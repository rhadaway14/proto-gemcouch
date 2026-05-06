package com.protogemcouch.wire;

import com.protogemcouch.serialization.ValueDecoding;
import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ShortShapeTest {

    @Test
    void geode_short_positive_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Short.valueOf((short) 7));

        System.out.println("SHORT_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("SHORT_HEX_END");

        assertArrayEquals(
                hexToBytes("380007"),
                payload
        );
        assertEquals(Short.valueOf((short) 7), ValueDecoding.decodeShortValue(payload));
    }

    @Test
    void geode_short_negative_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Short.valueOf((short) -7));

        System.out.println("SHORT_NEGATIVE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("SHORT_NEGATIVE_HEX_END");

        assertArrayEquals(
                hexToBytes("38fff9"),
                payload
        );
        assertEquals(Short.valueOf((short) -7), ValueDecoding.decodeShortValue(payload));
    }

    @Test
    void geode_short_zero_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Short.valueOf((short) 0));

        System.out.println("SHORT_ZERO_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("SHORT_ZERO_HEX_END");

        assertArrayEquals(
                hexToBytes("380000"),
                payload
        );
        assertEquals(Short.valueOf((short) 0), ValueDecoding.decodeShortValue(payload));
    }

    @Test
    void geode_short_max_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Short.valueOf(Short.MAX_VALUE));

        System.out.println("SHORT_MAX_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("SHORT_MAX_HEX_END");

        assertArrayEquals(
                hexToBytes("387fff"),
                payload
        );
        assertEquals(Short.valueOf(Short.MAX_VALUE), ValueDecoding.decodeShortValue(payload));
    }

    @Test
    void geode_short_min_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Short.valueOf(Short.MIN_VALUE));

        System.out.println("SHORT_MIN_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("SHORT_MIN_HEX_END");

        assertArrayEquals(
                hexToBytes("388000"),
                payload
        );
        assertEquals(Short.valueOf(Short.MIN_VALUE), ValueDecoding.decodeShortValue(payload));
    }

    private static byte[] serializeWithGeode(Short value) throws Exception {
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