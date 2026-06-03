package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end validation of the robustness work against a real Geode client, raw sockets, and the
 * Dockerized Couchbase backend:
 *
 * <ul>
 *   <li>Phase 1: an oversized/malformed frame is rejected and the connection closed.</li>
 *   <li>Phase 5: an abrupt client disconnect does not affect the server's health.</li>
 *   <li>Phase 2: a Couchbase outage surfaces as an operation error (visible in metrics), not a
 *       silent empty result.</li>
 * </ul>
 */
@Tag("integration")
class ProtoGemCouchFailureIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);
    private static final String COUCHBASE_CONTAINER = envOrDefault("IT_COUCHBASE_CONTAINER", "protogemcouch-couchbase");
    private static final String REGION = envOrDefault("IT_REGION", "helloWorld");

    private ClientCache cache;
    private Region<String, Object> region;

    @BeforeEach
    void setUp() {
        waitForShimReady(HOST, HEALTH_PORT, Duration.ofSeconds(90));

        cache = new ClientCacheFactory()
                .addPoolServer(HOST, SHIM_PORT)
                .setPoolSubscriptionEnabled(false)
                .set("log-level", "warn")
                .create();

        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(REGION);
    }

    @AfterEach
    void tearDown() {
        // Safety net: make sure the shared Couchbase container is never left paused.
        unpauseCouchbaseQuietly();
        if (cache != null) {
            cache.close();
        }
    }

    // ------------------------------------------------------------------
    // Phase 1: malformed/oversized frame handling
    // ------------------------------------------------------------------

    @Test
    void oversizedFrameIsRejectedAndConnectionClosed() throws Exception {
        long malformedBefore = metricValue("protogemcouch_malformed_frames_total");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, SHIM_PORT), 3000);
            socket.setSoTimeout(5000);

            completeHandshake(socket);

            // A frame header declaring a payload far larger than the configured cap.
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(0);                 // messageType
            out.writeInt(Integer.MAX_VALUE); // payloadLength -> over the frame-size limit
            out.writeInt(1);                 // numberOfParts
            out.writeInt(1);                 // transactionId
            out.writeByte(0);                // flags
            out.flush();

            // The server must reject the frame and close the connection (read returns EOF).
            assertTrue(readUntilEof(socket.getInputStream(), 5000),
                    "Server should close the connection after an oversized frame");
        }

        long malformedAfter = waitForMetricAtLeast("protogemcouch_malformed_frames_total", malformedBefore + 1, 10);
        assertTrue(malformedAfter >= malformedBefore + 1,
                "malformed-frame metric should increment (before=" + malformedBefore + ", after=" + malformedAfter + ")");
    }

    // ------------------------------------------------------------------
    // Phase 5: resilience to abrupt client disconnect
    // ------------------------------------------------------------------

    @Test
    void abruptClientDisconnectDoesNotAffectServer() throws Exception {
        // A client that handshakes, sends a partial frame, then vanishes.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, SHIM_PORT), 3000);
            socket.setSoTimeout(5000);
            completeHandshake(socket);

            // Partial frame header (fewer than the 17 header bytes), then abrupt close.
            socket.getOutputStream().write(new byte[] {0, 0, 0, 5, 0});
            socket.getOutputStream().flush();
        } // socket closed abruptly

        // The shim must remain healthy and continue accepting new connections.
        assertEquals(200, httpStatus("http://" + HOST + ":" + HEALTH_PORT + "/ready"),
                "Shim should stay ready after an abrupt client disconnect");

        try (Socket fresh = new Socket()) {
            fresh.connect(new InetSocketAddress(HOST, SHIM_PORT), 3000);
            fresh.setSoTimeout(5000);
            completeHandshake(fresh); // a new client can still handshake
        }
    }

    // ------------------------------------------------------------------
    // Phase 2: backend outage surfaces as an error, not a silent miss
    // ------------------------------------------------------------------

    @Test
    void couchbaseOutageSurfacesAsErrorNotSilentMiss() throws Exception {
        Assumptions.assumeTrue(dockerAvailable(), "Docker CLI required");
        Assumptions.assumeTrue(containerRunning(COUCHBASE_CONTAINER),
                () -> "Couchbase container '" + COUCHBASE_CONTAINER + "' must be running");

        String key = "outage-" + UUID.randomUUID();
        region.put(key, "before-outage");

        long errorsBefore = metricValue("protogemcouch_request_errors_total");

        pauseCouchbase();
        boolean returnedStaleValueSilently = false;
        try {
            // With Couchbase unreachable, the operation must fail (and be recorded), not return a
            // benign value as if the cache simply had no data.
            try {
                Object value = region.get(key);
                returnedStaleValueSilently = "before-outage".equals(value);
            } catch (Exception expected) {
                // Failing loudly is the correct behavior.
            }

            long errorsAfter = waitForMetricAtLeast("protogemcouch_request_errors_total", errorsBefore + 1, 20);
            assertTrue(errorsAfter >= errorsBefore + 1,
                    "Backend outage should be recorded as an operation error "
                            + "(before=" + errorsBefore + ", after=" + errorsAfter + ")");
            assertTrue(!returnedStaleValueSilently,
                    "Outage must not be masked as a normal successful read");
        } finally {
            unpauseCouchbaseQuietly();
        }

        // After recovery, operations should succeed again without restarting the shim.
        waitForShimReady(HOST, HEALTH_PORT, Duration.ofSeconds(30));
        boolean recovered = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                region.put("recovery-" + UUID.randomUUID(), "ok");
                recovered = true;
                break;
            } catch (Exception retry) {
                sleep(Duration.ofSeconds(2));
            }
        }
        assertTrue(recovered, "Shim should recover and serve writes after Couchbase returns");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Send a dummy handshake and consume the shim's fixed handshake reply. */
    private static void completeHandshake(Socket socket) throws IOException {
        // The shim replies with a canned handshake to the first inbound message regardless of
        // content, then switches the connection into frame-decoding mode.
        socket.getOutputStream().write(new byte[] {1, 0, 0, 0, 0, 0, 0, 0});
        socket.getOutputStream().flush();

        InputStream in = socket.getInputStream();
        int first = in.read();
        if (first < 0) {
            throw new IOException("Shim closed connection during handshake");
        }
        // Drain whatever else of the handshake reply is immediately available.
        int available = in.available();
        if (available > 0) {
            in.readNBytes(available);
        }
    }

    private static boolean readUntilEof(InputStream in, int timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (System.nanoTime() < deadline) {
                int b = in.read();
                if (b < 0) {
                    return true;
                }
            }
        } catch (IOException e) {
            // A reset/closed socket is also an acceptable "server closed" signal.
            return true;
        }
        return false;
    }

    private static long metricValue(String metricName) {
        String body = httpBody("http://" + HOST + ":" + HEALTH_PORT + "/metrics");
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(metricName + " ")) {
                String[] parts = trimmed.split("\\s+");
                try {
                    return (long) Double.parseDouble(parts[parts.length - 1]);
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    private static long waitForMetricAtLeast(String metricName, long target, int seconds) {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        long latest = metricValue(metricName);
        while (latest < target && System.nanoTime() < deadline) {
            sleep(Duration.ofMillis(500));
            latest = metricValue(metricName);
        }
        return latest;
    }

    private static void pauseCouchbase() {
        runDocker(30, "pause", COUCHBASE_CONTAINER);
    }

    private static void unpauseCouchbaseQuietly() {
        try {
            runDocker(30, "unpause", COUCHBASE_CONTAINER);
        } catch (RuntimeException ignored) {
            // Already running, or container absent; nothing to do.
        }
    }

    private static boolean dockerAvailable() {
        try {
            return runDockerExitCode(10, "version") == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean containerRunning(String name) {
        try {
            String out = runDocker(10, "inspect", "-f", "{{.State.Running}}", name);
            return out.trim().equals("true");
        } catch (RuntimeException e) {
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
            if (process.exitValue() != 0) {
                throw new IllegalStateException("docker " + String.join(" ", args)
                        + " failed (exit " + process.exitValue() + "): " + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run docker " + String.join(" ", args), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running docker " + String.join(" ", args), e);
        }
    }

    private static int runDockerExitCode(int timeoutSeconds, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "docker";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static void waitForShimReady(String host, int healthPort, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String url = "http://" + host + ":" + healthPort + "/ready";
        while (System.nanoTime() < deadline) {
            try {
                if (httpStatus(url) == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // retry
            }
            sleep(Duration.ofMillis(500));
        }
        fail("Shim did not become ready before timeout: " + url);
    }

    private static int httpStatus(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(1500);
        connection.setRequestMethod("GET");
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static String httpBody(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setRequestMethod("GET");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().reduce("", (a, b) -> a + "\n" + b);
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            return "";
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", e);
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
