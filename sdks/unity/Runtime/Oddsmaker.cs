// Oddsmaker Unity SDK (Runtime)
// Complete implementation with caching, error handling, and experiment support.
// Client SDK 不持有任何签名密钥；HMAC 签名仅适用于 Server SDK。

using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using UnityEngine;
using UnityEngine.Networking;

namespace Oddsmaker
{
    [Serializable]
    public class Options
    {
        public string apiKey;
        public string endpoint;
        public string gameId;
        public string environment;
        public string deviceId = null;
        public int flushIntervalSec = 5;
        public int maxBatch = 50;
        public int maxQueueBytes = 512 * 1024;
        public int sessionGapSec = 30 * 60;
        public bool debug = false;
        
        // 重试配置
        public int maxRetries = 3;
        public int retryDelayMs = 1000;
    }

    [Serializable]
    public class Event
    {
        [NonSerialized]
        public string raw_json;
        public string event_id;
        public string game_id;
        public string environment;
        public string event_type;
        public string event_name;
        public string user_id;
        public string device_id;
        public string player_id;
        public string session_id;
        public long ts_client;
        public string platform = "unity";
        public string app_version;
        public string sdk_version = "1.0.0";
        public string country;
        public string level_id;
        public string game_mode;
        public string order_id;
        public string product_id;
        public double? revenue_amount;
        public string revenue_currency;
        public string virtual_currency;
        public double? virtual_amount;
        public string item_id;
        public string resource_id;
        public double? resource_amount;
        public string flow_type;
        public string ad_network;
        public string ad_placement;
        public string ad_format;
        public Dictionary<string, object> props;
    }

    [Serializable]
    public class ExperimentCache
    {
        public long timestamp;
        public string data;
    }

    // gateway /v1/batch 响应(JsonUtility 反序列化用):逐事件拒绝清单
    [Serializable]
    public class BatchRejectedEntry
    {
        public string event_id;
        public string reason;
    }

    [Serializable]
    public class BatchResponseDto
    {
        public List<string> accepted;
        public List<BatchRejectedEntry> rejected;
    }

    public class OddsmakerError
    {
        public string Code { get; set; }
        public string Message { get; set; }
        public Exception Exception { get; set; }

        public OddsmakerError(string code, string message, Exception exception = null)
        {
            Code = code;
            Message = message;
            Exception = exception;
        }
    }

    public partial class Oddsmaker : MonoBehaviour
    {
        public static Oddsmaker Instance { get; private set; }

        private Options _opts;
        private string _deviceId;
        private string _userId = null;
        private string _playerId = null;
        private readonly Dictionary<string, object> _userProps = new Dictionary<string, object>();
        private string _sessionId = null;
        private long _lastActiveMs = 0;

        private readonly List<Event> _queue = new List<Event>();
        private int _queueBytes = 0;
        private bool _isFlushing = false;
        private int _retryCount = 0;

        // 实验缓存:原始配置 JSON + 时间戳(与 Android/iOS 的 "ts\njson" 语义一致)
        private string _experimentCacheData = "";
        private long _experimentCacheTimestamp = 0;
        private const int EXPERIMENT_CACHE_TTL_SEC = 300;

        // 错误回调
        public event Action<OddsmakerError> OnError;

        // 统计
        private long _totalEventsSent = 0;
        private long _totalEventsFailed = 0;
        private long _totalFlushAttempts = 0;

        private string QueuePath => Path.Combine(Application.persistentDataPath, $"oddsmaker_queue_{_opts.gameId}_{_opts.environment}_{_deviceId}.ndjson");
        private string ExperimentCachePath => Path.Combine(Application.persistentDataPath, $"oddsmaker_experiments_{_opts.gameId}_{_opts.environment}.json");
        private string DevKey => $"oddsmaker_device_id_{_opts.gameId}_{_opts.environment}";

        public static void Init(Options options)
        {
            if (Instance != null) return;
            var go = new GameObject("OddsmakerClient");
            DontDestroyOnLoad(go);
            Instance = go.AddComponent<Oddsmaker>();
            Instance.Configure(options);
        }

        private void Configure(Options opts)
        {
            _opts = opts;
            _deviceId = string.IsNullOrEmpty(_opts.deviceId)
                ? PlayerPrefs.GetString(DevKey, string.Empty)
                : _opts.deviceId;
            if (string.IsNullOrEmpty(_deviceId))
            {
                var duid = SystemInfo.deviceUniqueIdentifier;
                if (!string.IsNullOrEmpty(duid)) _deviceId = Hash("d_", duid);
                else _deviceId = "d_" + Guid.NewGuid().ToString("N");
                PlayerPrefs.SetString(DevKey, _deviceId);
                PlayerPrefs.Save();
            }
            LoadQueue();
            LoadExperimentCache();
            _lastActiveMs = NowMs();
            StartCoroutine(FlushLoop());
            LogDebug($"Oddsmaker initialized. deviceId={_deviceId}, queue={_queue.Count}");
        }

