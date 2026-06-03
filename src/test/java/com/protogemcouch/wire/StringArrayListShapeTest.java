package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class StringArrayListShapeTest {

    @Test
    void string_array_list_empty_shape_is_stable() throws Exception {
        byte[] actual = serializeArrayList(new ArrayList<>());

        printHex("STRING_ARRAY_LIST_EMPTY_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x41,
                        0x00
                },
                actual
        );
    }

    @Test
    void string_array_list_one_shape_is_stable() throws Exception {
        ArrayList<String> value = new ArrayList<>();
        value.add("one");

        byte[] actual = serializeArrayList(value);

        printHex("STRING_ARRAY_LIST_ONE_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x41,
                        0x01,

                        0x57, 0x00, 0x03,
                        0x6f, 0x6e, 0x65
                },
                actual
        );
    }

    @Test
    void string_array_list_three_shape_is_stable() throws Exception {
        ArrayList<String> value = new ArrayList<>(Arrays.asList(
                "one",
                "two",
                "three"
        ));

        byte[] actual = serializeArrayList(value);

        printHex("STRING_ARRAY_LIST_THREE_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x41,
                        0x03,

                        0x57, 0x00, 0x03,
                        0x6f, 0x6e, 0x65,

                        0x57, 0x00, 0x03,
                        0x74, 0x77, 0x6f,

                        0x57, 0x00, 0x05,
                        0x74, 0x68, 0x72, 0x65, 0x65
                },
                actual
        );
    }

    @Test
    void string_array_list_mixed_shape_is_stable() throws Exception {
        ArrayList<String> value = new ArrayList<>(Arrays.asList(
                "",
                "A",
                "hello"
        ));

        byte[] actual = serializeArrayList(value);

        printHex("STRING_ARRAY_LIST_MIXED_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x41,
                        0x03,

                        0x57, 0x00, 0x00,

                        0x57, 0x00, 0x01,
                        0x41,

                        0x57, 0x00, 0x05,
                        0x68, 0x65, 0x6c, 0x6c, 0x6f
                },
                actual
        );
    }

    @Test
    void string_array_list_with_null_element_shape_is_stable() throws Exception {
        ArrayList<String> value = new ArrayList<>();
        value.add("one");
        value.add(null);
        value.add("three");

        byte[] actual = serializeArrayList(value);

        printHex("STRING_ARRAY_LIST_WITH_NULL_ELEMENT_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x41,
                        0x03,

                        0x57, 0x00, 0x03,
                        0x6f, 0x6e, 0x65,

                        0x29,

                        0x57, 0x00, 0x05,
                        0x74, 0x68, 0x72, 0x65, 0x65
                },
                actual
        );
    }

    private static byte[] serializeArrayList(ArrayList<String> value) throws IOException {
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
}