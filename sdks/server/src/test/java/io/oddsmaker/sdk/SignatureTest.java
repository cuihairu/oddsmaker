package io.oddsmaker.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignatureTest {

    @Test
    void hmacSha256KnownVector() {
        // RFC 4231 风格公开向量：HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog")
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            Signature.hmacSha256Hex("key", "The quick brown fox jumps over the lazy dog"));
    }
}
