package com.protogemcouch.shim;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-version negotiation matrix. Drives the <em>authoritative</em> Geode client protocol
 * version → ordinal table (read from {@code KnownVersion} in the geode-core jar) through the shim's
 * {@link HandshakeVersionPolicy}: every version's handshake ordinal must parse correctly (covering
 * both the single-byte form for ordinals {@code <= 127} and the {@code 0xFF}-token + 2-byte-short form
 * for ordinals {@code > 127}), only the configured allowlist is accepted, and every other version gets
 * a well-formed {@code REPLY_REFUSED}.
 *
 * <p>This complements {@link HandshakeVersionPolicyTest} (which spot-checks the parse/accept/refuse
 * logic) by exercising the entire real version line, so a mis-encoded ordinal or an off-by-one at the
 * single-byte/token boundary can't slip through.
 */
class HandshakeVersionMatrixTest {

    /** Geode {@code KnownVersion} ordinals (authoritative, dumped from the geode-core jar). */
    private static final Map<Integer, String> GEODE_VERSIONS = geodeVersions();

    private static Map<Integer, String> geodeVersions() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(35, "8.1");
        m.put(45, "9.0");
        m.put(50, "1.1.0");
        m.put(55, "1.1.1");
        m.put(65, "1.2.0");
        m.put(70, "1.3.0");
        m.put(75, "1.4.0");
        m.put(80, "1.5.0");
        m.put(85, "1.6.0");
        m.put(90, "1.7.0");
        m.put(95, "1.8.0");
        m.put(100, "1.9.0");
        m.put(105, "1.10.0");
        m.put(110, "1.11.0");
        m.put(115, "1.12.0");
        m.put(116, "1.12.1");
        m.put(120, "1.13.0");
        m.put(121, "1.13.2");
        m.put(125, "1.14.0");
        m.put(150, "1.15.0"); // CURRENT — the wire-validated line
        return m;
    }

    /** Build a handshake buffer carrying {@code ordinal} exactly as Geode's writeOrdinal does. */
    private static byte[] handshakeFor(int ordinal) {
        byte mode = 0x64; // communication mode 100 (client→server op)
        if (ordinal <= 127) {
            // single byte for ordinals <= Byte.MAX_VALUE, then some trailing handshake bytes
            return new byte[] {mode, (byte) ordinal, 0x00, 0x00, 0x00, 0x00};
        }
        // 0xFF token + 2-byte big-endian short for larger ordinals
        return new byte[] {mode, (byte) 0xFF, (byte) (ordinal >>> 8), (byte) ordinal, 0x00, 0x00};
    }

    @Test
    void everyKnownVersionOrdinalParsesFromItsHandshake() {
        for (Map.Entry<Integer, String> e : GEODE_VERSIONS.entrySet()) {
            int ordinal = e.getKey();
            assertEquals(ordinal, HandshakeVersionPolicy.parseVersionOrdinal(handshakeFor(ordinal)),
                    "ordinal for Geode " + e.getValue() + " must parse from its handshake");
        }
    }

    @Test
    void singleByteAndTokenBoundaryParseCorrectly() {
        // 127 is the largest single-byte ordinal; 128 is the smallest token-encoded one.
        assertEquals(127, HandshakeVersionPolicy.parseVersionOrdinal(handshakeFor(127)));
        assertEquals(128, HandshakeVersionPolicy.parseVersionOrdinal(handshakeFor(128)));
        assertEquals(150, HandshakeVersionPolicy.parseVersionOrdinal(handshakeFor(150)));
        assertEquals(65535, HandshakeVersionPolicy.parseVersionOrdinal(handshakeFor(65535)));
    }

    @Test
    void defaultPolicyAcceptsOnly_1_15_AndRefusesEveryOtherVersion() {
        HandshakeVersionPolicy policy = new HandshakeVersionPolicy(Set.of(HandshakeVersionPolicy.GEODE_1_15_ORDINAL));
        for (Map.Entry<Integer, String> e : GEODE_VERSIONS.entrySet()) {
            int ordinal = e.getKey();
            boolean expected = ordinal == HandshakeVersionPolicy.GEODE_1_15_ORDINAL;
            assertEquals(expected, policy.isSupported(ordinal),
                    "Geode " + e.getValue() + " (ordinal " + ordinal + ") support under the default policy");
        }
    }

    @Test
    void wideningAcceptsExactlyTheConfiguredVersions() {
        // Operator validated 1.14.0 (125) in their environment alongside the default 1.15.x (150).
        HandshakeVersionPolicy policy = new HandshakeVersionPolicy(Set.of(125, 150));
        assertTrue(policy.isSupported(125), "1.14.0 accepted when explicitly widened");
        assertTrue(policy.isSupported(150), "1.15.x still accepted");
        assertFalse(policy.isSupported(120), "1.13.0 not accepted (not in the allowlist)");
        assertFalse(policy.isSupported(45), "9.0 not accepted");
    }

    @Test
    void refusalIsWellFormedForEveryUnsupportedVersion() throws Exception {
        HandshakeVersionPolicy policy = new HandshakeVersionPolicy(Set.of(HandshakeVersionPolicy.GEODE_1_15_ORDINAL));
        for (Map.Entry<Integer, String> e : GEODE_VERSIONS.entrySet()) {
            int ordinal = e.getKey();
            if (ordinal == HandshakeVersionPolicy.GEODE_1_15_ORDINAL) {
                continue;
            }
            byte[] reply = policy.buildRefusalReply(ordinal);
            assertEquals(HandshakeVersionPolicy.REPLY_REFUSED, reply[0],
                    "refusal for Geode " + e.getValue() + " starts with REPLY_REFUSED");
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(reply, 1, reply.length - 1))) {
                String message = in.readUTF(); // must be a readable modified-UTF string
                assertTrue(message.contains(Integer.toString(ordinal)),
                        "refusal message names the rejected ordinal " + ordinal + ": " + message);
            }
        }
    }
}
