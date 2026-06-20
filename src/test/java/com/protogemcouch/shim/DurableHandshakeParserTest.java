package com.protogemcouch.shim;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DurableHandshakeParserTest {

    // Real DURABLE membership bytes (durable-client-id=ITDURab49cb3d, timeout=300=0x12c) — exact capture.
    private static final byte[] DURABLE = HexFormat.of().parseHex(
            "2663015c040a0000bc0000e4cf570014686f73742e646f636b65722e696e7465726e616c0900000000000116ec"
          + "0d00570000570008396664303634653257000d495444555261623439636233640000012cff0096000000000000"
          + "0000000000000000000000000000010000");

    // Real NON-durable handshake: the last 0x57-string is empty (57 00 00).
    private static final byte[] NON_DURABLE = HexFormat.of().parseHex(
            "64ff00963b000007d0012656015c040a0000bc0000e56c570014686f73742e646f636b65722e696e7465726e616c"
          + "09000000000000972c0d0057000057000862626135333064655700000000012cff00960000000000000000000000"
          + "0000000000000000010000");

    @Test
    void parsesDurableIdAndTimeout() {
        DurableHandshakeParser.Durable d = DurableHandshakeParser.parse(DURABLE);
        assertNotNull(d, "durable client should be recognized");
        assertEquals("ITDURab49cb3d", d.id());
        assertEquals(300, d.timeoutSeconds());
    }

    @Test
    void nonDurableHandshakeReturnsNull() {
        assertNull(DurableHandshakeParser.parse(NON_DURABLE), "a non-durable client has an empty durable id");
    }
}