        private void OnDestroy()
        {
            SaveQueue();
            SaveExperimentCache();
        }

        private void OnApplicationPause(bool pauseStatus)
        {
            if (pauseStatus)
            {
                SaveQueue();
                SaveExperimentCache();
            }
            else
            {
                _lastActiveMs = NowMs();
            }
        }

        // 用户管理

        public static void SetUserId(string userId)
        {
            if (Instance == null) return;
            Instance._userId = string.IsNullOrEmpty(userId) ? null : userId;
        }

        public static void SetPlayer(string playerId)
        {
            if (Instance == null) return;
            Instance._playerId = string.IsNullOrEmpty(playerId) ? null : playerId;
        }

        public static string Identify(string newUserId, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            var previousUserId = Instance._userId;
            Instance._userId = newUserId;
            var identifyProps = new Dictionary<string, object>
            {
                { "$identify", true },
                { "new_user_id", newUserId }
            };
            if (!string.IsNullOrEmpty(previousUserId) && previousUserId != newUserId)
            {
                identifyProps["previous_user_id"] = previousUserId;
            }
            if (!string.IsNullOrEmpty(Instance._playerId))
            {
                identifyProps["player_id"] = Instance._playerId;
            }
            if (props != null)
            {
                foreach (var kv in props) identifyProps[kv.Key] = kv.Value;
            }
            return Instance.TrackInternal("$identify", identifyProps);
        }

        public static void SetUserProps(Dictionary<string, object> props)
        {
            if (Instance == null || props == null) return;
            foreach (var kv in props) Instance._userProps[kv.Key] = kv.Value;
        }

        // 事件跟踪

        public static string Track(string eventName, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            return Instance.TrackInternal(eventName, props);
        }

        public static string Expose(string exp, string variant)
        {
            return Track("experiment_exposure", new Dictionary<string, object> { { "exp", exp }, { "variant", variant } });
        }

        public static string Revenue(double amount, string currency, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            string code = currency == null ? null : currency.ToUpperInvariant();
            var p = WithCoreProps(props, new Dictionary<string, object> { { "amount", amount }, { "currency", code } });
            return Instance.TrackInternal("revenue", p, revenueAmount: amount, revenueCurrency: code);
        }

        // ---- 类型化便捷事件:与 Web SDK 对齐,填充事件契约顶层字段(分析视图直接消费) ----

        public static string LevelStart(string levelId, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            var merged = WithCoreProps(props, new Dictionary<string, object> { { "level_id", levelId } });
            merged.TryGetValue("game_mode", out object gm);
            return Instance.TrackInternal("level_start", merged, levelId: levelId, gameMode: gm as string);
        }

        public static string LevelFail(string levelId, string reason, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            var merged = WithCoreProps(props, new Dictionary<string, object> { { "level_id", levelId }, { "fail_reason", reason } });
            merged.TryGetValue("game_mode", out object gm);
            return Instance.TrackInternal("level_fail", merged, levelId: levelId, gameMode: gm as string);
        }

        public static string LevelComplete(string levelId, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            var merged = WithCoreProps(props, new Dictionary<string, object> { { "level_id", levelId } });
            merged.TryGetValue("game_mode", out object gm);
            return Instance.TrackInternal("level_complete", merged, levelId: levelId, gameMode: gm as string);
        }

        public static string CurrencySource(string currency, double amount, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            string code = currency == null ? null : currency.ToUpperInvariant();
            var merged = WithCoreProps(props, new Dictionary<string, object> { { "currency_code", code }, { "amount", amount } });
            return Instance.TrackInternal("currency_source", merged,
                resourceId: code, resourceAmount: amount, virtualCurrency: code, virtualAmount: amount, flowType: "source");
        }

        public static string CurrencySink(string currency, double amount, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            string code = currency == null ? null : currency.ToUpperInvariant();
            var merged = WithCoreProps(props, new Dictionary<string, object> { { "currency_code", code }, { "amount", amount } });
            return Instance.TrackInternal("currency_sink", merged,
                resourceId: code, resourceAmount: amount, virtualCurrency: code, virtualAmount: amount, flowType: "sink");
        }

