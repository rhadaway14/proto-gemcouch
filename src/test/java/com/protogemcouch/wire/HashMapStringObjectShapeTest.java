package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class HashMapStringObjectShapeTest {

    @Test
    void hash_map_string_object_empty_shape_is_stable() throws Exception {
        HashMap<String, Object> value = new HashMap<>();

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_OBJECT_EMPTY_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x43,
                        0x00
                },
                actual
        );
    }

    @Test
    void linked_hash_map_string_object_one_string_shape_is_stable() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "rob");

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_OBJECT_ONE_STRING_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000017400046e616d65740003726f627800"),
                actual
        );
    }

    @Test
    void linked_hash_map_string_object_string_integer_boolean_shape_is_stable() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "rob");
        value.put("age", Integer.valueOf(42));
        value.put("active", Boolean.TRUE);

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_OBJECT_STRING_INTEGER_BOOLEAN_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400046e616d65740003726f62740003616765737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b02000078700000002a740006616374697665737200116a6176612e6c616e672e426f6f6c65616ecd207280d59cfaee0200015a000576616c75657870017800"),
                actual
        );
    }

    @Test
    void linked_hash_map_string_object_string_null_date_shape_is_stable() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "rob");
        value.put("middleName", null);
        value.put("createdAt", new Date(1_000L));

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_OBJECT_STRING_NULL_DATE_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400046e616d65740003726f6274000a6d6964646c654e616d65707400096372656174656441747372000e6a6176612e7574696c2e44617465686a81014b5974190300007870770800000000000003e8787800"),
                actual
        );
    }

    @Test
    void linked_hash_map_string_object_numeric_types_shape_is_stable() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("byteValue", Byte.valueOf((byte) 7));
        value.put("shortValue", Short.valueOf((short) 77));
        value.put("integerValue", Integer.valueOf(12345));
        value.put("longValue", Long.valueOf(9_876_543_210L));
        value.put("floatValue", Float.valueOf(7.25f));
        value.put("doubleValue", Double.valueOf(7.25d));

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_OBJECT_NUMERIC_TYPES_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000067400096279746556616c75657372000e6a6176612e6c616e672e427974659c4e6084ee50f51c02000142000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b02000078700774000a73686f727456616c75657372000f6a6176612e6c616e672e53686f7274684d37133460da5202000153000576616c75657871007e0005004d74000c696e746567657256616c7565737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c75657871007e0005000030397400096c6f6e6756616c75657372000e6a6176612e6c616e672e4c6f6e673b8be490cc8f23df0200014a000576616c75657871007e0005000000024cb016ea74000a666c6f617456616c75657372000f6a6176612e6c616e672e466c6f6174daedc9a2db3cf0ec02000146000576616c75657871007e000540e8000074000b646f75626c6556616c7565737200106a6176612e6c616e672e446f75626c6580b3c24a296bfb0402000144000576616c75657871007e0005401d0000000000007800"),
                actual
        );
    }

    @Test
    void linked_hash_map_string_object_byte_array_shape_is_stable() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("payload", new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05
        });

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_OBJECT_BYTE_ARRAY_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000017400077061796c6f6164757200025b42acf317f8060854e002000078700000000501020304057800"),
                actual
        );
    }

    @Test
    void linked_hash_map_string_object_string_array_shape_is_stable() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("items", new String[] {
                "one",
                null,
                "three"
        });

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_OBJECT_STRING_ARRAY_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000017400056974656d73757200135b4c6a6176612e6c616e672e537472696e673badd256e7e91d7b470200007870000000037400036f6e657074000574687265657800"),
                actual
        );
    }

    @Test
    void linked_hash_map_string_object_string_array_list_shape_is_stable() throws Exception {
        ArrayList<String> items = new ArrayList<>();
        items.add("one");
        items.add(null);
        items.add("three");

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("items", items);

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_OBJECT_STRING_ARRAY_LIST_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000017400056974656d73737200136a6176612e7574696c2e41727261794c6973747881d21d99c7619d03000149000473697a657870000000037704000000037400036f6e65707400057468726565787800"),
                actual
        );
    }

    @Test
    void linked_hash_map_string_object_with_null_key_shape_is_stable() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "rob");
        value.put(null, "null-key-value");
        value.put("active", Boolean.TRUE);

        byte[] actual = serializeMap(value);

        printHex("HASH_MAP_STRING_OBJECT_WITH_NULL_KEY_HEX", actual);

        assertArrayEquals(
                hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400046e616d65740003726f627074000e6e756c6c2d6b65792d76616c7565740006616374697665737200116a6176612e6c616e672e426f6f6c65616ecd207280d59cfaee0200015a000576616c75657870017800"),
                actual
        );
    }

    private static byte[] serializeMap(Map<String, Object> value) throws IOException {
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