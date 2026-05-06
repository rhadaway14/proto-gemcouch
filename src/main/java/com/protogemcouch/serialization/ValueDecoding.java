package com.protogemcouch.serialization;

import java.nio.charset.StandardCharsets;

public final class ValueDecoding {

    private static final int GEODE_STRING_CODE = 0x57;
    private static final int GEODE_NULL_CODE = 0x29;

    /*
     * Geode DataSerializer boolean marker:
     *
     *   Boolean.TRUE  -> 35 01
     *   Boolean.FALSE -> 35 00
     */
    private static final int GEODE_BOOLEAN_CODE = 0x35;

    /*
     * Geode DataSerializer short marker observed from ShortShapeTest:
     *
     *   Short.valueOf((short) 7)  -> 38 00 07
     *   Short.valueOf((short) -7) -> 38 ff f9
     *   Short.valueOf((short) 0)  -> 38 00 00
     *   Short.MAX_VALUE           -> 38 7f ff
     *   Short.MIN_VALUE           -> 38 80 00
     */
    private static final int GEODE_SHORT_CODE = 0x38;

    /*
     * Geode DataSerializer integer marker observed from IntegerShapeTest:
     *
     *   Integer.valueOf(7) -> 39 00 00 00 07
     */
    private static final int GEODE_INTEGER_CODE = 0x39;

    /*
     * Geode DataSerializer long marker observed from LongShapeTest:
     *
     *   Long.valueOf(7L)           -> 3a 00 00 00 00 00 00 00 07
     *   Long.valueOf(-7L)          -> 3a ff ff ff ff ff ff ff f9
     *   Long.valueOf(9876543210L)  -> 3a 00 00 00 02 4c b0 16 ea
     */
    private static final int GEODE_LONG_CODE = 0x3a;

    /*
     * Geode DataSerializer float marker observed from FloatShapeTest:
     *
     *   Float.valueOf(7.25f)      -> 3b 40 e8 00 00
     *   Float.valueOf(-7.25f)     -> 3b c0 e8 00 00
     *   Float.valueOf(987654.25f) -> 3b 49 71 20 64
     *   Float.valueOf(0.0f)       -> 3b 00 00 00 00
     */
    private static final int GEODE_FLOAT_CODE = 0x3b;

    /*
     * Geode DataSerializer double marker observed from DoubleShapeTest:
     *
     *   Double.valueOf(7.25d)        -> 3c 40 1d 00 00 00 00 00 00
     *   Double.valueOf(-7.25d)       -> 3c c0 1d 00 00 00 00 00 00
     *   Double.valueOf(9876543.210d) -> 3c 41 62 d6 87 e6 b8 51 ec
     *   Double.valueOf(0.0d)         -> 3c 00 00 00 00 00 00 00 00
     */
    private static final int GEODE_DOUBLE_CODE = 0x3c;

    private ValueDecoding() {
    }

    public static Boolean decodeBooleanValue(byte[] payload) {
        if (payload == null || payload.length != 2) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_BOOLEAN_CODE) {
            return null;
        }

        int value = payload[1] & 0xff;

        if (value == 0x00) {
            return Boolean.FALSE;
        }

        if (value == 0x01) {
            return Boolean.TRUE;
        }

        return null;
    }

    public static Short decodeShortValue(byte[] payload) {
        if (payload == null || payload.length != 3) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_SHORT_CODE) {
            return null;
        }

        int value = ((payload[1] & 0xff) << 8)
                | (payload[2] & 0xff);

        return (short) value;
    }

    public static Integer decodeIntegerValue(byte[] payload) {
        if (payload == null || payload.length != 5) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_INTEGER_CODE) {
            return null;
        }

        return ((payload[1] & 0xff) << 24)
                | ((payload[2] & 0xff) << 16)
                | ((payload[3] & 0xff) << 8)
                | (payload[4] & 0xff);
    }

    public static Long decodeLongValue(byte[] payload) {
        if (payload == null || payload.length != 9) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_LONG_CODE) {
            return null;
        }

        return ((long) (payload[1] & 0xff) << 56)
                | ((long) (payload[2] & 0xff) << 48)
                | ((long) (payload[3] & 0xff) << 40)
                | ((long) (payload[4] & 0xff) << 32)
                | ((long) (payload[5] & 0xff) << 24)
                | ((long) (payload[6] & 0xff) << 16)
                | ((long) (payload[7] & 0xff) << 8)
                | ((long) (payload[8] & 0xff));
    }

    public static Float decodeFloatValue(byte[] payload) {
        if (payload == null || payload.length != 5) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_FLOAT_CODE) {
            return null;
        }

        int bits = ((payload[1] & 0xff) << 24)
                | ((payload[2] & 0xff) << 16)
                | ((payload[3] & 0xff) << 8)
                | (payload[4] & 0xff);

        return Float.intBitsToFloat(bits);
    }

    public static Double decodeDoubleValue(byte[] payload) {
        if (payload == null || payload.length != 9) {
            return null;
        }

        if ((payload[0] & 0xff) != GEODE_DOUBLE_CODE) {
            return null;
        }

        long bits = ((long) (payload[1] & 0xff) << 56)
                | ((long) (payload[2] & 0xff) << 48)
                | ((long) (payload[3] & 0xff) << 40)
                | ((long) (payload[4] & 0xff) << 32)
                | ((long) (payload[5] & 0xff) << 24)
                | ((long) (payload[6] & 0xff) << 16)
                | ((long) (payload[7] & 0xff) << 8)
                | ((long) (payload[8] & 0xff));

        return Double.longBitsToDouble(bits);
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
         * Do not allow typed primitive Geode payloads to fall through into the
         * plain UTF-8 fallback.
         */
        if (decodeBooleanValue(payload) != null) {
            return null;
        }

        if (decodeShortValue(payload) != null) {
            return null;
        }

        if (decodeIntegerValue(payload) != null) {
            return null;
        }

        if (decodeLongValue(payload) != null) {
            return null;
        }

        if (decodeFloatValue(payload) != null) {
            return null;
        }

        if (decodeDoubleValue(payload) != null) {
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
