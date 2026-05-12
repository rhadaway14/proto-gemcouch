package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class StringArrayShapeTest {

    @Test
    void string_array_empty_shape_is_stable() throws Exception {
        byte[] actual = serializeStringArray(new String[] {});

        printHex("STRING_ARRAY_EMPTY_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x40,
                        0x00
                },
                actual
        );
    }

    @Test
    void string_array_one_shape_is_stable() throws Exception {
        byte[] actual = serializeStringArray(new String[] {
                "one"
        });

        printHex("STRING_ARRAY_ONE_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x40,
                        0x01,
                        0x57, 0x00, 0x03,
                        0x6f, 0x6e, 0x65
                },
                actual
        );
    }

    @Test
    void string_array_three_shape_is_stable() throws Exception {
        byte[] actual = serializeStringArray(new String[] {
                "one",
                "two",
                "three"
        });

        printHex("STRING_ARRAY_THREE_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x40,
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
    void string_array_mixed_shape_is_stable() throws Exception {
        byte[] actual = serializeStringArray(new String[] {
                "",
                "A",
                "hello"
        });

        printHex("STRING_ARRAY_MIXED_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x40,
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
    void string_array_with_null_element_shape_is_stable() throws Exception {
        byte[] actual = serializeStringArray(new String[] {
                "one",
                null,
                "three"
        });

        printHex("STRING_ARRAY_WITH_NULL_ELEMENT_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x40,
                        0x03,

                        0x57, 0x00, 0x03,
                        0x6f, 0x6e, 0x65,

                        0x45,

                        0x57, 0x00, 0x05,
                        0x74, 0x68, 0x72, 0x65, 0x65
                },
                actual
        );
    }

    private static byte[] serializeStringArray(String[] value) throws IOException {
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