package com.protogemcouch.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end cross-version negotiation: a Geode client of a <em>lower</em> protocol version than the
 * shim supports must be cleanly refused at the handshake (so it never gets served a session it can't
 * decode). We can't run a real older-version Geode client jar offline, so we replay a real captured
 * Geode 1.15 op-connection handshake with only the version ordinal swapped to 1.14 (125, token-encoded
 * so the buffer layout is byte-identical), open a raw socket to the live shim, and assert it answers
 * with Geode's {@code REPLY_REFUSED} (60) and a readable message. The matching unit matrix
 * ({@code HandshakeVersionMatrixTest}) covers the accept/refuse decision across every version ordinal.
 */
@Tag("integration")
class ProtoGemCouchVersionNegotiationIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private static final byte REPLY_REFUSED = 60;

    // The real Geode 1.15.x op-connection handshake (mode 0x64, ordinal token 0xFF 0x0096 = 150),
    // with bytes 2-3 changed to 0x007d so the advertised ordinal is 125 (Geode 1.14.0) — everything
    // else, including the buffer layout, is identical to a genuine handshake.
    private static final byte[] GEODE_1_14_HANDSHAKE = HexFormat.of().parseHex(
            "64ff007d3b000007d0012656015c040a0000bc0000e56c570014686f73742e646f636b65722e"
          + "696e7465726e616c09000000000000972c0d0057000057000862626135333064655700000000"
          + "012cff00960000000000000000000000000000000000000000010000");

    @Test
    void shimRefusesALowerGeodeVersionAtTheHandshake() throws Exception {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, SHIM_PORT), 5000);
            socket.setSoTimeout(5000);
            try (OutputStream out = socket.getOutputStream(); InputStream in = socket.getInputStream()) {
                out.write(GEODE_1_14_HANDSHAKE);
                out.flush();

                int replyCode = in.read();
                assertEquals(REPLY_REFUSED & 0xff, replyCode,
                        "the shim must answer a lower-version handshake with Geode REPLY_REFUSED (60)");

                String message = new DataInputStream(in).readUTF();
                assertTrue(message.toLowerCase().contains("unsupported")
                                || message.contains("125") || message.contains("version"),
                        "the refusal carries a readable explanation: " + message);
            }
        }
    }

    private static void waitForReady(String url, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(1500);
                connection.setRequestMethod("GET");
                try {
                    if (connection.getResponseCode() == 200) {
                        return;
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {
                // retry
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for shim readiness");
            }
        }
        fail("shim did not become ready before timeout: " + url);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
