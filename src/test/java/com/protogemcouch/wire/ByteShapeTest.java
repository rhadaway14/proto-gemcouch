package com.protogemcouch.wire;

import com.protogemcouch.serialization.ValueDecoding;
import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ByteShapeTest {

    @Test
    void geode_byte_zero_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Byte.valueOf((byte) 0));

        System.out.println("BYTE_ZERO_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("BYTE_ZERO_HEX_END");

        assertArrayEquals(
                hexToBytes("3700"),
                payload
        );
        assertEquals(Byte.valueOf((byte) 0), ValueDecoding.decodeByteValue(payload));
    }

    @Test
    void geode_byte_positive_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Byte.valueOf((byte) 7));

        System.out.println("BYTE_POSITIVE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("BYTE_POSITIVE_HEX_END");

        assertArrayEquals(
                hexToBytes("3707"),
                payload
        );
        assertEquals(Byte.valueOf((byte) 7), ValueDecoding.decodeByteValue(payload));
    }

    @Test
    void geode_byte_negative_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Byte.valueOf((byte) -7));

        System.out.println("BYTE_NEGATIVE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("BYTE_NEGATIVE_HEX_END");

        assertArrayEquals(
                hexToBytes("37f9"),
                payload
        );
        assertEquals(Byte.valueOf((byte) -7), ValueDecoding.decodeByteValue(payload));
    }

    @Test
    void geode_byte_max_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Byte.valueOf(Byte.MAX_VALUE));

        System.out.println("BYTE_MAX_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("BYTE_MAX_HEX_END");

        assertArrayEquals(
                hexToBytes("377f"),
                payload
        );
        assertEquals(Byte.valueOf(Byte.MAX_VALUE), ValueDecoding.decodeByteValue(payload));
    }

    @Test
    void geode_byte_min_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Byte.valueOf(Byte.MIN_VALUE));

        System.out.println("BYTE_MIN_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("BYTE_MIN_HEX_END");

        assertArrayEquals(
                hexToBytes("3780"),
                payload
        );
        assertEquals(Byte.valueOf(Byte.MIN_VALUE), ValueDecoding.decodeByteValue(payload));
    }

    private static byte[] serializeWithGeode(Byte value) throws Exception {
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