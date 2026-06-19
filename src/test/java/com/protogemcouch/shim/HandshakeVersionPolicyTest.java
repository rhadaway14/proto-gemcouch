package com.protogemcouch.shim;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HandshakeVersionPolicyTest {

    // A real Geode 1.15.x op-connection handshake captured by tools.HandshakeCapture:
    // byte 0 = 0x64 (mode 100), byte 1 = 0xFF (ordinal token), bytes 2-3 = 0x0096 (ordinal 150).
    private static final byte[] REAL_1_15_HANDSHAKE = HexFormat.of().parseHex(
            "64ff00963b000007d0012656015c040a0000bc0000e56c570014686f73742e646f636b65722e"
          + "696e7465726e616c09000000000000972c0d0057000057000862626135333064655700000000"
          + "012cff00960000000000000000000000000000000000000000010000");

    @Test
    void parsesTokenFormOrdinalFromRealGeode115Handshake() {
        assertEquals(150, HandshakeVersionPolicy.parseVersionOrdinal(REAL_1_15_HANDSHAKE));
    }

    @Test
    void parsesSingleByteOrdinalWhenOrdinalIsAtMost127() {
        // ordinal <= 127 is written as a single byte at offset 1 (no 0xFF token).
        byte[] handshake = {0x64, 110, 0x3b, 0x00};
        assertEquals(110, HandshakeVersionPolicy.parseVersionOrdinal(handshake));
    }

    @Test
    void returnsUnknownForBufferTooShortToContainTheOrdinal() {
        assertEquals(HandshakeVersionPolicy.UNKNOWN_ORDINAL,
                HandshakeVersionPolicy.parseVersionOrdinal(new byte[] {0x64}));
        // token form announced (0xFF) but the 2-byte short is missing
        assertEquals(HandshakeVersionPolicy.UNKNOWN_ORDINAL,
                HandshakeVersionPolicy.parseVersionOrdinal(new byte[] {0x64, (byte) 0xFF, 0x00}));
        assertEquals(HandshakeVersionPolicy.UNKNOWN_ORDINAL,
                HandshakeVersionPolicy.parseVersionOrdinal(new byte[0]));
    }

    @Test
    void defaultPolicyAcceptsGeode115AndRejectsOthers() {
        HandshakeVersionPolicy policy = new HandshakeVersionPolicy(
                HandshakeVersionPolicy.parseSupportedOrdinals(null));
        assertTrue(policy.isSupported(HandshakeVersionPolicy.GEODE_1_15_ORDINAL));
        assertTrue(policy.isSupported(150));
        assertFalse(policy.isSupported(110));   // 1.14.x
        assertFalse(policy.isSupported(75));     // older
        assertFalse(policy.isSupported(HandshakeVersionPolicy.UNKNOWN_ORDINAL));
    }

    @Test
    void blankOrUnparseableConfigFallsBackToGeode115() {
        assertEquals(Set.of(150), HandshakeVersionPolicy.parseSupportedOrdinals(null));
        assertEquals(Set.of(150), HandshakeVersionPolicy.parseSupportedOrdinals("   "));
        assertEquals(Set.of(150), HandshakeVersionPolicy.parseSupportedOrdinals("not-a-number"));
    }

    @Test
    void refusalReplyIsAWellFormedGeodeReplyRefusedFrame() throws Exception {
        HandshakeVersionPolicy policy = new HandshakeVersionPolicy(Set.of(150));
        byte[] reply = policy.buildRefusalReply(110);

        // Decode exactly as a Geode client would: first byte = REPLY_REFUSED, then DataInput.readUTF().
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(reply));
        assertEquals(HandshakeVersionPolicy.REPLY_REFUSED, in.readByte(),
                "refusal must start with Geode REPLY_REFUSED (60)");
        String message = in.readUTF();
        assertTrue(message.contains("ordinal 110"), "message names the rejected ordinal: " + message);
        assertTrue(message.contains("150"), "message names the supported ordinals: " + message);
        assertEquals(0, in.available(), "no trailing bytes after the refusal message");
    }

    @Test
    void configWidensTheAllowlist() {
        HandshakeVersionPolicy policy = new HandshakeVersionPolicy(
                HandshakeVersionPolicy.parseSupportedOrdinals("150, 110 , 100, bad"));
        assertEquals(Set.of(150, 110, 100), policy.supportedOrdinals());
        assertTrue(policy.isSupported(110));
        assertTrue(policy.isSupported(150));
        assertFalse(policy.isSupported(95));
    }
}
