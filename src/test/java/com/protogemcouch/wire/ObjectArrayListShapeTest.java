package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectArrayListShapeTest {

    @Test
    void empty_object_array_list_shape_is_observed() throws Exception {
        ArrayList<Object> value = new ArrayList<>();

        byte[] actual = serializeObject(value);

        printHex("OBJECT_ARRAY_LIST_EMPTY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void object_array_list_with_one_string_shape_is_observed() throws Exception {
        ArrayList<Object> value = new ArrayList<>();
        value.add("one");

        byte[] actual = serializeObject(value);

        printHex("OBJECT_ARRAY_LIST_ONE_STRING_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void object_array_list_with_string_integer_boolean_shape_is_observed() throws Exception {
        ArrayList<Object> value = new ArrayList<>();
        value.add("one");
        value.add(Integer.valueOf(42));
        value.add(Boolean.TRUE);

        byte[] actual = serializeObject(value);

        printHex("OBJECT_ARRAY_LIST_STRING_INTEGER_BOOLEAN_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void object_array_list_with_null_element_shape_is_observed() throws Exception {
        ArrayList<Object> value = new ArrayList<>();
        value.add("one");
        value.add(null);
        value.add("three");

        byte[] actual = serializeObject(value);

        printHex("OBJECT_ARRAY_LIST_WITH_NULL_ELEMENT_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void object_array_list_with_date_and_byte_array_shape_is_observed() throws Exception {
        ArrayList<Object> value = new ArrayList<>();
        value.add(new Date(1_000L));
        value.add(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05});

        byte[] actual = serializeObject(value);

        printHex("OBJECT_ARRAY_LIST_WITH_DATE_AND_BYTE_ARRAY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void object_array_list_with_nested_string_array_shape_is_observed() throws Exception {
        ArrayList<Object> value = new ArrayList<>();
        value.add("outer");
        value.add(new String[] {
                "one",
                null,
                "three"
        });

        byte[] actual = serializeObject(value);

        printHex("OBJECT_ARRAY_LIST_WITH_NESTED_STRING_ARRAY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void object_array_list_with_nested_object_array_shape_is_observed() throws Exception {
        ArrayList<Object> value = new ArrayList<>();
        value.add("outer");
        value.add(new Object[] {
                "one",
                Integer.valueOf(42),
                Boolean.TRUE
        });

        byte[] actual = serializeObject(value);

        printHex("OBJECT_ARRAY_LIST_WITH_NESTED_OBJECT_ARRAY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void object_array_list_with_nested_string_array_list_shape_is_observed() throws Exception {
        ArrayList<String> nested = new ArrayList<>();
        nested.add("one");
        nested.add(null);
        nested.add("three");

        ArrayList<Object> value = new ArrayList<>();
        value.add("outer");
        value.add(nested);

        byte[] actual = serializeObject(value);

        printHex("OBJECT_ARRAY_LIST_WITH_NESTED_STRING_ARRAY_LIST_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void object_array_list_with_nested_string_object_hash_map_shape_is_observed() throws Exception {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("name", "rob");
        map.put("age", Integer.valueOf(42));
        map.put("active", Boolean.TRUE);
        map.put("createdAt", new Date(1_000L));

        ArrayList<Object> value = new ArrayList<>();
        value.add("outer");
        value.add(map);

        byte[] actual = serializeObject(value);

        printHex("OBJECT_ARRAY_LIST_WITH_NESTED_STRING_OBJECT_HASH_MAP_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void object_array_list_with_nested_serializable_pojo_shape_is_observed() throws Exception {
        CustomerProfile profile = new CustomerProfile(
                "customer-1",
                "Rob",
                42,
                true
        );

        ArrayList<Object> value = new ArrayList<>();
        value.add("outer");
        value.add(profile);

        byte[] actual = serializeObject(value);

        printHex("OBJECT_ARRAY_LIST_WITH_NESTED_SERIALIZABLE_POJO_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void object_array_list_with_mixed_supported_values_shape_is_observed() throws Exception {
        ArrayList<String> nestedStringList = new ArrayList<>();
        nestedStringList.add("one");
        nestedStringList.add(null);
        nestedStringList.add("three");

        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("name", "rob");
        map.put("age", Integer.valueOf(42));
        map.put("active", Boolean.TRUE);
        map.put("createdAt", new Date(1_000L));

        CustomerProfile profile = new CustomerProfile(
                "customer-1",
                "Rob",
                42,
                true
        );

        ArrayList<Object> value = new ArrayList<>();
        value.add("string-value");
        value.add(Character.valueOf('A'));
        value.add(Byte.valueOf((byte) 7));
        value.add(new byte[] {0x01, 0x02, 0x03});
        value.add(new String[] {"one", null, "three"});
        value.add(new Object[] {"one", Integer.valueOf(42), Boolean.TRUE});
        value.add(nestedStringList);
        value.add(map);
        value.add(profile);
        value.add(Short.valueOf((short) 7));
        value.add(Integer.valueOf(42));
        value.add(Boolean.TRUE);
        value.add(Long.valueOf(9_876_543_210L));
        value.add(Float.valueOf(7.25f));
        value.add(Double.valueOf(7.25d));
        value.add(new Date(1_000L));

        byte[] actual = serializeObject(value);

        printHex("OBJECT_ARRAY_LIST_MIXED_SUPPORTED_VALUES_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    private static byte[] serializeObject(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(bytes)) {
            DataSerializer.writeObject(value, out);
        }

        return bytes.toByteArray();
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

    private static final class CustomerProfile implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String id;
        private final String name;
        private final int age;
        private final boolean active;

        private CustomerProfile(String id, String name, int age, boolean active) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.active = active;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof CustomerProfile that)) {
                return false;
            }

            return age == that.age
                    && active == that.active
                    && Objects.equals(id, that.id)
                    && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, age, active);
        }
    }
}