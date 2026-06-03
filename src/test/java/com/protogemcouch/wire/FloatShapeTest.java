package com.protogemcouch.wire;

import com.protogemcouch.serialization.ValueDecoding;
import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FloatShapeTest {

    @Test
    void geode_float_positive_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Float.valueOf(7.25f));

        System.out.println("FLOAT_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("FLOAT_HEX_END");

        assertArrayEquals(
                hexToBytes("3b40e80000"),
                payload
        );
        assertEquals(Float.valueOf(7.25f), ValueDecoding.decodeFloatValue(payload));
    }

    @Test
    void geode_float_negative_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Float.valueOf(-7.25f));

        System.out.println("FLOAT_NEGATIVE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("FLOAT_NEGATIVE_HEX_END");

        assertArrayEquals(
                hexToBytes("3bc0e80000"),
                payload
        );
        assertEquals(Float.valueOf(-7.25f), ValueDecoding.decodeFloatValue(payload));
    }

    @Test
    void geode_float_large_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Float.valueOf(987_654.25f));

        System.out.println("FLOAT_LARGE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("FLOAT_LARGE_HEX_END");

        assertArrayEquals(
                hexToBytes("3b49712064"),
                payload
        );
        assertEquals(Float.valueOf(987_654.25f), ValueDecoding.decodeFloatValue(payload));
    }

    @Test
    void geode_float_zero_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Float.valueOf(0.0f));

        System.out.println("FLOAT_ZERO_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("FLOAT_ZERO_HEX_END");

        assertArrayEquals(
                hexToBytes("3b00000000"),
                payload
        );
        assertEquals(Float.valueOf(0.0f), ValueDecoding.decodeFloatValue(payload));
    }

    private static byte[] serializeWithGeode(Float value) throws Exception {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();

        try (DataOutputStream dataOut = new DataOutputStream(byteOut)) {
            DataSerializer.writeObject(value, dataOut);
        }

        return byteOut.toByteArray();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder out = new StringBuilder();

        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xff));
        }

        return out.toString();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }

        byte[] bytes = new byte[hex.length() / 2];

        for (int i = 0; i < hex.length(); i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }

        return bytes;
    }
}