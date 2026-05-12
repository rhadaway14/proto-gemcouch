package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ByteArrayShapeTest {

    @Test
    void byte_array_empty_shape_is_stable() throws Exception {
        byte[] actual = serializeByteArray(new byte[] {});

        printHex("BYTE_ARRAY_EMPTY_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x2e,
                        0x00
                },
                actual
        );
    }

    @Test
    void byte_array_one_shape_is_stable() throws Exception {
        byte[] actual = serializeByteArray(new byte[] {0x01});

        printHex("BYTE_ARRAY_ONE_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x2e,
                        0x01,
                        0x01
                },
                actual
        );
    }

    @Test
    void byte_array_five_shape_is_stable() throws Exception {
        byte[] actual = serializeByteArray(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05});

        printHex("BYTE_ARRAY_FIVE_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x2e,
                        0x05,
                        0x01, 0x02, 0x03, 0x04, 0x05
                },
                actual
        );
    }

    @Test
    void byte_array_mixed_shape_is_stable() throws Exception {
        byte[] actual = serializeByteArray(new byte[] {
                0x00,
                0x01,
                0x7f,
                (byte) 0x80,
                (byte) 0xff
        });

        printHex("BYTE_ARRAY_MIXED_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x2e,
                        0x05,
                        0x00, 0x01, 0x7f, (byte) 0x80, (byte) 0xff
                },
                actual
        );
    }

    private static byte[] serializeByteArray(byte[] value) throws IOException {
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