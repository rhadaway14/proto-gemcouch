package com.protogemcouch.shim;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Protocol version negotiation policy: parse the Geode client's protocol version ordinal from its
 * handshake and decide whether the shim supports it.
 *
 * <p>The shim's wire encodings are reverse-engineered and validated against the <b>Geode 1.15.x</b>
 * line (ordinal {@value #GEODE_1_15_ORDINAL}), so that is the default supported set. The allowlist is
 * widenable via the {@code SUPPORTED_VERSION_ORDINALS} environment variable (comma-separated ordinals)
 * for operators who have validated additional, wire-compatible Geode versions in their own environment.
 *
 * <p>Handshake layout (see {@code com.protogemcouch.tools.HandshakeCapture}): byte 0 is the
 * communication mode; byte 1 begins the client {@code Version} ordinal, written by Geode's
 * {@code StaticSerialization.writeOrdinal} — a single byte when the ordinal is {@code <= 127}, else a
 * token byte {@code 0xFF} followed by a 2-byte big-endian short. A real Geode 1.15.x client sends
 * {@code 64 FF 00 96 …} (mode 100, ordinal 0x0096 = 150).
 */
public final class HandshakeVersionPolicy {

    /** Geode {@code StaticSerialization.writeOrdinal} token: ordinals > 127 follow as a 2-byte short. */
    private static final int TOKEN_ORDINAL = 0xFF;

    /** Geode 1.15.x protocol version ordinal — the validated wire surface. */
    public static final int GEODE_1_15_ORDINAL = 150;

    /** Returned by {@link #parseVersionOrdinal(byte[])} when the ordinal can't be read. */
    public static final int UNKNOWN_ORDINAL = -1;

    /** Geode {@code Handshake.REPLY_REFUSED} — makes the client raise ServerRefusedConnectionException. */
    public static final byte REPLY_REFUSED = 60;

    private final Set<Integer> supportedOrdinals;

    public HandshakeVersionPolicy(Set<Integer> supportedOrdinals) {
        this.supportedOrdinals = Set.copyOf(supportedOrdinals);
    }

    /** Build the policy from {@code SUPPORTED_VERSION_ORDINALS} (default: Geode 1.15.x only). */
    public static HandshakeVersionPolicy fromEnvironment() {
        return new HandshakeVersionPolicy(parseSupportedOrdinals(System.getenv("SUPPORTED_VERSION_ORDINALS")));
    }

    /** Parse a comma-separated ordinal list; blank/null/unparseable falls back to {@code {150}}. */
    static Set<Integer> parseSupportedOrdinals(String csv) {
        Set<Integer> ordinals = new LinkedHashSet<>();
        if (csv != null && !csv.isBlank()) {
            for (String token : csv.split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    ordinals.add(Integer.parseInt(trimmed));
                } catch (NumberFormatException ignored) {
                    // skip a bad token rather than fail startup
                }
            }
        }
        if (ordinals.isEmpty()) {
            ordinals.add(GEODE_1_15_ORDINAL);
        }
        return ordinals;
    }

    /**
     * Parse the client version ordinal from a handshake buffer, or {@link #UNKNOWN_ORDINAL} if the
     * buffer is too short to contain it.
     */
    public static int parseVersionOrdinal(byte[] handshake) {
        if (handshake == null || handshake.length < 2) {
            return UNKNOWN_ORDINAL;
        }
        int first = handshake[1] & 0xff;
        if (first == TOKEN_ORDINAL) {
            if (handshake.length < 4) {
                return UNKNOWN_ORDINAL;
            }
            return ((handshake[2] & 0xff) << 8) | (handshake[3] & 0xff);
        }
        return first;
    }

    public boolean isSupported(int ordinal) {
        return ordinal != UNKNOWN_ORDINAL && supportedOrdinals.contains(ordinal);
    }

    public Set<Integer> supportedOrdinals() {
        return supportedOrdinals;
    }

    /**
     * Build the handshake refusal a client receives for an unsupported version: {@link #REPLY_REFUSED}
     * followed by {@code DataOutputStream.writeUTF} (the modified-UTF, 2-byte-length form Geode's
     * {@code readUTF} expects). The client raises ServerRefusedConnectionException carrying the message.
     */
    public byte[] buildRefusalReply(int ordinal) {
        String message = "ProtoGemCouch: unsupported Geode client protocol version (ordinal " + ordinal
                + "). Supported ordinals: " + supportedOrdinals + " (set SUPPORTED_VERSION_ORDINALS to widen).";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeByte(REPLY_REFUSED);
            dos.writeUTF(message);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream never throws
        }
        return bos.toByteArray();
    }
}
