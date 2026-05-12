package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class HashMapStringStringShapeTest {

    @Test
    void hash_map_string_string_empty_shape_is_stable() throws Exception {
        HashMap<String, String> value = new HashMap<>();

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_STRING_EMPTY_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x43,
                        0x00
                },
                actual
        );
    }

    @Test
    void linked_hash_map_string_string_one_shape_is_stable() throws Exception {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("one", "value-1");

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_STRING_ONE_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000017400036f6e6574000776616c75652d317800"),
                actual
        );
    }

    @Test
    void linked_hash_map_string_string_three_shape_is_stable() throws Exception {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("one", "value-1");
        value.put("two", "value-2");
        value.put("three", "value-3");

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_STRING_THREE_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400036f6e6574000776616c75652d3174000374776f74000776616c75652d32740005746872656574000776616c75652d337800"),
                actual
        );
    }

    @Test
    void linked_hash_map_string_string_mixed_shape_is_stable() throws Exception {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("", "");
        value.put("A", "alpha");
        value.put("hello", "world");

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_STRING_MIXED_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c7708000000100000000374000071007e000374000141740005616c70686174000568656c6c6f740005776f726c647800"),
                actual
        );
    }

    @Test
    void linked_hash_map_string_string_with_null_value_shape_is_stable() throws Exception {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("one", "value-1");
        value.put("two", null);
        value.put("three", "value-3");

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_STRING_WITH_NULL_VALUE_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400036f6e6574000776616c75652d3174000374776f70740005746872656574000776616c75652d337800"),
                actual
        );
    }

    @Test
    void linked_hash_map_string_string_with_null_key_shape_is_stable() throws Exception {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("one", "value-1");
        value.put(null, "null-key-value");
        value.put("three", "value-3");

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_STRING_WITH_NULL_KEY_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400036f6e6574000776616c75652d317074000e6e756c6c2d6b65792d76616c7565740005746872656574000776616c75652d337800"),
                actual
        );
    }

    @Test
    void linked_hash_map_string_string_with_null_key_and_null_value_shape_is_stable() throws Exception {
        Map<String, String> value = new LinkedHashMap<>();
        value.put("one", "value-1");
        value.put(null, null);
        value.put("three", "value-3");

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_STRING_WITH_NULL_KEY_AND_NULL_VALUE_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400036f6e6574000776616c75652d317070740005746872656574000776616c75652d337800"),
                actual
        );
    }

    private static byte[] serializeMap(Map<String, String> value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(bytes)) {
            DataSerializer.writeObject(value, out);
        }

        return bytes.toByteArray();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must be non-null and have an even length");
        }

        byte[] out = new byte[hex.length() / 2];

        for (int i = 0; i < hex.length(); i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }

        return out;
    }

    private static void printHex(String label, byte[] payload) {
        System.out.println(label + "_START");
        System.out.println(toHex(payload));
        System.out.println(label + "_END");
    }

    private static String toHex(byte[] payload) {
        StringBuilder out = new StringBuilder();

        for (byte b : payload) {
            out.append(String.format("%02x", b & 0xff));
        }

        return out.toString();
    }
}