package io.oddsmaker.jobs.enrich;

/** Composite state key encoding that cannot collide when components contain delimiters. */
public final class KeyCodec {
    private KeyCodec() {
    }

    public static String join(String... parts) {
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part;
            key.append(value.length()).append(':').append(value);
        }
        return key.toString();
    }
}
