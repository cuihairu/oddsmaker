package io.oddsmaker.control.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    @DisplayName("allowPrivate=true 时内网放行")
    void allowsPrivateWhenEnabled() {
        assertDoesNotThrow(() -> UrlSecurityValidator.validateHttpUrl(
            "http://localhost:8080/h", "url", true));
    }
}
