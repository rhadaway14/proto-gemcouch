package com.protogemcouch.wire;

import com.protogemcouch.serialization.ValueDecoding;
import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LongShapeTest {

    @Test
    void geode_long_positive_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Long.valueOf(7L));

        System.out.println("LONG_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("LONG_HEX_END");

        assertArrayEquals(
                hexToBytes("3a0000000000000007"),
                payload
        );
        assertEquals(Long.valueOf(7L), ValueDecoding.decodeLongValue(payload));
    }

    @Test
    void geode_long_negative_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Long.valueOf(-7L));

        System.out.println("LONG_NEGATIVE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("LONG_NEGATIVE_HEX_END");

        assertArrayEquals(
                hexToBytes("3afffffffffffffff9"),
                payload
        );
        assertEquals(Long.valueOf(-7L), ValueDecoding.decodeLongValue(payload));
    }

    @Test
    void geode_long_large_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Long.valueOf(9_876_543_210L));

        System.out.println("LONG_LARGE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("LONG_LARGE_HEX_END");

        assertArrayEquals(
                hexToBytes("3a000000024cb016ea"),
                payload
        );
        assertEquals(Long.valueOf(9_876_543_210L), ValueDecoding.decodeLongValue(payload));
    }

    private static byte[] serializeWithGeode(Long value) throws Exception {
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