package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.client.ServerOperationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates the Phase 3 graceful error response against a real Geode client.
 *
 * <p>Requires the shim to run with {@code ERROR_RESPONSE_MODE=exception} (set in
 * {@code docker-compose.yml}). When a backend operation fails, the shim should reply with a Geode
 * EXCEPTION frame; the client must deserialize it into a {@link ServerOperationException} (rather
 * than seeing a dropped socket / {@code ServerConnectivityException}), and the connection should
 * stay open so further requests work without reconnecting.
 */
@Tag("integration")
class ProtoGemCouchExceptionResponseIntegrationTest {

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
        unpauseCouchbaseQuietly();
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void backendFailureIsDeliveredAsAServerOperationException() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker CLI required");
        Assumptions.assumeTrue(containerRunning(COUCHBASE_CONTAINER),
                () -> "Couchbase container '" + COUCHBASE_CONTAINER + "' must be running");

        String key = "exc-" + UUID.randomUUID();
        region.put(key, "value");

        pauseCouchbase();
        try {
            Throwable thrown = catchThrowable(() -> region.get(key));

            // The client must have received and parsed a server EXCEPTION frame, not a dropped socket.
            assertTrue(thrown != null, "get should fail while the backend is unavailable");
            assertTrue(hasCause(thrown, ServerOperationException.class),
                    "Client should receive a Geode ServerOperationException (graceful EXCEPTION frame), "
                            + "but got: " + describe(thrown));
            assertTrue(chainMentions(thrown, "get failed") || chainMentions(thrown, "RepositoryException"),
                    "The server-side failure detail should propagate to the client: " + describe(thrown));

            // The connection should remain usable: a second request still gets a structured server
            // error (still failing because the backend is down), not a broken pipe.
            Throwable second = catchThrowable(() -> region.get(key));
            assertTrue(hasCause(second, ServerOperationException.class),
                    "Connection should stay open and keep returning structured errors: " + describe(second));
        } finally {
            unpauseCouchbaseQuietly();
        }

        // Recovery after the backend returns.
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
        assertTrue(recovered, "Shim should recover after Couchbase returns");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Throwable catchThrowable(ThrowingRunnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static boolean chainMentions(Throwable thrown, String needle) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(needle)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String describe(Throwable thrown) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" | ");
            if (t.getCause() == t) {
                break;
            }
        }
        return sb.toString();
    }

    private static void pauseCouchbase() {
        runDocker(30, "pause", COUCHBASE_CONTAINER);
    }

    private static void unpauseCouchbaseQuietly() {
        try {
            runDocker(30, "unpause", COUCHBASE_CONTAINER);
        } catch (RuntimeException ignored) {
            // already running or absent
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

    private static boolean containerRunning(String name) {
        try {
            return runDocker(10, "inspect", "-f", "{{.State.Running}}", name).trim().equals("true");
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

    private static void waitForShimReady(String host, int healthPort, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String url = "http://" + host + ":" + healthPort + "/ready";
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
            sleep(Duration.ofMillis(500));
        }
        fail("Shim did not become ready before timeout: " + url);
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
