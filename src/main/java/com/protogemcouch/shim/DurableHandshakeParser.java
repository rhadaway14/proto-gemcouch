package com.protogemcouch.shim;

import java.nio.charset.StandardCharsets;

/**
 * Extracts a durable client's identity from its Geode handshake. The durable id is the last printable
 * Geode string ({@code 57 00 <2-byte len> <bytes>}) in the membership — an empty trailing string means
 * a non-durable client — and the 4 bytes after it are the durable-client-timeout (seconds). See
 * {@code tools.DurableClientProbe} for the captured layout.
 */
public final class DurableHandshakeParser {

    /** Parsed durable identity, or {@code null} for a non-durable client. */
    public record Durable(String id, int timeoutSeconds) {
    }

    private DurableHandshakeParser() {
    }

    public static Durable parse(byte[] inbound) {
        if (inbound == null) {
            return null;
        }
        // Geode string framing: 0x57 marker (1 byte) + a 2-byte length + UTF bytes. The durable id is the
        // last such (printable) string in the membership.
        int idStart = -1;
        int idLen = -1;
        int i = 0;
        while (i + 3 <= inbound.length) {
            if ((inbound[i] & 0xff) == 0x57) {
                int len = ((inbound[i + 1] & 0xff) << 8) | (inbound[i + 2] & 0xff);
                int start = i + 3;
                if (len >= 0 && start + len <= inbound.length && isPrintableAscii(inbound, start, len)) {
                    idStart = start;
                    idLen = len;
                    i = start + len;
                    continue;
                }
            }
            i++;
        }
        if (idLen <= 0) {
            return null; // last string empty / none -> non-durable
        }
        String id = new String(inbound, idStart, idLen, StandardCharsets.UTF_8);
        int after = idStart + idLen;
        int timeout = (after + 4 <= inbound.length)
                ? ((inbound[after] & 0xff) << 24) | ((inbound[after + 1] & 0xff) << 16)
                | ((inbound[after + 2] & 0xff) << 8) | (inbound[after + 3] & 0xff)
                : 0;
        return new Durable(id, timeout);
    }

    private static boolean isPrintableAscii(byte[] b, int off, int len) {
        for (int j = off; j < off + len; j++) {
            int c = b[j] & 0xff;
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }
}
