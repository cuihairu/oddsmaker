package io.pit.control.api;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache for hot ApiKey lookup (used by Gateway).
 * Backed by Control Service DB; refreshed on write.
 *
 * @deprecated Use ControlService + ApiKeyRepo directly. Kept for compatibility
 *             with Gateway's hot-path caching during the transition.
 */
@Deprecated
public class MemoryStore {
    private final Map<String, Key> keys = new ConcurrentHashMap<>(); // apiKey -> Key

    public static class Key {
        public String apiKey;
        public String secret;
        public String orgId;
        public String gameId;
        public String environmentId;
        public String name;
        public int rpm = 600;
        public int ipRpm = 300;
        public List<String> propsAllowlist = List.of();
        public String piiEmail;
        public String piiPhone;
        public String piiIp;
        public List<String> denyKeys;
        public List<String> maskKeys;
    }

    public Key createKey(String orgId, String gameId, String environmentId, String name) {
        Key k = new Key();
        k.apiKey = gen("pk_");
        k.secret = gen("sk_");
        k.orgId = orgId; k.gameId = gameId; k.environmentId = environmentId; k.name = name;
        keys.put(k.apiKey, k);
        return k;
    }
    public Key getKey(String apiKey) { return keys.get(apiKey); }

    public Key updatePolicy(String apiKey, Integer rpm, Integer ipRpm, List<String> allowlist,
                            String piiEmail, String piiPhone, String piiIp,
                            List<String> denyKeys, List<String> maskKeys) {
        Key k = keys.get(apiKey);
        if (k == null) return null;
        if (rpm != null) k.rpm = rpm;
        if (ipRpm != null) k.ipRpm = ipRpm;
        if (allowlist != null) k.propsAllowlist = allowlist;
        if (piiEmail != null) k.piiEmail = piiEmail;
        if (piiPhone != null) k.piiPhone = piiPhone;
        if (piiIp != null) k.piiIp = piiIp;
        if (denyKeys != null) k.denyKeys = denyKeys;
        if (maskKeys != null) k.maskKeys = maskKeys;
        return k;
    }

    public Collection<Key> listKeys() { return keys.values(); }

    private static String gen(String prefix) {
        byte[] b = new byte[12]; new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(prefix);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
