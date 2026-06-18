// Pit Unity SDK (Runtime)
// Minimal batching NDJSON client with gzip (optional), offline persistence, and session management.
// Drop this under Assets (e.g., Assets/Pit/Runtime/Pit.cs) and call Pit.Init(...).

using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using UnityEngine;
using UnityEngine.Networking;

namespace Pit
{
    [Serializable]
    public class Options
    {
        public string apiKey;
        public string endpoint;  // e.g. http://localhost:8080
        public string tenantId;  // = organization_id
        public string appId;     // = game_id + environment
        public string deviceId = null; // optional override
        public int flushIntervalSec = 5;
        public int maxBatch = 50;
        public int maxQueueBytes = 512 * 1024;
        public int sessionGapSec = 30 * 60;
        public bool debug = false;
        // NOTE: HMAC signing intentionally omitted on client SDK.
        // Server-side HMAC (via Server SDK) is the only supported way to sign requests.
        // Embedding HMAC secret in client code is unsafe (reverse-engineerable from IL2CPP/Mono assemblies).
    }

    [Serializable]
    public class Event
    {
        public string event_id;
        public string event_name;
        public string tenant_id;
        public string app_id;
        public string user_id;  // optional
        public string device_id;
        public string session_id; // optional
        public long ts_client; // epoch ms
        public string platform = "unity";
        public string app_version;
        public string country;
        public Dictionary<string, object> props;
    }

    public class Pit : MonoBehaviour
    {
        public static Pit Instance { get; private set; }

        private Options _opts;
        private string _deviceId;
        private string _userId = null;
        private string _sessionId = null;
        private long _lastActiveMs = 0;

        private readonly List<Event> _queue = new List<Event>();
        private int _queueBytes = 0;
        private bool _isFlushing = false;

        private string QueuePath => Path.Combine(Application.persistentDataPath, $"pit_queue_{_opts.tenantId}_{_opts.appId}_{_deviceId}.ndjson");
        private string DevKey => $"pit_device_id_{_opts.tenantId}_{_opts.appId}";

        public static void Init(Options options)
        {
            if (Instance != null) return;
            var go = new GameObject("PitClient");
            DontDestroyOnLoad(go);
            Instance = go.AddComponent<Pit>();
            Instance.Configure(options);
        }

        private void Configure(Options opts)
        {
            _opts = opts;
            // device id
            _deviceId = string.IsNullOrEmpty(_opts.deviceId)
                ? (PlayerPrefs.GetString(DevKey, string.Empty))
                : _opts.deviceId;
            if (string.IsNullOrEmpty(_deviceId))
            {
                // Prefer SystemInfo.deviceUniqueIdentifier if not empty
                var duid = SystemInfo.deviceUniqueIdentifier;
                if (!string.IsNullOrEmpty(duid)) _deviceId = Hash("d_", duid);
                else _deviceId = "d_" + Guid.NewGuid().ToString("N");
                PlayerPrefs.SetString(DevKey, _deviceId);
                PlayerPrefs.Save();
            }
            LoadQueue();
            _lastActiveMs = NowMs();
            StartCoroutine(FlushLoop());
            LogDebug($"Pit initialized. deviceId={_deviceId}, queue={_queue.Count}");
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
            var p = props == null ? new Dictionary<string, object>() : new Dictionary<string, object>(props);
            p["amount"] = amount; p["currency"] = currency;
            return Track("revenue", p);
        }

        public static void Flush() { if (Instance != null) Instance.StartCoroutine(Instance.FlushOnce()); }

