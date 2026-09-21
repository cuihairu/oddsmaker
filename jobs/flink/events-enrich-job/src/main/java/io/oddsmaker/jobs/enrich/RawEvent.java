package io.oddsmaker.jobs.enrich;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * 事件明文 POJO（对应 schema/avro/oddsmaker-event.avsc，experiments map 字段未承接）。
 * 不能把 Avro GenericRecord 直接交给 Flink 算子链：Flink 视其为泛型类型，
 * 链式算子间复制走 Kryo，Kryo 读不动 Avro Schema$Field 的不可变集合
 * （UnsupportedOperationException: UnmodifiableCollection.add）。
 * 反序列化后立即转成本 POJO，让 Flink 用自身 PojoSerializer 完成序列化与 copy。
 */
public class RawEvent {
    public String event_id;
    public String game_id;
    public String environment;
    public String event_type;
    public String event_name;
    public String user_id;
    public String device_id;
    public String player_id;
    public String character_id;
    public String session_id;
    public Long ts_client;   // micros
    public Long ts_server;   // micros
    public String platform;
    public String app_version;
    public String sdk_version;
    public String country;
    public String client_ip;
    public String user_agent;
    public String server_id;
    public String guild_id;
    public String match_id;
    public String level_id;
    public String game_mode;
    public String difficulty;
    public String progression_path;
    public String order_id;
    public String product_id;
    public Double revenue_amount;
    public String revenue_currency;
    public String receipt_hash;
    public String virtual_currency;
    public Double virtual_amount;
    public String item_id;
    public String resource_id;
    public Double resource_amount;
    public String flow_type;
    public String operation_id;
    public String operation_type;
    public String ad_network;
    public String ad_placement;
    public String ad_format;
    public String ad_impression_id;
    public String risk_context;
    public String device_fingerprint;
    public String client_integrity;
    public String props_json;
    public java.util.Map<String, String> experiments;

    public RawEvent() {}

    static RawEvent from(GenericRecord r) {
        RawEvent e = new RawEvent();
        e.event_id = str(r, "event_id");
        e.game_id = str(r, "game_id");
        e.environment = str(r, "environment");
        e.event_type = str(r, "event_type");
        e.event_name = str(r, "event_name");
        e.user_id = str(r, "user_id");
        e.device_id = str(r, "device_id");
        e.player_id = str(r, "player_id");
        e.character_id = str(r, "character_id");
        e.session_id = str(r, "session_id");
        e.ts_client = lng(r, "ts_client");
        e.ts_server = lng(r, "ts_server");
        e.platform = str(r, "platform");
        e.app_version = str(r, "app_version");
        e.sdk_version = str(r, "sdk_version");
        e.country = str(r, "country");
        e.client_ip = str(r, "client_ip");
        e.user_agent = str(r, "user_agent");
        e.server_id = str(r, "server_id");
        e.guild_id = str(r, "guild_id");
        e.match_id = str(r, "match_id");
        e.level_id = str(r, "level_id");
        e.game_mode = str(r, "game_mode");
        e.difficulty = str(r, "difficulty");
        e.progression_path = str(r, "progression_path");
        e.order_id = str(r, "order_id");
        e.product_id = str(r, "product_id");
        e.revenue_amount = dbl(r, "revenue_amount");
        e.revenue_currency = str(r, "revenue_currency");
        e.receipt_hash = str(r, "receipt_hash");
        e.virtual_currency = str(r, "virtual_currency");
        e.virtual_amount = dbl(r, "virtual_amount");
        e.item_id = str(r, "item_id");
        e.resource_id = str(r, "resource_id");
        e.resource_amount = dbl(r, "resource_amount");
        e.flow_type = str(r, "flow_type");
        e.operation_id = str(r, "operation_id");
        e.operation_type = str(r, "operation_type");
        e.ad_network = str(r, "ad_network");
        e.ad_placement = str(r, "ad_placement");
        e.ad_format = str(r, "ad_format");
        e.ad_impression_id = str(r, "ad_impression_id");
        e.risk_context = str(r, "risk_context");
        e.device_fingerprint = str(r, "device_fingerprint");
        e.client_integrity = str(r, "client_integrity");
        e.props_json = str(r, "props_json");
        e.experiments = mp(r, "experiments");
        return e;
    }

    /** Avro map 字段解析（空 map 视为 null，序列化体积友好） */
    static java.util.Map<String, String> mp(GenericRecord r, String field) {
        Object v = field(r, field);
        if (!(v instanceof java.util.Map<?, ?> m) || m.isEmpty()) {
            return null;
        }
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<?, ?> en : m.entrySet()) {
            if (en.getKey() != null && en.getValue() != null) {
                out.put(en.getKey().toString(), en.getValue().toString());
            }
        }
        return out;
    }

    static String toDlqJson(RawEvent e, String reason) {
        String id = e == null || e.event_id == null ? "" : e.event_id;
        return "{\"event_id\":\"" + id + "\",\"reason\":\"" + reason + "\"}";
    }

    // Avro string 解码为 CharSequence(Utf8)，统一转 String
    private static String str(GenericRecord r, String name) {
        Object v = field(r, name);
        return v == null ? null : v.toString();
    }

    private static Long lng(GenericRecord r, String name) {
        Object v = field(r, name);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException ex) { return null; }
    }

    private static Double dbl(GenericRecord r, String name) {
        Object v = field(r, name);
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException ex) { return null; }
    }

    private static Object field(GenericRecord r, String name) {
        // GenericRecord.getSchema() 契约恒非 null（Avro 记录构造时必携带 schema），防御壳等价删除
        return r.getSchema().getField(name) == null ? null : r.get(name);
    }
}
