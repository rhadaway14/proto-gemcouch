package com.protogemcouch.util;

import java.nio.charset.StandardCharsets;

public final class ByteUtils {

    private ByteUtils() {
    }

    public static String bytesToString(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
                .replace("\u0000", "")
                .trim();
    }

    public static int bytesToInt(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return 0;
        }
        return ((bytes[0] & 0xFF) << 24)
                | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
    }

    public static byte[] hex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
        }
        return out;
    }
}