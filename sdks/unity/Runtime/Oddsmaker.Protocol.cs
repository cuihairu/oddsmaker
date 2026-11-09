// Oddsmaker Unity SDK (Runtime) - JSON 协议层（LAY-015 自 Oddsmaker.cs 拆出，partial class）
// 事件 NDJSON 序列化/还原、/v1/batch 2xx 逐事件拒绝解析、请求体 gzip。

using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Text;
using UnityEngine;

namespace Oddsmaker
{
    public partial class Oddsmaker
    {

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

    }
}
