package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class DateShapeTest {

    @Test
    void date_epoch_shape_is_stable() throws Exception {
        byte[] actual = serializeDate(new Date(0L));

        printHex("DATE_EPOCH_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x3d,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00
                },
                actual
        );
    }

    @Test
    void date_one_second_shape_is_stable() throws Exception {
        byte[] actual = serializeDate(new Date(1_000L));

        printHex("DATE_ONE_SECOND_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x3d,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x03, (byte) 0xe8
                },
                actual
        );
    }

    @Test
    void date_known_future_shape_is_stable() throws Exception {
        byte[] actual = serializeDate(new Date(1_778_265_266_000L));

        printHex("DATE_KNOWN_FUTURE_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x3d,
                        0x00, 0x00, 0x01, (byte) 0x9e,
                        0x08, (byte) 0xde, (byte) 0x97, 0x50
                },
                actual
        );
    }

    @Test
    void date_negative_shape_is_stable() throws Exception {
        byte[] actual = serializeDate(new Date(-1_000L));

        printHex("DATE_NEGATIVE_HEX", actual);

        assertArrayEquals(
                new byte[] {
                        0x3d,
                        (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                        (byte) 0xff, (byte) 0xff, (byte) 0xfc, 0x18
                },
                actual
        );
    }

    private static byte[] serializeDate(Date value) throws IOException {
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