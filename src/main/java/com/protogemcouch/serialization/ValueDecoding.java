package com.protogemcouch.serialization;

import java.nio.charset.StandardCharsets;

public final class ValueDecoding {

    private static final int GEODE_STRING_CODE = 0x57;
    private static final int GEODE_NULL_CODE = 0x29;

    private ValueDecoding() {
    }

    public static String decodeStringLikeValue(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }

        /*
         * Geode NULL / absent-ish marker.
         *
         * Do not let this fall through to raw UTF-8, otherwise it becomes ")"
         * and gets stored as a real document value.
         */
        if (payload.length == 1 && (payload[0] & 0xff) == GEODE_NULL_CODE) {
            return null;
        }

        /*
         * Preferred/current Geode string shape:
         *
         *   0x57
         *   2-byte unsigned UTF-8 length
         *   UTF-8 bytes
         */
        if ((payload[0] & 0xff) == GEODE_STRING_CODE) {
            String decoded = decodeLengthPrefixedGeodeString(payload);
            if (decoded != null) {
                return decoded;
            }

            /*
             * Legacy/unit-test fixture shape:
             *
             *   0x57
             *   UTF-8 bytes
             *
             * Several existing PutAllHandler tests use this shape. Preserve support
             * for it, but only after the proper length-prefixed parse fails.
             */
            if (payload.length > 1) {
                String markerStripped = new String(
                        payload,
                        1,
                        payload.length - 1,
                        StandardCharsets.UTF_8
                )
                        .replace("\u0000", "")
                        .trim();

                return markerStripped.isBlank() ? null : markerStripped;
            }

            return null;
        }

        /*
         * Plain UTF-8 fallback for older/local fixtures and non-Geode payloads.
         *
         * Keep this conservative. Single-byte control/token-like values should not
         * become persisted strings.
         */
        if (payload.length == 1 && isLikelyGeodeToken(payload[0] & 0xff)) {
            return null;
        }

        String text = new String(payload, StandardCharsets.UTF_8)
                .replace("\u0000", "")
                .trim();

        return text.isBlank() ? null : text;
    }

    private static String decodeLengthPrefixedGeodeString(byte[] payload) {
        if (payload.length < 3) {
            return null;
        }

        int length = ((payload[1] & 0xff) << 8)
                | (payload[2] & 0xff);

        int start = 3;
        int end = start + length;

        if (length < 0 || end > payload.length) {
            return null;
        }

        String value = new String(payload, start, length, StandardCharsets.UTF_8);
        return value.isBlank() ? null : value;
    }

    private static boolean isLikelyGeodeToken(int value) {
        /*
         * Geode/DataSerializable token space. For this validated string-value path,
         * single-byte values in this range should not become user strings.
         */
        return value >= 0x00 && value <= 0x7f;
    }
}