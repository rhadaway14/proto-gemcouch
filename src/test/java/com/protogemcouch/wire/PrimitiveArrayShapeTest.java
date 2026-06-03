package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimitiveArrayShapeTest {

    @Test
    void empty_int_array_shape_is_observed() throws Exception {
        int[] value = new int[] {};

        byte[] actual = serializeObject(value);

        printHex("INT_ARRAY_EMPTY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void int_array_shape_is_observed() throws Exception {
        int[] value = new int[] {
                1,
                42,
                -7,
                Integer.MAX_VALUE,
                Integer.MIN_VALUE
        };

        byte[] actual = serializeObject(value);

        printHex("INT_ARRAY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void empty_long_array_shape_is_observed() throws Exception {
        long[] value = new long[] {};

        byte[] actual = serializeObject(value);

        printHex("LONG_ARRAY_EMPTY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void long_array_shape_is_observed() throws Exception {
        long[] value = new long[] {
                1L,
                42L,
                -7L,
                9_876_543_210L,
                Long.MAX_VALUE,
                Long.MIN_VALUE
        };

        byte[] actual = serializeObject(value);

        printHex("LONG_ARRAY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void empty_short_array_shape_is_observed() throws Exception {
        short[] value = new short[] {};

        byte[] actual = serializeObject(value);

        printHex("SHORT_ARRAY_EMPTY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void short_array_shape_is_observed() throws Exception {
        short[] value = new short[] {
                1,
                42,
                -7,
                Short.MAX_VALUE,
                Short.MIN_VALUE
        };

        byte[] actual = serializeObject(value);

        printHex("SHORT_ARRAY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void empty_float_array_shape_is_observed() throws Exception {
        float[] value = new float[] {};

        byte[] actual = serializeObject(value);

        printHex("FLOAT_ARRAY_EMPTY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void float_array_shape_is_observed() throws Exception {
        float[] value = new float[] {
                1.0f,
                7.25f,
                -7.25f,
                Float.MAX_VALUE,
                Float.MIN_VALUE
        };

        byte[] actual = serializeObject(value);

        printHex("FLOAT_ARRAY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void empty_double_array_shape_is_observed() throws Exception {
        double[] value = new double[] {};

        byte[] actual = serializeObject(value);

        printHex("DOUBLE_ARRAY_EMPTY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void double_array_shape_is_observed() throws Exception {
        double[] value = new double[] {
                1.0d,
                7.25d,
                -7.25d,
                Double.MAX_VALUE,
                Double.MIN_VALUE
        };

        byte[] actual = serializeObject(value);

        printHex("DOUBLE_ARRAY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void empty_boolean_array_shape_is_observed() throws Exception {
        boolean[] value = new boolean[] {};

        byte[] actual = serializeObject(value);

        printHex("BOOLEAN_ARRAY_EMPTY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void boolean_array_shape_is_observed() throws Exception {
        boolean[] value = new boolean[] {
                true,
                false,
                true,
                true,
                false
        };

        byte[] actual = serializeObject(value);

        printHex("BOOLEAN_ARRAY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void empty_char_array_shape_is_observed() throws Exception {
        char[] value = new char[] {};

        byte[] actual = serializeObject(value);

        printHex("CHAR_ARRAY_EMPTY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void char_array_shape_is_observed() throws Exception {
        char[] value = new char[] {
                'A',
                'Z',
                '0',
                '\n',
                '\u2603'
        };

        byte[] actual = serializeObject(value);

        printHex("CHAR_ARRAY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void empty_byte_array_shape_is_observed_for_comparison() throws Exception {
        byte[] value = new byte[] {};

        byte[] actual = serializeObject(value);

        printHex("BYTE_ARRAY_EMPTY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void byte_array_shape_is_observed_for_comparison() throws Exception {
        byte[] value = new byte[] {
                0x01,
                0x02,
                0x03,
                0x04,
                0x05
        };

        byte[] actual = serializeObject(value);

        printHex("BYTE_ARRAY_HEX", actual);

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
}