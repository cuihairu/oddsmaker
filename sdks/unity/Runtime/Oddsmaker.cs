// Oddsmaker Unity SDK (Runtime)
// Minimal batching NDJSON client with gzip (optional), offline persistence, and session management.
// Drop this under Assets (e.g., Assets/Oddsmaker/Runtime/Oddsmaker.cs) and call Oddsmaker.Init(...).

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
        public string endpoint;  // e.g. http://localhost:8080
        public string gameId;
        public string environment;
        public string deviceId = null; // optional override
        public int flushIntervalSec = 5;
        public int maxBatch = 50;
        public int maxQueueBytes = 512 * 1024;
        public int sessionGapSec = 30 * 60;
        public bool debug = false;
    }

    [Serializable]
    public class Event
    {
        public string event_id;
        public string game_id;
        public string environment;
        public string event_type;
        public string event_name;
        public string user_id;
        public string device_id;
        public string session_id;
        public long ts_client;
        public string platform = "unity";
        public string app_version;
        public string country;
        public double? revenue_amount;
        public string revenue_currency;
        public Dictionary<string, object> props;
    }

    public class Oddsmaker : MonoBehaviour
    {
        public static Oddsmaker Instance { get; private set; }

        private Options _opts;
        private string _deviceId;
        private string _userId = null;
        private string _sessionId = null;
        private long _lastActiveMs = 0;

        private readonly List<Event> _queue = new List<Event>();
        private int _queueBytes = 0;
        private bool _isFlushing = false;

        private string QueuePath => Path.Combine(Application.persistentDataPath, $"oddsmaker_queue_{_opts.gameId}_{_opts.environment}_{_deviceId}.ndjson");
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
            _lastActiveMs = NowMs();
            StartCoroutine(FlushLoop());
            LogDebug($"Oddsmaker initialized. deviceId={_deviceId}, queue={_queue.Count}");
        }

        public static void SetUserId(string userId)
        {
            if (Instance == null) return;
            Instance._userId = string.IsNullOrEmpty(userId) ? null : userId;
        }

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
            var p = props == null ? new Dictionary<string, object>() : new Dictionary<string, object>(props);
            p["amount"] = amount;
            p["currency"] = currency;
            return Instance.TrackInternal("revenue", p, amount, currency);
        }

        public static void Flush()
        {
            if (Instance != null) Instance.StartCoroutine(Instance.FlushOnce());
        }

        private string TrackInternal(string eventName, Dictionary<string, object> props, double? revenueAmount = null, string revenueCurrency = null)
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
                session_id = _sessionId,
                ts_client = now,
                platform = "unity",
                revenue_amount = revenueAmount,
                revenue_currency = revenueCurrency,
                props = props
            };
            int est = EstimateSize(e);
            _queue.Add(e);
            _queueBytes += est;
            _lastActiveMs = now;
            if (_opts.debug) LogDebug($"queued {e.event_id} {e.event_name} bytes={est}");
            if (_queueBytes >= _opts.maxQueueBytes) StartCoroutine(FlushOnce());
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

        private IEnumerator FlushOnce()
        {
            if (_isFlushing || _queue.Count == 0) yield break;
            _isFlushing = true;
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
                if (gzOk) req.SetRequestHeader("content-encoding", "gzip");

                yield return req.SendWebRequest();
                if (req.result == UnityWebRequest.Result.Success && req.responseCode >= 200 && req.responseCode < 300)
                {
                    _queue.RemoveRange(0, n);
                    RecalcQueueBytes();
                    if (_opts.debug) LogDebug($"flushed {n} events ok");
                }
                else if (_opts.debug)
                {
                    LogDebug($"flush failed: {req.responseCode} {req.error}");
                }
            }
            finally
            {
                SaveQueue();
                _isFlushing = false;
            }
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
                _ = File.ReadAllText(QueuePath);
                File.WriteAllText(QueuePath, string.Empty);
            }
            catch { }
        }

        private void SaveQueue()
        {
            try
            {
                var sb = new StringBuilder(_queue.Count * 128);
                foreach (var e in _queue) sb.AppendLine(ToJson(e));
                File.WriteAllText(QueuePath, sb.ToString());
            }
            catch { }
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
            var sb = new StringBuilder(256);
            sb.Append('{');
            JField(sb, "event_id", e.event_id);
            JField(sb, "game_id", e.game_id);
            JField(sb, "environment", e.environment);
            JField(sb, "event_type", e.event_type);
            JField(sb, "event_name", e.event_name);
            if (!string.IsNullOrEmpty(e.user_id)) JField(sb, "user_id", e.user_id);
            JField(sb, "device_id", e.device_id);
            if (!string.IsNullOrEmpty(e.session_id)) JField(sb, "session_id", e.session_id);
            JField(sb, "ts_client", e.ts_client);
            if (!string.IsNullOrEmpty(e.platform)) JField(sb, "platform", e.platform);
            if (!string.IsNullOrEmpty(e.app_version)) JField(sb, "app_version", e.app_version);
            if (!string.IsNullOrEmpty(e.country)) JField(sb, "country", e.country);
            if (e.revenue_amount.HasValue) JField(sb, "revenue_amount", e.revenue_amount.Value);
            if (!string.IsNullOrEmpty(e.revenue_currency)) JField(sb, "revenue_currency", e.revenue_currency);
            if (e.props != null && e.props.Count > 0) JObject(sb, "props", e.props);
            if (sb[sb.Length - 1] == ',') sb.Length -= 1;
            sb.Append('}');
            return sb.ToString();
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

        private static string JEscape(string s) => s.Replace("\\", "\\\\").Replace("\"", "\\\"");

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
            if (name.Contains("risk") || name.Contains("fraud")) return "risk";
            if (name.Contains("experiment")) return "experiment";
            if (name.Contains("ad_")) return "ad";
            if (name.Contains("level") || name.Contains("quest")) return "progression";
            if (name.Contains("session")) return "session";
            if (name.Contains("error") || name.Contains("crash")) return "error";
            return "business";
        }

        private static void LogDebug(string msg) { Debug.Log("[Oddsmaker] " + msg); }

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
    }
}
