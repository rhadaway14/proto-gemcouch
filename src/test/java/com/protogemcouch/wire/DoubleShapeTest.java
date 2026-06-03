package com.protogemcouch.wire;

import com.protogemcouch.serialization.ValueDecoding;
import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DoubleShapeTest {

    @Test
    void geode_double_positive_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Double.valueOf(7.25d));

        System.out.println("DOUBLE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("DOUBLE_HEX_END");

        assertArrayEquals(
                hexToBytes("3c401d000000000000"),
                payload
        );
        assertEquals(Double.valueOf(7.25d), ValueDecoding.decodeDoubleValue(payload));
    }

    @Test
    void geode_double_negative_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Double.valueOf(-7.25d));

        System.out.println("DOUBLE_NEGATIVE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("DOUBLE_NEGATIVE_HEX_END");

        assertArrayEquals(
                hexToBytes("3cc01d000000000000"),
                payload
        );
        assertEquals(Double.valueOf(-7.25d), ValueDecoding.decodeDoubleValue(payload));
    }

    @Test
    void geode_double_large_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Double.valueOf(9_876_543.210d));

        System.out.println("DOUBLE_LARGE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("DOUBLE_LARGE_HEX_END");

        assertArrayEquals(
                hexToBytes("3c4162d687e6b851ec"),
                payload
        );
        assertEquals(Double.valueOf(9_876_543.210d), ValueDecoding.decodeDoubleValue(payload));
    }

    @Test
    void geode_double_zero_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Double.valueOf(0.0d));

        System.out.println("DOUBLE_ZERO_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("DOUBLE_ZERO_HEX_END");

        assertArrayEquals(
                hexToBytes("3c0000000000000000"),
                payload
        );
        assertEquals(Double.valueOf(0.0d), ValueDecoding.decodeDoubleValue(payload));
    }

    private static byte[] serializeWithGeode(Double value) throws Exception {
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