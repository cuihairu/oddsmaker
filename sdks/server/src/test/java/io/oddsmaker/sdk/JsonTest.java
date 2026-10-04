package io.oddsmaker.sdk;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {

    @Test
    void escapesStringsAndControlChars() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quote", "say \"hi\"");
        m.put("backslash", "a\\b");
        m.put("newline", "l1\nl2");
        m.put("tab", "a\tb");
        m.put("ctrl", "a\u0001b");
        assertEquals("{\"quote\":\"say \\\"hi\\\"\",\"backslash\":\"a\\\\b\","
                + "\"newline\":\"l1\\nl2\",\"tab\":\"a\\tb\",\"ctrl\":\"a\\u0001b\"}",
            Json.write(m));
    }

    @Test
    void nestsMapsListsAndScalars() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("amount", 9.99);
        inner.put("qty", 3);
        inner.put("gift", true);
        inner.put("note", null);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("order", inner);
        outer.put("tags", List.of("a", "b"));
        outer.put("missing", null);
        assertEquals("{\"order\":{\"amount\":9.99,\"qty\":3,\"gift\":true,\"note\":null},"
                + "\"tags\":[\"a\",\"b\"],\"missing\":null}", Json.write(outer));
    }

    @Test
    void fallbackToStringForUnknownTypes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("when", new Object() {
            @Override public String toString() { return "2026-10-04"; }
        });
        assertEquals("{\"when\":\"2026-10-04\"}", Json.write(m));
    }
}