        private string TrackInternal(string eventName, Dictionary<string, object> props)
        {
            long now = NowMs();
            RollSession(now);
            var e = new Event
            {
                event_id = UuidV7(),
                event_name = eventName,
                tenant_id = _opts.tenantId,
                app_id = _opts.appId,
                user_id = _userId,
                device_id = _deviceId,
                session_id = _sessionId,
                ts_client = now,
                platform = "unity",
                props = props
            };
            int est = EstimateSize(e);
            _queue.Add(e); _queueBytes += est; _lastActiveMs = now;
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

                // NOTE: HMAC signature intentionally NOT added here.
                // Client SDK authenticates via x-api-key only. Adding HMAC would
                // require shipping the secret in client code, which is unsafe.

                yield return req.SendWebRequest();
                if (req.result == UnityWebRequest.Result.Success && req.responseCode >= 200 && req.responseCode < 300)
                {
                    // drop sent slice
                    _queue.RemoveRange(0, n);
                    RecalcQueueBytes();
                    if (_opts.debug) LogDebug($"flushed {n} events ok");
                }
                else
                {
                    if (_opts.debug) LogDebug($"flush failed: {req.responseCode} {req.error}");
                    // keep queue intact; next interval will retry
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
                var lines = File.ReadAllLines(QueuePath);
                foreach (var line in lines)
                {
                    // best-effort: we do not parse back to objects; we just ignore persisted lines
                    // Alternatively, parse minimal fields to requeue. Keep simple for skeleton.
                }
                // Truncate persisted queue after load
                File.WriteAllText(QueuePath, string.Empty);
            }
            catch { /* ignore */ }
        }

        private void SaveQueue()
        {
            try
            {
                var sb = new StringBuilder(_queue.Count * 128);
                foreach (var e in _queue) { sb.AppendLine(ToJson(e)); }
                File.WriteAllText(QueuePath, sb.ToString());
            }
            catch { /* ignore */ }
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
            JField(sb, "event_name", e.event_name);
            JField(sb, "tenant_id", e.tenant_id);
            JField(sb, "app_id", e.app_id);
            if (!string.IsNullOrEmpty(e.user_id)) JField(sb, "user_id", e.user_id);
            JField(sb, "device_id", e.device_id);
            if (!string.IsNullOrEmpty(e.session_id)) JField(sb, "session_id", e.session_id);
            JField(sb, "ts_client", e.ts_client);
            if (!string.IsNullOrEmpty(e.platform)) JField(sb, "platform", e.platform);
            if (!string.IsNullOrEmpty(e.app_version)) JField(sb, "app_version", e.app_version);
            if (!string.IsNullOrEmpty(e.country)) JField(sb, "country", e.country);
            if (e.props != null && e.props.Count > 0) JObject(sb, "props", e.props);
            if (sb[sb.Length - 1] == ',') sb.Length -= 1; // trim last comma
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
        private static void JObject(StringBuilder sb, string k, Dictionary<string, object> map, int depth = 0)
        {
            if (depth > 2) return; // clamp
            sb.Append('"').Append(JEscape(k)).Append('"').Append(':');
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
            sb.Append('}').Append(',');
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
                    JObject(sb, null, m, depth); break; // key null means direct object (only used in nested)
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
            // xxxx-xxxx-7xxx-xxxx-xxxxxxxxxxxx
            return hex.Substring(0, 8) + "-" + hex.Substring(8, 12 - 8) + "-7" + hex.Substring(13, 16 - 13) + "-" + hex.Substring(16, 20 - 16) + "-" + hex.Substring(20, 32 - 20);
        }

        private static void LogDebug(string msg) { Debug.Log("[Pit] " + msg); }

        // ===== Experiments helpers =====
        public static string AssignVariant(string expId, string salt, System.Collections.Generic.List<System.Tuple<string,int>> variants, string key)
        {
            if (variants == null || variants.Count == 0) return "A";
            int sum = 0; foreach (var v in variants) sum += v.Item2 > 0 ? v.Item2 : 1;
            uint h = Hash32(expId+":"+(salt??"")+":"+key);
            int r = (int)(h % (uint)sum);
            int acc = 0; foreach (var v in variants) { acc += v.Item2>0?v.Item2:1; if (r < acc) return v.Item1; }
            return variants[0].Item1;
        }
        private static uint Hash32(string s)
        {
            uint h = 0x811c9dc5;
            foreach (char ch in s) { h ^= (uint)ch; h += (h<<1)+(h<<4)+(h<<7)+(h<<8)+(h<<24); }
            return h;
        }
    }
}