        public static string ItemGrant(string itemId, int quantity = 1, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            var merged = WithCoreProps(props, new Dictionary<string, object> { { "item_id", itemId }, { "quantity", quantity } });
            return Instance.TrackInternal("item_grant", merged,
                itemId: itemId, resourceId: itemId, resourceAmount: quantity, flowType: "source");
        }

        public static string ItemConsume(string itemId, int quantity = 1, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            var merged = WithCoreProps(props, new Dictionary<string, object> { { "item_id", itemId }, { "quantity", quantity } });
            return Instance.TrackInternal("item_consume", merged,
                itemId: itemId, resourceId: itemId, resourceAmount: quantity, flowType: "sink");
        }

        public static string IapOrder(string orderId, double amount, string currency, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            string code = currency == null ? null : currency.ToUpperInvariant();
            var merged = WithCoreProps(
                WithCoreProps(props, new Dictionary<string, object> { { "order_id", orderId } }),
                new Dictionary<string, object> { { "amount", amount }, { "currency", code } });
            merged.TryGetValue("product_id", out object pid);
            return Instance.TrackInternal("iap_order", merged,
                orderId: orderId, productId: pid as string, revenueAmount: amount, revenueCurrency: code);
        }

        public static string WebshopOrder(string orderId, double amount, string currency, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            string code = currency == null ? null : currency.ToUpperInvariant();
            var merged = WithCoreProps(
                WithCoreProps(props, new Dictionary<string, object> { { "order_id", orderId } }),
                new Dictionary<string, object> { { "amount", amount }, { "currency", code } });
            merged.TryGetValue("product_id", out object pid);
            return Instance.TrackInternal("webshop_order", merged,
                orderId: orderId, productId: pid as string, revenueAmount: amount, revenueCurrency: code);
        }

        public static string AdImpression(double amount, string currency, Dictionary<string, object> props = null)
        {
            if (Instance == null) return null;
            string code = currency == null ? null : currency.ToUpperInvariant();
            var merged = WithCoreProps(props, new Dictionary<string, object> { { "amount", amount }, { "currency", code } });
            merged.TryGetValue("network", out object network);
            merged.TryGetValue("placement_id", out object placement);
            merged.TryGetValue("ad_format", out object format);
            return Instance.TrackInternal("ad_impression", merged,
                adNetwork: network as string, adPlacement: placement as string, adFormat: format as string,
                revenueAmount: amount, revenueCurrency: code);
        }

        private static Dictionary<string, object> WithCoreProps(Dictionary<string, object> props, Dictionary<string, object> core)
        {
            var merged = props == null ? new Dictionary<string, object>() : new Dictionary<string, object>(props);
            foreach (var kv in core) merged[kv.Key] = kv.Value;
            return merged;
        }

        public static void Flush()
        {
            if (Instance != null) Instance.StartCoroutine(Instance.FlushOnce());
        }

        // 实验支持

        public static void FetchExperiments(string controlURL, Action<string> callback)
        {
            if (Instance == null) return;
            Instance.StartCoroutine(Instance.FetchExperimentsCoroutine(controlURL, callback));
        }

        public static void FetchExperimentsCached(string controlURL, Action<string> callback, int ttlSec = 300)
        {
            if (Instance == null) return;

            // TTL 内命中缓存 → 直接回调缓存的原始配置 JSON(与 Web/Android/iOS 语义一致)
            if (Instance._experimentCacheTimestamp > 0 && !string.IsNullOrEmpty(Instance._experimentCacheData))
            {
                long now = NowMs();
                if (now - Instance._experimentCacheTimestamp < ttlSec * 1000L)
                {
                    callback?.Invoke(Instance._experimentCacheData);
                    return;
                }
            }

            Instance.StartCoroutine(Instance.FetchExperimentsCoroutine(controlURL, (data) =>
            {
                Instance._experimentCacheTimestamp = NowMs();
                Instance._experimentCacheData = data ?? "";
                Instance.SaveExperimentCache();
                callback?.Invoke(data);
            }));
        }

