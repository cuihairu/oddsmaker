package io.oddsmaker.control.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/** Shared URL validation for outbound HTTP endpoints. */
public final class UrlSecurityValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]");

    private UrlSecurityValidator() {}

    public static void validateHttpUrl(String raw, String label, boolean allowPrivateNetworks) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + " must be a valid URL", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException(label + " must start with http:// or https://");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(label + " must include a host");
        }
        if (!allowPrivateNetworks && isPrivateOrLoopback(host)) {
            throw new IllegalArgumentException(label + " must not target private/loopback/link-local networks");
        }
    }

    /**
     * 私网/环回/链路本地判定。
     *
     * <p>不依赖 {@link InetAddress#isSiteLocalAddress()}（Java 对 IPv6 唯一本地 fc00::/7 恒返回
     * false），改为按 RFC 4291/4193/6598 字节判定：IPv4 10/8、172.16/12、192.168/16（site-local）、
     * 127/8（loopback）、169.254/16（link-local）、100.64.0.0/10（CGNAT）；IPv6 ::1（loopback）、
     * fc00::/7（unique-local）、fe80::/10（link-local）。
     *
     * <p>注意：判定时经 {@link InetAddress#getByName} 解析一次，实际发起请求时调用方（RestTemplate）
     * 会再解析一次——存在 DNS rebinding TOCTOU 残余（本类只校验 URL 形态/地址族，无法持锁到实际
     * 连接）。本层为深度防御，配置由受信运维写入，非面向不可信 DNS 的边界。
     */
    public static boolean isPrivateOrLoopback(String host) {
        String normalized = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (LOCAL_HOSTS.contains(normalized)) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            return isPrivateOrLoopback(addr);
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** 按解析后的地址字节判定（与域名无关，直接面向 InetAddress）。 */
    static boolean isPrivateOrLoopback(InetAddress addr) {
        if (addr == null) {
            return false;
        }
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            // 10.0.0.0/8
            if (b0 == 10) {
                return true;
            }
            // 172.16.0.0/12
            if (b0 == 172 && (b1 & 0xF0) == 16) {
                return true;
            }
            // 192.168.0.0/16
            if (b0 == 192 && b1 == 168) {
                return true;
            }
            // 169.254.0.0/16（link-local，isLinkLocalAddress 应已捕获，双保险）
            if (b0 == 169 && b1 == 254) {
                return true;
            }
            // 100.64.0.0/10（CGNAT，RFC 6598）
            if (b0 == 100 && (b1 & 0xC0) == 64) {
                return true;
            }
            // 127.0.0.0/8（loopback，isLoopbackAddress 应已捕获，双保险）
            return b0 == 127;
        }
        if (b.length == 16) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            // fc00::/7（unique-local，IPv6）
            if ((b0 & 0xFE) == 0xFC) {
                return true;
            }
            // fe80::/10（link-local，isLinkLocalAddress 应已捕获，双保险）
            if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
                return true;
            }
            // fec0::/10（deprecated site-local）
            return b0 == 0xFE && (b1 & 0xC0) == 0xC0;
        }
        return false;
    }
}
