package com.protogemcouch.wire;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-exact validation that the shim's GET_ENTRY (opcode 89) reply equals what a real Geode 1.15.1
 * server sends. The two reference hex strings were captured from a real server inside a client
 * transaction via {@code tools/GetEntryCapture} (region {@code /helloWorld}, key {@code k1}, value
 * {@code v1}, txId {@code 1}): one for a present key (a serialized {@code EntrySnapshot}) and one for
 * an absent key (a null object). Reproducing them to the byte proves the client's
 * {@code Region.getEntry(key)} reads back the entry/value (or null) correctly.
 */
class GetEntryResponseShapeTest {

    private static final int TX_ID = 1;

    // Captured server->client bytes for getEntry("k1") on a present entry (value "v1").
    private static final String PRESENT_CAPTURE =
            "000000010000005500000002000000010000000047012d2b57002d6f72672e6170616368652e67656f"
            + "64652e696e7465726e616c2e63616368652e456e747279536e617073686f74005700026b3157000276"
            + "3100000000000000000029000000040000000000";

    // Captured server->client bytes for getEntry("absent").
    private static final String ABSENT_CAPTURE =
            "000000010000000e0000000200000001000000000000000000040000000008";

    @Test
    void presentEntryReplyMatchesRealServerCapture() {
        byte[] actual = GemResponseWriter.buildGetEntryResponse(TX_ID, "k1", StoredValue.stringValue("v1"));
        assertEquals(PRESENT_CAPTURE, HexFormat.of().formatHex(actual),
                "GET_ENTRY present-key reply must be byte-identical to the real Geode server's EntrySnapshot");
    }

    @Test
    void absentEntryReplyMatchesRealServerCapture() {
        byte[] actual = GemResponseWriter.buildGetEntryNotFoundResponse(TX_ID);
        assertEquals(ABSENT_CAPTURE, HexFormat.of().formatHex(actual),
                "GET_ENTRY absent-key reply must be byte-identical to the real Geode server's null reply");
    }
}
