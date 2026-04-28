package com.protogemcouch.serialization;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class ValueEncoding {

    private static final byte GEODE_STRING_CODE = 0x57;

    private ValueEncoding() {
    }

    public static byte[] encodeStringLikeValue(String value) {
        if (value == null) {
            return new byte[0];
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeGeodeStringValue(String value) {
        if (value == null) {
            return new byte[0];
        }

        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);

        if (utf8.length > 0xFFFF) {
            throw new IllegalArgumentException("Validated v1 path only supports string values up to 65535 bytes");
        }

        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + utf8.length);
        buf.put(GEODE_STRING_CODE);
        buf.putShort((short) utf8.length);
        buf.put(utf8);
        return buf.array();
    }

    public static String decodeGeodeStringValue(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }

        if (payload[0] != GEODE_STRING_CODE) {
            return null;
        }

        if (payload.length < 3) {
            return null;
        }

        int declaredLength = ((payload[1] & 0xff) << 8) | (payload[2] & 0xff);
        int availableLength = payload.length - 3;

        if (declaredLength < 0 || declaredLength > availableLength) {
            return null;
        }

        return new String(payload, 3, declaredLength, StandardCharsets.UTF_8);
    }

    public static boolean isGeodeStringValue(byte[] payload) {
        return payload != null
                && payload.length >= 3
                && payload[0] == GEODE_STRING_CODE;
    }
}