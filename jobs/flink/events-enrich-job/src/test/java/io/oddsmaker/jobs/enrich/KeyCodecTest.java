package io.oddsmaker.jobs.enrich;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@DisplayName("KeyCodec length-prefixed composite keys")
class KeyCodecTest {

    @Test
    @DisplayName("join: delimiter-bearing components cannot collide across boundaries")
    void delimiterBearingComponentsCannotCollide() {
        assertNotEquals(KeyCodec.join("g|a", "b"), KeyCodec.join("g", "a|b"));
        assertEquals("1:g1:a1:b", KeyCodec.join("g", "a", "b"));
        assertEquals("0:", KeyCodec.join((String) null));
    }
}
