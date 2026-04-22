package com.protogemcouch.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ByteUtilsTest {

    @Test
    void bytesToString_removesNulls_and_trims() {
        byte[] input = new byte[] {0, ' ', 'h', 'e', 'l', 'l', 'o', ' ', 0};
        String result = ByteUtils.bytesToString(input);
        assertEquals("hello", result);
    }

    @Test
    void bytesToInt_reads_big_endian_int() {
        byte[] input = new byte[] {0x00, 0x00, 0x00, 0x2A};
        int result = ByteUtils.bytesToInt(input);
        assertEquals(42, result);
    }

    @Test
    void bytesToInt_returns_zero_for_null() {
        assertEquals(0, ByteUtils.bytesToInt(null));
    }

    @Test
    void bytesToInt_returns_zero_for_short_array() {
        assertEquals(0, ByteUtils.bytesToInt(new byte[] {0x01, 0x02}));
    }

    @Test
    void hex_decodes_hex_string() {
        byte[] result = ByteUtils.hex("0a0b0c");
        assertArrayEquals(new byte[] {0x0A, 0x0B, 0x0C}, result);
    }
}