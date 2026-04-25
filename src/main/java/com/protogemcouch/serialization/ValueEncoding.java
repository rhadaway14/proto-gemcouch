package com.protogemcouch.serialization;

import java.nio.charset.StandardCharsets;

public final class ValueEncoding {

    private ValueEncoding() {
    }

    public static byte[] encodeStringLikeValue(String value) {
        if (value == null) {
            return new byte[0];
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }
}