        public static string AssignVariant(string expId, string salt, List<Tuple<string, int>> variants, string key)
        {
            if (variants == null || variants.Count == 0) return "A";
            int sum = 0;
            foreach (var v in variants) sum += v.Item2 > 0 ? v.Item2 : 1;
            // 与服务端 ExperimentSplitter 同源：无符号哈希对 int32 总权重取模（long 模，sum 符号扩展）。
            // 原 h % (uint)sum 在 sum 溢出为负时把模数变成 2^32+sum 的无符号值，落位与服务端分裂；
            // sum 回绕到 0 时 (uint)sum 会除零崩溃。兜底统一为末变体（原 first）
            if (sum == 0) return variants[variants.Count - 1].Item1;
            long h = (long)Hash32(expId + ":" + (salt ?? "") + ":" + key) % sum;
            int acc = 0;
            foreach (var v in variants)
            {
                acc += v.Item2 > 0 ? v.Item2 : 1;
                if (h < acc) return v.Item1;
            }
            return variants[variants.Count - 1].Item1;
        }

        // 统计信息

        public static Dictionary<string, object> GetStats()
        {
            if (Instance == null) return new Dictionary<string, object>();
            return new Dictionary<string, object>
            {
                { "totalEventsSent", Instance._totalEventsSent },
                { "totalEventsFailed", Instance._totalEventsFailed },
                { "totalFlushAttempts", Instance._totalFlushAttempts },
                { "queueSize", Instance._queue.Count },
                { "queueBytes", Instance._queueBytes }
            };
        }

        // 内部实现

        private string TrackInternal(string eventName, Dictionary<string, object> props,
            string levelId = null, string gameMode = null,
            string orderId = null, string productId = null,
            double? revenueAmount = null, string revenueCurrency = null,
            string virtualCurrency = null, double? virtualAmount = null,
            string itemId = null, string resourceId = null, double? resourceAmount = null,
            string flowType = null,
            string adNetwork = null, string adPlacement = null, string adFormat = null)
        {
            long now = NowMs();
            RollSession(now);
            var e = new Event
            {
                event_id = UuidV7(),
                game_id = _opts.gameId,
                environment = _opts.environment,
                event_type = InferEventType(eventName),
                event_name = eventName,
                user_id = _userId,
                device_id = _deviceId,
                player_id = _playerId,
                session_id = _sessionId,
                ts_client = now,
                platform = "unity",
                app_version = Application.version,
                sdk_version = "1.0.0",
                level_id = levelId,
                game_mode = gameMode,
                order_id = orderId,
                product_id = productId,
                revenue_amount = revenueAmount,
                revenue_currency = revenueCurrency,
                virtual_currency = virtualCurrency,
                virtual_amount = virtualAmount,
                item_id = itemId,
                resource_id = resourceId,
                resource_amount = resourceAmount,
                flow_type = flowType,
                ad_network = adNetwork,
                ad_placement = adPlacement,
                ad_format = adFormat,
                props = MergeProps(props)
            };
            int est = EstimateSize(e);
            _queue.Add(e);
            _queueBytes += est;
            _lastActiveMs = now;
            if (_opts.debug) LogDebug($"queued {e.event_id} {e.event_name} bytes={est}");
            if (_queueBytes >= _opts.maxQueueBytes || _queue.Count >= _opts.maxBatch) StartCoroutine(FlushOnce());
            SaveQueue();
            return e.event_id;
        }

        private IEnumerator FlushLoop()
        {
            var wait = new WaitForSeconds(_opts.flushIntervalSec);
            while (true)
            {
                yield return wait;
                if (!_isFlushing && _queue.Count > 0)
                {
                    yield return FlushOnce();
                }
            }
        }

        private Dictionary<string, object> MergeProps(Dictionary<string, object> props)
        {
            var merged = new Dictionary<string, object>(_userProps);
            if (!string.IsNullOrEmpty(_playerId)) merged["player_id"] = _playerId;
            if (props != null)
            {
                foreach (var kv in props) merged[kv.Key] = kv.Value;
            }
            return merged;
        }

