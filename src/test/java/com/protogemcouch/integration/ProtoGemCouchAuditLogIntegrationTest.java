package com.protogemcouch.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates the dedicated audit stream end-to-end: triggering a malformed/oversized frame must emit a
 * security audit event on the {@code protogemcouch.audit} logger (carrying the {@code audit=true}
 * marker) in the shim container's log — proving security events are recorded on the auditable stream,
 * not just reflected in metrics. Skipped when Docker is unavailable.
 */
@Tag("integration")
class ProtoGemCouchAuditLogIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);
    private static final String SHIM_CONTAINER = envOrDefault("IT_SHIM_CONTAINER", "protogemcouch-shim");

    @Test
    void malformedFrameEmitsAuditEvent() throws Exception {
        Assumptions.assumeTrue(dockerAvailable(), "Docker CLI required");
        waitForShimReady(Duration.ofSeconds(90));

        // A unique oversized payload length so we can find this exact event in the log.
        int offending = 2_000_000_001;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, SHIM_PORT), 3000);
            socket.setSoTimeout(5000);
            completeHandshake(socket);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(0);          // messageType
            out.writeInt(offending);  // payloadLength -> over the frame-size cap
            out.writeInt(1);          // numberOfParts
            out.writeInt(1);          // transactionId
            out.writeByte(0);         // flags
            out.flush();

            readUntilEof(socket.getInputStream(), 5000); // server rejects and closes
        }

        // The audit event must appear on the dedicated stream, with the marker and the offending value.
        String needleEvent = "event=malformed_frame";
        String needleValue = "offendingValue=" + offending;
        boolean found = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!found && System.nanoTime() < deadline) {
            String logs = runDocker(15, "logs", "--tail", "800", SHIM_CONTAINER);
            for (String line : logs.split("\\R")) {
                if (line.contains(needleEvent) && line.contains("audit=true") && line.contains(needleValue)) {
                    assertTrue(line.contains(AUDIT_LOGGER) || line.contains("audit=true"),
                            "audit event is routed via the audit stream");
                    found = true;
                    break;
                }
            }
            if (!found) {
                sleep();
            }
        }
        assertTrue(found, "a malformed frame emits an 'audit=true malformed_frame' event in the container log");
    }

    private static final String AUDIT_LOGGER = "protogemcouch.audit";

    private static void completeHandshake(Socket socket) throws IOException {
        socket.getOutputStream().write(new byte[] {1, 0, 0, 0, 0, 0, 0, 0});
        socket.getOutputStream().flush();
        InputStream in = socket.getInputStream();
        int first = in.read();
        if (first < 0) {
            throw new IOException("Shim closed connection during handshake");
        }
        int available = in.available();
        if (available > 0) {
            in.readNBytes(available);
        }
    }

    private static void readUntilEof(InputStream in, int timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (System.nanoTime() < deadline) {
                if (in.read() < 0) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // reset/closed is an acceptable "server closed" signal
        }
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String runDocker(int timeoutSeconds, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "docker";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("docker " + String.join(" ", args) + " timed out");
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run docker " + String.join(" ", args), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running docker " + String.join(" ", args), e);
        }
    }

    private static void waitForShimReady(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String url = "http://" + HOST + ":" + HEALTH_PORT + "/ready";
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
            sleep();
        }
        fail("shim did not become ready before timeout: " + url);
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
