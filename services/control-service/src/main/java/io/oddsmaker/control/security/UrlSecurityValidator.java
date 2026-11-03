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

    public static boolean isPrivateOrLoopback(String host) {
        String normalized = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (LOCAL_HOSTS.contains(normalized)) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isSiteLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()
                || addr.getHostAddress().startsWith("169.254.")
                || addr.getHostAddress().startsWith("100.64.");
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
