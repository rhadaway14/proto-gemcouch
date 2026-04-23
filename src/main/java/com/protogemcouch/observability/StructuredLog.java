package com.protogemcouch.observability;

public final class StructuredLog {

    private StructuredLog() {
    }

    public static String event(String event, Object... kvPairs) {
        StringBuilder sb = new StringBuilder();
        sb.append("event=").append(event);

        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            String key = String.valueOf(kvPairs[i]);
            Object value = kvPairs[i + 1];
            sb.append(' ')
                    .append(key)
                    .append('=')
                    .append(escape(value));
        }

        return sb.toString();
    }

    private static String escape(Object value) {
        if (value == null) {
            return "null";
        }

        String s = String.valueOf(value);
        if (s.isBlank()) {
            return "\"\"";
        }

        boolean needsQuotes = s.contains(" ") || s.contains("=") || s.contains("\"");
        if (!needsQuotes) {
            return s;
        }

        return "\"" + s.replace("\"", "\\\"") + "\"";
    }
}