package io.oddsmaker.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PropsPolicy {
    private final Set<String> allowlist;
    private final int maxEventBytes;
    private final int maxRequestBytes;
    private final ObjectMapper om;

    public PropsPolicy(Environment env, ObjectMapper om) {
        this.om = om;
        List<String> list = Binder.get(env).bind("oddsmaker.props.allowlist", List.class).orElse(Collections.emptyList());
        this.allowlist = new HashSet<>();
        for (Object o : list) this.allowlist.add(String.valueOf(o));
        this.maxEventBytes = Binder.get(env).bind("oddsmaker.event.maxBytes", Integer.class).orElse(65536);
        this.maxRequestBytes = Binder.get(env).bind("oddsmaker.request.maxBytes", Integer.class).orElse(1048576);
    }

    public Map<String, Object> filter(Map<String, Object> props) {
        if (props == null || allowlist.isEmpty()) return props;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            if (allowlist.contains(e.getKey())) out.put(e.getKey(), sanitize(e.getValue(), 0));
        }
        return out;
    }

    public Map<String, Object> filterWithAllowlist(Map<String, Object> props, List<String> allow) {
        if (props == null || allow == null || allow.isEmpty()) return filter(props);
        Set<String> al = new HashSet<>(allow);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            if (al.contains(e.getKey())) out.put(e.getKey(), sanitize(e.getValue(), 0));
        }
        return out;
    }

    private Object sanitize(Object v, int depth) {
        if (v == null) return null;
        if (depth >= 3) return null; // clamp depth
        if (v instanceof Number || v instanceof Boolean || v instanceof String) return v;
        if (v instanceof Map) {
            Map<?,?> m = (Map<?,?>) v;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?,?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                Object sv = sanitize(e.getValue(), depth+1);
                if (sv != null) out.put(String.valueOf(e.getKey()), sv);
            }
            return out;
        }
        if (v instanceof List) {
            List<?> list = (List<?>) v;
            List<Object> out = new ArrayList<>();
            for (Object it : list) {
                Object sv = sanitize(it, depth+1);
                if (sv != null) out.add(sv);
                if (out.size() >= 50) break; // clamp array length
            }
            return out;
        }
        return String.valueOf(v);
    }

    public boolean exceedsEventLimit(Object event) {
        try {
            byte[] b = om.writeValueAsBytes(event);
            return b.length > maxEventBytes;
        } catch (Exception e) { return false; }
    }

    public boolean exceedsRequestLimit(byte[] raw) { return raw != null && raw.length > maxRequestBytes; }
}