        private IEnumerator FlushOnce()
        {
            if (_isFlushing || _queue.Count == 0) yield break;
            _isFlushing = true;
            _totalFlushAttempts++;
            
            try
            {
                int n = Math.Min(_opts.maxBatch, _queue.Count);
                var slice = _queue.GetRange(0, n);
                string ndjson = BuildNdjson(slice);
                byte[] body = Encoding.UTF8.GetBytes(ndjson);
                bool gzOk = TryGzip(ref body);

                var url = _opts.endpoint.TrimEnd('/') + "/v1/batch";
                var req = new UnityWebRequest(url, UnityWebRequest.kHttpVerbPOST);
                req.uploadHandler = new UploadHandlerRaw(body);
                req.downloadHandler = new DownloadHandlerBuffer();
                req.SetRequestHeader("x-api-key", _opts.apiKey);
                req.SetRequestHeader("content-type", "application/x-ndjson");
                req.SetRequestHeader("x-sdk-version", "unity-1.0.0");
                if (gzOk) req.SetRequestHeader("content-encoding", "gzip");

                yield return req.SendWebRequest();
                
                if (req.result == UnityWebRequest.Result.Success && req.responseCode >= 200 && req.responseCode < 300)
                {
                    // 2xx 响应体仍含逐事件拒绝:仅 kafka_error 回队首发重试,其余永久失败丢弃并告警
                    var retryable = ParseKafkaErrorIds(req.downloadHandler != null ? req.downloadHandler.text : null, slice);

                    _queue.RemoveRange(0, n);
                    if (retryable.Count > 0)
                    {
                        _queue.InsertRange(0, retryable);
                    }
                    RecalcQueueBytes();
                    SaveQueue();   // kafka_error 回队的事件立即落盘,进程被杀也不丢
                    _totalEventsSent += n - retryable.Count;
                    _retryCount = 0;
                    if (_opts.debug) LogDebug($"flushed {n - retryable.Count} events ok, {retryable.Count} retryable");
                }
                else
                {
                    _totalEventsFailed += n;
                    string errorMsg = $"flush failed: {req.responseCode} {req.error}";
                    LogDebug(errorMsg);
                    
                    // 重试逻辑
                    if (_retryCount < _opts.maxRetries)
                    {
                        _retryCount++;
                        LogDebug($"retry {_retryCount}/{_opts.maxRetries}");
                        yield return new WaitForSeconds(_opts.retryDelayMs / 1000f);
                    }
                    else
                    {
                        _retryCount = 0;
                        OnError?.Invoke(new OddsmakerError("FLUSH_FAILED", errorMsg));
                    }
                }
            }
            finally
            {
                SaveQueue();
                _isFlushing = false;
            }
        }

        private IEnumerator FetchExperimentsCoroutine(string controlURL, Action<string> callback)
        {
            string url = controlURL.TrimEnd('/') + $"/api/config/{_opts.gameId}/{_opts.environment}";

            using (var req = UnityWebRequest.Get(url))
            {
                req.SetRequestHeader("accept", "application/json");

                yield return req.SendWebRequest();

                if (req.result == UnityWebRequest.Result.Success)
                {
                    string data = req.downloadHandler.text;
                    callback?.Invoke(data);
                }
                else
                {
                    string errorMsg = $"fetch experiments failed: {req.error}";
                    LogDebug(errorMsg);
                    OnError?.Invoke(new OddsmakerError("FETCH_EXPERIMENTS_FAILED", errorMsg));
                    callback?.Invoke(null);
                }
            }
        }

        private void RollSession(long nowMs)
        {
            if (string.IsNullOrEmpty(_sessionId) || nowMs - _lastActiveMs > _opts.sessionGapSec * 1000L)
            {
                _sessionId = UuidV7();
            }
        }

        private static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

        private static string Hash(string prefix, string v)
        {
            using var sha = SHA1.Create();
            var bytes = sha.ComputeHash(Encoding.UTF8.GetBytes(v));
            var sb = new StringBuilder(prefix);
            for (int i = 0; i < 12; i++) sb.Append(bytes[i].ToString("x2"));
            return sb.ToString();
        }

        private static string UuidV7()
        {
            long ms = NowMs();
            string ts = ms.ToString("x").PadLeft(12, '0');
            string rnd = Guid.NewGuid().ToString("N");
            string hex = ts + rnd.Substring(0, 20);
            return hex.Substring(0, 8) + "-" +
                   hex.Substring(8, 4) + "-7" +
                   hex.Substring(13, 3) + "-" +
                   hex.Substring(16, 4) + "-" +
                   hex.Substring(20, 12);
        }

        private static string InferEventType(string eventName)
        {
            var name = (eventName ?? string.Empty).ToLowerInvariant();
            if (name == "$identify" || name.Contains("identity")) return "identity";
            if (name.Contains("risk") || name.Contains("fraud")) return "risk";
            if (name.Contains("experiment")) return "experiment";
            if (name.Contains("ad_")) return "ad";
            if (name.Contains("level") || name.Contains("quest")) return "progression";
            if (name.Contains("session")) return "session";
            if (name.Contains("error") || name.Contains("crash")) return "error";
            return "business";
        }

        private static uint Hash32(string s)
        {
            uint h = 0x811c9dc5;
            foreach (char ch in s)
            {
                h ^= (uint)ch;
                h += (h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24);
            }
            return h;
        }

        private static void LogDebug(string msg) { Debug.Log("[Oddsmaker] " + msg); }
    }
}