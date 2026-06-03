package com.protogemcouch.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class ByteUtils {

    private ByteUtils() {
    }

    public static String bytesToString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    }

    public static int bytesToInt(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return 0;
        }

        if (bytes.length >= 4) {
            return ByteBuffer.wrap(bytes).getInt();
        }

        byte[] padded = new byte[4];
        System.arraycopy(bytes, 0, padded, 4 - bytes.length, bytes.length);
        return ByteBuffer.wrap(padded).getInt();
    }

    public static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    public static byte[] hex(String hex) {
        if (hex == null) {
            return new byte[0];
        }

        String normalized = hex.replaceAll("\\s+", "");
        if ((normalized.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }

        byte[] out = new byte[normalized.length() / 2];
        for (int i = 0; i < normalized.length(); i += 2) {
            int hi = Character.digit(normalized.charAt(i), 16);
            int lo = Character.digit(normalized.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex character in: " + hex);
            }
            out[i / 2] = (byte) ((hi << 4) + lo);
        }
        return out;
    }
}