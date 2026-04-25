package com.protogemcouch.serialization;

import java.nio.charset.StandardCharsets;

public final class ValueDecoding {

    private ValueDecoding() {
    }

    public static String decodeStringLikeValue(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }

        String direct = clean(new String(payload, StandardCharsets.UTF_8));
        if (looksReasonable(direct)) {
            return direct;
        }

        if (payload.length > 1) {
            String skipOne = clean(new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8));
            if (looksReasonable(skipOne)) {
                return skipOne;
            }
        }

        if (payload.length > 2) {
            String skipTwo = clean(new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8));
            if (looksReasonable(skipTwo)) {
                return skipTwo;
            }
        }

        return null;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\u0000", "").trim();
    }

    private static boolean looksReasonable(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        int printable = 0;
        for (char c : value.toCharArray()) {
            if (!Character.isISOControl(c) || Character.isWhitespace(c)) {
                printable++;
            }
        }

        return printable >= Math.max(1, value.length() / 2);
    }
}