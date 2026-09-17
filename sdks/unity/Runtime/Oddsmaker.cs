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

    public class Oddsmaker : MonoBehaviour
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
            uint h = Hash32(expId + ":" + (salt ?? "") + ":" + key);
            int r = (int)(h % (uint)sum);
            int acc = 0;
            foreach (var v in variants)
            {
                acc += v.Item2 > 0 ? v.Item2 : 1;
                if (r < acc) return v.Item1;
            }
            return variants[0].Item1;
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

        /** 从 /v1/batch 2xx 响应体提取 reason=kafka_error 的拒绝事件(临时故障,需重发);响应体缺失/不可解析按全成功处理。 */
        private List<Event> ParseKafkaErrorIds(string responseBody, List<Event> slice)
        {
            var retryable = new List<Event>();
            if (string.IsNullOrEmpty(responseBody)) return retryable;
            BatchResponseDto dto;
            try
            {
                dto = JsonUtility.FromJson<BatchResponseDto>(responseBody);
            }
            catch (Exception)
            {
                return retryable;
            }
            if (dto == null || dto.rejected == null || dto.rejected.Count == 0) return retryable;
            var reasons = new Dictionary<string, string>();
            foreach (var r in dto.rejected)
            {
                if (r != null && !string.IsNullOrEmpty(r.event_id)) reasons[r.event_id] = r.reason;
            }
            if (reasons.Count == 0) return retryable;
            foreach (var e in slice)
            {
                if (!reasons.TryGetValue(e.event_id, out string reason)) continue;
                if (reason == "kafka_error") retryable.Add(e);
                else OnError?.Invoke(new OddsmakerError("EVENT_REJECTED", $"event rejected: {e.event_id} {reason}"));
            }
            return retryable;
        }

        private void RollSession(long nowMs)
        {
            if (string.IsNullOrEmpty(_sessionId) || nowMs - _lastActiveMs > _opts.sessionGapSec * 1000L)
            {
                _sessionId = UuidV7();
            }
        }

        private void LoadQueue()
        {
            try
            {
                if (!File.Exists(QueuePath)) return;
                foreach (var line in File.ReadAllLines(QueuePath))
                {
                    if (string.IsNullOrWhiteSpace(line)) continue;
                    var restored = FromJson(line);
                    if (restored != null) _queue.Add(restored);
                }
                RecalcQueueBytes();
            }
            catch (Exception ex)
            {
                LogDebug($"load queue error: {ex.Message}");
            }
        }

        private void SaveQueue()
        {
            try
            {
                var sb = new StringBuilder(_queue.Count * 128);
                foreach (var e in _queue) sb.AppendLine(ToJson(e));
                File.WriteAllText(QueuePath, sb.ToString());
            }
            catch (Exception ex)
            {
                LogDebug($"save queue error: {ex.Message}");
            }
        }

        private void LoadExperimentCache()
        {
            try
            {
                if (!File.Exists(ExperimentCachePath)) return;
                string json = File.ReadAllText(ExperimentCachePath);
                var cache = JsonUtility.FromJson<ExperimentCache>(json);
                if (cache != null)
                {
                    _experimentCacheTimestamp = cache.timestamp;
                    _experimentCacheData = cache.data ?? "";
                }
            }
            catch (Exception ex)
            {
                LogDebug($"load experiment cache error: {ex.Message}");
            }
        }

        private void SaveExperimentCache()
        {
            try
            {
                var cache = new ExperimentCache
                {
                    timestamp = _experimentCacheTimestamp,
                    data = _experimentCacheData
                };
                string json = JsonUtility.ToJson(cache);
                File.WriteAllText(ExperimentCachePath, json);
            }
            catch (Exception ex)
            {
                LogDebug($"save experiment cache error: {ex.Message}");
            }
        }

        private void RecalcQueueBytes()
        {
            _queueBytes = 0;
            foreach (var e in _queue) _queueBytes += EstimateSize(e);
        }

        private static int EstimateSize(Event e) => Encoding.UTF8.GetByteCount(ToJson(e)) + 1;

        private static string BuildNdjson(List<Event> events)
        {
            var sb = new StringBuilder(events.Count * 128);
            for (int i = 0; i < events.Count; i++)
            {
                sb.Append(ToJson(events[i]));
                if (i < events.Count - 1) sb.Append('\n');
            }
            return sb.ToString();
        }

        private static string ToJson(Event e)
        {
            if (!string.IsNullOrEmpty(e.raw_json)) return e.raw_json;
            var sb = new StringBuilder(256);
            sb.Append('{');
            JField(sb, "event_id", e.event_id);
            JField(sb, "game_id", e.game_id);
            JField(sb, "environment", e.environment);
            JField(sb, "event_type", e.event_type);
            JField(sb, "event_name", e.event_name);
            if (!string.IsNullOrEmpty(e.user_id)) JField(sb, "user_id", e.user_id);
            JField(sb, "device_id", e.device_id);
            if (!string.IsNullOrEmpty(e.player_id)) JField(sb, "player_id", e.player_id);
            if (!string.IsNullOrEmpty(e.session_id)) JField(sb, "session_id", e.session_id);
            JField(sb, "ts_client", e.ts_client);
            if (!string.IsNullOrEmpty(e.platform)) JField(sb, "platform", e.platform);
            if (!string.IsNullOrEmpty(e.app_version)) JField(sb, "app_version", e.app_version);
            if (!string.IsNullOrEmpty(e.sdk_version)) JField(sb, "sdk_version", e.sdk_version);
            if (!string.IsNullOrEmpty(e.country)) JField(sb, "country", e.country);
            if (!string.IsNullOrEmpty(e.level_id)) JField(sb, "level_id", e.level_id);
            if (!string.IsNullOrEmpty(e.game_mode)) JField(sb, "game_mode", e.game_mode);
            if (!string.IsNullOrEmpty(e.order_id)) JField(sb, "order_id", e.order_id);
            if (!string.IsNullOrEmpty(e.product_id)) JField(sb, "product_id", e.product_id);
            if (e.revenue_amount.HasValue) JField(sb, "revenue_amount", e.revenue_amount.Value);
            if (!string.IsNullOrEmpty(e.revenue_currency)) JField(sb, "revenue_currency", e.revenue_currency);
            if (!string.IsNullOrEmpty(e.virtual_currency)) JField(sb, "virtual_currency", e.virtual_currency);
            if (e.virtual_amount.HasValue) JField(sb, "virtual_amount", e.virtual_amount.Value);
            if (!string.IsNullOrEmpty(e.item_id)) JField(sb, "item_id", e.item_id);
            if (!string.IsNullOrEmpty(e.resource_id)) JField(sb, "resource_id", e.resource_id);
            if (e.resource_amount.HasValue) JField(sb, "resource_amount", e.resource_amount.Value);
            if (!string.IsNullOrEmpty(e.flow_type)) JField(sb, "flow_type", e.flow_type);
            if (!string.IsNullOrEmpty(e.ad_network)) JField(sb, "ad_network", e.ad_network);
            if (!string.IsNullOrEmpty(e.ad_placement)) JField(sb, "ad_placement", e.ad_placement);
            if (!string.IsNullOrEmpty(e.ad_format)) JField(sb, "ad_format", e.ad_format);
            if (e.props != null && e.props.Count > 0) JObject(sb, "props", e.props);
            if (sb[sb.Length - 1] == ',') sb.Length -= 1;
            sb.Append('}');
            return sb.ToString();
        }

        private static Event FromJson(string line)
        {
            try
            {
                var e = new Event
                {
                    raw_json = line,
                    event_id = JsonString(line, "event_id"),
                    game_id = JsonString(line, "game_id"),
                    environment = JsonString(line, "environment"),
                    event_type = JsonString(line, "event_type"),
                    event_name = JsonString(line, "event_name"),
                    user_id = JsonString(line, "user_id"),
                    device_id = JsonString(line, "device_id"),
                    player_id = JsonString(line, "player_id"),
                    session_id = JsonString(line, "session_id"),
                    ts_client = JsonLong(line, "ts_client"),
                    platform = JsonString(line, "platform"),
                    app_version = JsonString(line, "app_version"),
                    sdk_version = JsonString(line, "sdk_version"),
                    country = JsonString(line, "country"),
                    level_id = JsonString(line, "level_id"),
                    game_mode = JsonString(line, "game_mode"),
                    order_id = JsonString(line, "order_id"),
                    product_id = JsonString(line, "product_id"),
                    revenue_currency = JsonString(line, "revenue_currency"),
                    virtual_currency = JsonString(line, "virtual_currency"),
                    item_id = JsonString(line, "item_id"),
                    resource_id = JsonString(line, "resource_id"),
                    flow_type = JsonString(line, "flow_type"),
                    ad_network = JsonString(line, "ad_network"),
                    ad_placement = JsonString(line, "ad_placement"),
                    ad_format = JsonString(line, "ad_format")
                };
                if (string.IsNullOrEmpty(e.event_id) ||
                    string.IsNullOrEmpty(e.game_id) ||
                    string.IsNullOrEmpty(e.environment) ||
                    string.IsNullOrEmpty(e.event_name) ||
                    string.IsNullOrEmpty(e.device_id) ||
                    e.ts_client <= 0) return null;
                if (string.IsNullOrEmpty(e.event_type)) e.event_type = InferEventType(e.event_name);
                return e;
            }
            catch
            {
                return null;
            }
        }

        // JSON工具方法

        private static string JsonString(string json, string key)
        {
            string marker = "\"" + key + "\"";
            int i = json.IndexOf(marker, StringComparison.Ordinal);
            if (i < 0) return null;
            i = json.IndexOf(':', i + marker.Length);
            if (i < 0) return null;
            i++;
            while (i < json.Length && char.IsWhiteSpace(json[i])) i++;
            if (i >= json.Length || json[i] != '"') return null;
            i++;
            var sb = new StringBuilder();
            bool esc = false;
            for (; i < json.Length; i++)
            {
                char c = json[i];
                if (esc)
                {
                    sb.Append(c);
                    esc = false;
                }
                else if (c == '\\')
                {
                    esc = true;
                }
                else if (c == '"')
                {
                    return sb.ToString();
                }
                else
                {
                    sb.Append(c);
                }
            }
            return null;
        }

        private static long JsonLong(string json, string key)
        {
            string marker = "\"" + key + "\"";
            int i = json.IndexOf(marker, StringComparison.Ordinal);
            if (i < 0) return 0;
            i = json.IndexOf(':', i + marker.Length);
            if (i < 0) return 0;
            i++;
            while (i < json.Length && char.IsWhiteSpace(json[i])) i++;
            int start = i;
            while (i < json.Length && (char.IsDigit(json[i]) || json[i] == '-')) i++;
            return long.TryParse(json.Substring(start, i - start), out var value) ? value : 0;
        }

        private static void JField(StringBuilder sb, string k, string v)
        {
            sb.Append('"').Append(JEscape(k)).Append('"').Append(':')
              .Append('"').Append(JEscape(v)).Append('"').Append(',');
        }

        private static void JField(StringBuilder sb, string k, long v)
        {
            sb.Append('"').Append(JEscape(k)).Append('"').Append(':').Append(v).Append(',');
        }

        private static void JField(StringBuilder sb, string k, double v)
        {
            sb.Append('"').Append(JEscape(k)).Append('"').Append(':')
              .Append(v.ToString(System.Globalization.CultureInfo.InvariantCulture))
              .Append(',');
        }

        private static void JObject(StringBuilder sb, string k, Dictionary<string, object> map, int depth = 0)
        {
            if (depth > 2) return;
            sb.Append('"').Append(JEscape(k)).Append('"').Append(':');
            AppendObject(sb, map, depth);
            sb.Append(',');
        }

        private static void AppendObject(StringBuilder sb, Dictionary<string, object> map, int depth)
        {
            if (depth > 2) { sb.Append("{}"); return; }
            sb.Append('{');
            int count = 0;
            foreach (var kv in map)
            {
                if (count >= 50) break;
                sb.Append('"').Append(JEscape(kv.Key)).Append('"').Append(':');
                JValue(sb, kv.Value, depth + 1);
                sb.Append(',');
                count++;
            }
            if (sb[sb.Length - 1] == ',') sb.Length -= 1;
            sb.Append('}');
        }

        private static void JArray(StringBuilder sb, IList list, int depth)
        {
            if (depth > 2) { sb.Append("[]"); return; }
            sb.Append('[');
            int lim = Math.Min(50, list.Count);
            for (int i = 0; i < lim; i++)
            {
                JValue(sb, list[i], depth + 1);
                sb.Append(',');
            }
            if (sb[sb.Length - 1] == ',') sb.Length -= 1;
            sb.Append(']');
        }

        private static void JValue(StringBuilder sb, object v, int depth)
        {
            if (v == null) { sb.Append("null"); return; }
            switch (v)
            {
                case string s:
                    sb.Append('"').Append(JEscape(s)).Append('"'); break;
                case bool b:
                    sb.Append(b ? "true" : "false"); break;
                case int or long or float or double or decimal:
                    sb.Append(Convert.ToString(v, System.Globalization.CultureInfo.InvariantCulture)); break;
                case Dictionary<string, object> m:
                    AppendObject(sb, m, depth); break;
                case IList list:
                    JArray(sb, list, depth); break;
                default:
                    sb.Append('"').Append(JEscape(v.ToString())).Append('"'); break;
            }
        }

        private static string JEscape(string s)
        {
            if (s == null) return string.Empty;
            var sb = new StringBuilder(s.Length + 8);
            foreach (char c in s)
            {
                switch (c)
                {
                    case '\\': sb.Append("\\\\"); break;
                    case '"': sb.Append("\\\""); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    default:
                        if (c < ' ') sb.Append("\\u").Append(((int)c).ToString("x4"));
                        else sb.Append(c);
                        break;
                }
            }
            return sb.ToString();
        }

        private static bool TryGzip(ref byte[] body)
        {
            try
            {
                using var ms = new MemoryStream();
                using (var gz = new GZipStream(ms, CompressionLevel.Fastest, leaveOpen: true))
                {
                    gz.Write(body, 0, body.Length);
                }
                body = ms.ToArray();
                return true;
            }
            catch { return false; }
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