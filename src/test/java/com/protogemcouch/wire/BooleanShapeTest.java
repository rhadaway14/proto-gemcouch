package com.protogemcouch.wire;

import com.protogemcouch.serialization.ValueDecoding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BooleanShapeTest {

    @Test
    void geode_boolean_true_shape_should_decode() {
        byte[] payload = new byte[] {
                0x35,
                0x01
        };

        System.out.println("BOOLEAN_TRUE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("BOOLEAN_TRUE_HEX_END");

        assertEquals(Boolean.TRUE, ValueDecoding.decodeBooleanValue(payload));
    }

    @Test
    void geode_boolean_false_shape_should_decode() {
        byte[] payload = new byte[] {
                0x35,
                0x00
        };

        System.out.println("BOOLEAN_FALSE_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("BOOLEAN_FALSE_HEX_END");

        assertEquals(Boolean.FALSE, ValueDecoding.decodeBooleanValue(payload));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder out = new StringBuilder();

        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xff));
        }

        return out.toString();
    }
}