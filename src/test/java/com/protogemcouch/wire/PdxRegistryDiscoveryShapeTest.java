package com.protogemcouch.wire;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the bulk PDX registry-discovery reply framing (GET_PDX_TYPES 101 / GET_PDX_ENUMS 102 /
 * GET_PDX_ENUM_BY_ID 98) to the shape decoded from a real Geode 1.15.1 server
 * (see {@code tools/GetPdxRegistryCapture}). The map reply is a Geode {@code HashMap} — marker
 * {@code 0x43}, a compact entry count, then each entry as an {@code Integer} key ({@code 0x39} + the
 * 4-byte id) followed by the value's already-serialized object bytes. The per-value PdxType/EnumInfo
 * bytes themselves come straight from the registry (the same form GET_PDX_TYPE_BY_ID serves), so this
 * test validates the framing with a synthetic value using a real captured id ({@code 0x0015c65c}).
 */
class PdxRegistryDiscoveryShapeTest {

    private static final int TX_ID = -1;

    @Test
    void registryMapReplyUsesGeodeHashMapFraming() {
        LinkedHashMap<Integer, byte[]> map = new LinkedHashMap<>();
        map.put(0x0015c65c, new byte[] {(byte) 0xaa, (byte) 0xbb}); // id 1427036 -> 2 value bytes

        byte[] actual = GemResponseWriter.buildPdxRegistryMapResponse(TX_ID, map);

        // RESPONSE(1) | len=14 | parts=1 | txId=-1 | sec=00 | partLen=9 | obj=01 |
        //   43 (HashMap) 01 (size 1) 39 0015c65c (Integer key) aabb (value)
        String expected = "00000001" + "0000000e" + "00000001" + "ffffffff" + "00"
                + "00000009" + "01" + "43" + "01" + "39" + "0015c65c" + "aabb";
        assertEquals(expected, toHex(actual));
    }

    @Test
    void emptyRegistryMapReplyIsAnEmptyHashMap() {
        byte[] actual = GemResponseWriter.buildPdxRegistryMapResponse(TX_ID, new LinkedHashMap<>());
        // ... obj = 43 (HashMap) 00 (size 0)
        String expected = "00000001" + "00000007" + "00000001" + "ffffffff" + "00"
                + "00000002" + "01" + "43" + "00";
        assertEquals(expected, toHex(actual));
    }

    @Test
    void enumByIdReplyIsASingleObjectPart() {
        byte[] actual = GemResponseWriter.buildPdxEnumByIdResponse(TX_ID, new byte[] {0x01, 0x09, 0x41});
        // RESPONSE(1) | len=8 | parts=1 | txId=-1 | sec=00 | partLen=3 | obj=01 | 010941
        String expected = "00000001" + "00000008" + "00000001" + "ffffffff" + "00"
                + "00000003" + "01" + "010941";
        assertEquals(expected, toHex(actual));
    }

    private static String toHex(byte[] b) {
        return java.util.HexFormat.of().formatHex(b);
    }
}
