package com.protogemcouch.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ByteUtilsTest {

    @Test
    void bytesToString_returns_trimmed_utf8_string() {
        byte[] bytes = " hello ".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("hello", ByteUtils.bytesToString(bytes));
    }

    @Test
    void bytesToString_returns_empty_for_null() {
        assertEquals("", ByteUtils.bytesToString(null));
    }

    @Test
    void bytesToInt_returns_int_for_four_byte_array() {
        byte[] bytes = new byte[] {0, 0, 0, 7};
        assertEquals(7, ByteUtils.bytesToInt(bytes));
    }

    @Test
    void bytesToInt_returns_zero_for_empty_array() {
        assertEquals(0, ByteUtils.bytesToInt(new byte[0]));
    }

    @Test
    void bytesToInt_left_pads_short_array() {
        byte[] bytes = new byte[] {0x01, 0x02};
        assertEquals(258, ByteUtils.bytesToInt(bytes));
    }

    @Test
    void intToBytes_round_trips_with_bytesToInt() {
        int value = 42;
        byte[] bytes = ByteUtils.intToBytes(value);
        assertEquals(42, ByteUtils.bytesToInt(bytes));
    }

    @Test
    void hex_decodes_hex_string() {
        byte[] bytes = ByteUtils.hex("0a0b0c");
        assertArrayEquals(new byte[] {0x0a, 0x0b, 0x0c}, bytes);
    }
}