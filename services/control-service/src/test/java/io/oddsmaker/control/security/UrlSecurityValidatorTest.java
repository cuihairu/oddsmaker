package io.oddsmaker.control.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlSecurityValidatorTest {

    @Test
    @DisplayName("公网 http(s) 放行；私网/环回/链路本地拒绝")
    void rejectsPrivateTargets() {
        assertDoesNotThrow(() -> UrlSecurityValidator.validateHttpUrl(
            "https://hooks.example.com/h", "url", false));
        assertDoesNotThrow(() -> UrlSecurityValidator.validateHttpUrl(
            "http://example.com/h", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://127.0.0.1:1/h", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://10.0.0.5/h", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://192.168.1.1/h", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://169.254.1.1/h", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "ftp://example.com", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "file:///etc/passwd", "url", false));
    }

    @Test
    @DisplayName("IPv6 环回/唯一本地/链路本地拒绝（Java isSiteLocalAddress 对 fc00::/7 返回 false，须按字节判定）")
    void rejectsIpv6PrivateTargets() {
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://[::1]:8080/h", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://[fd00::1]:8080/h", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://[fc00::1]:8080/h", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://[fe80::1]:8080/h", "url", false));
        // 公网 IPv6 放行
        assertDoesNotThrow(() -> UrlSecurityValidator.validateHttpUrl(
            "http://[2001:db8::1]:8080/h", "url", false));
    }

    @Test
    @DisplayName("CGNAT 100.64.0.0/10 全段拒绝（100.64–100.127，startsWith(100.64.) 只覆盖 1/64）")
    void rejectsCgnatRange() {
        // 100.64.0.0/10 边界内外
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://100.64.0.1/h", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://100.65.0.1/h", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://100.100.0.1/h", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://100.127.255.255/h", "url", false));
        // 100.128.0.0/16 为公网（超出 CGNAT /10），放行
        assertDoesNotThrow(() -> UrlSecurityValidator.validateHttpUrl(
            "http://100.128.0.1/h", "url", false));
    }

    @Test
    @DisplayName("IPv4 私网补充段：172.16/12、IPv6 公网、非数字主机名")
    void rejectsIpv4AdditionalPrivateSegments() {
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://172.16.0.1/h", "url", false));
        assertThrows(IllegalArgumentException.class, () -> UrlSecurityValidator.validateHttpUrl(
            "http://172.31.255.255/h", "url", false));
        // 172.32.0.0/16 公网
        assertDoesNotThrow(() -> UrlSecurityValidator.validateHttpUrl(
            "http://172.32.0.1/h", "url", false));
    }

    @Test
    @DisplayName("isPrivateOrLoopback 字节判定直测（IPv6 ULA/CGNAT 全段、边界）")
    void isPrivateOrLoopbackBytes() throws Exception {
        // IPv6 唯一本地 fc00::/7 全段
        assertTrue(UrlSecurityValidator.isPrivateOrLoopback("fd00::1"));
        assertTrue(UrlSecurityValidator.isPrivateOrLoopback("fc00::1"));
        assertTrue(UrlSecurityValidator.isPrivateOrLoopback("fdff:ffff::1"));
        assertFalse(UrlSecurityValidator.isPrivateOrLoopback("2001:db8::1"));
        // CGNAT 边界
        assertTrue(UrlSecurityValidator.isPrivateOrLoopback("100.64.0.0"));
        assertTrue(UrlSecurityValidator.isPrivateOrLoopback("100.127.255.255"));
        assertFalse(UrlSecurityValidator.isPrivateOrLoopback("100.128.0.0"));
        assertFalse(UrlSecurityValidator.isPrivateOrLoopback("100.63.255.255"));
        // IPv4 私网
        assertTrue(UrlSecurityValidator.isPrivateOrLoopback("10.0.0.1"));
        assertTrue(UrlSecurityValidator.isPrivateOrLoopback("172.16.0.1"));
        assertTrue(UrlSecurityValidator.isPrivateOrLoopback("192.168.1.1"));
        assertFalse(UrlSecurityValidator.isPrivateOrLoopback("8.8.8.8"));
    }

    @Test
    @DisplayName("allowPrivate=true 时内网放行")
    void allowsPrivateWhenEnabled() {
        assertDoesNotThrow(() -> UrlSecurityValidator.validateHttpUrl(
            "http://localhost:8080/h", "url", true));
        assertDoesNotThrow(() -> UrlSecurityValidator.validateHttpUrl(
            "http://[fd00::1]:8080/h", "url", true));
        assertDoesNotThrow(() -> UrlSecurityValidator.validateHttpUrl(
            "http://100.65.0.1/h", "url", true));
    }
}
