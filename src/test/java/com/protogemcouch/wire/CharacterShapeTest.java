package com.protogemcouch.wire;

import com.protogemcouch.serialization.ValueDecoding;
import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterShapeTest {

    @Test
    void geode_character_a_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Character.valueOf('A'));

        System.out.println("CHAR_A_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("CHAR_A_HEX_END");

        assertArrayEquals(
                hexToBytes("360041"),
                payload
        );
        assertEquals(Character.valueOf('A'), ValueDecoding.decodeCharacterValue(payload));
    }

    @Test
    void geode_character_z_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Character.valueOf('Z'));

        System.out.println("CHAR_Z_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("CHAR_Z_HEX_END");

        assertArrayEquals(
                hexToBytes("36005a"),
                payload
        );
        assertEquals(Character.valueOf('Z'), ValueDecoding.decodeCharacterValue(payload));
    }

    @Test
    void geode_character_zero_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Character.valueOf('0'));

        System.out.println("CHAR_ZERO_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("CHAR_ZERO_HEX_END");

        assertArrayEquals(
                hexToBytes("360030"),
                payload
        );
        assertEquals(Character.valueOf('0'), ValueDecoding.decodeCharacterValue(payload));
    }

    @Test
    void geode_character_space_shape_should_match_expected_bytes_and_decode() throws Exception {
        byte[] payload = serializeWithGeode(Character.valueOf(' '));

        System.out.println("CHAR_SPACE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("CHAR_SPACE_HEX_END");

        assertArrayEquals(
                hexToBytes("360020"),
                payload
        );
        assertEquals(Character.valueOf(' '), ValueDecoding.decodeCharacterValue(payload));
    }

    private static byte[] serializeWithGeode(Character value) throws Exception {
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