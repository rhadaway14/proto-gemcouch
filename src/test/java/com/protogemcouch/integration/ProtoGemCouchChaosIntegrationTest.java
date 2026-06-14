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
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Chaos / resilience gates: drive the shim through real backend and shim outages (full container
 * stop/start, not just a pause) and assert the robustness contract holds.
 *
 * <ul>
 *   <li><b>Couchbase hard outage under load:</b> while Couchbase is down, concurrent writes fail
 *       promptly and cleanly (recorded as errors, never hanging), and after Couchbase returns the shim
 *       recovers on its own. Crucially, the region's keyset/size end up reflecting <em>exactly</em> the
 *       writes that were acknowledged — a write that failed during the outage never leaves a phantom
 *       key (the value upsert precedes the keyset update, so a failed value write never updates the
 *       keyset).</li>
 *   <li><b>Shim restart mid-flight:</b> the shim is stateless, so restarting its container loses no
 *       data — previously-acknowledged entries are still readable (they live in Couchbase) and the
 *       client reconnects and resumes writing.</li>
 * </ul>
 *
 * <p>Each test fully restores both containers in a {@code finally}/{@code @AfterEach} so later test
 * classes (which share this Couchbase and shim) are unaffected. Skipped when Docker is unavailable.
 */
@Tag("integration")
class ProtoGemCouchChaosIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);
    private static final String COUCHBASE_CONTAINER =
            envOrDefault("IT_COUCHBASE_CONTAINER", "protogemcouch-couchbase");
    private static final String SHIM_CONTAINER = envOrDefault("IT_SHIM_CONTAINER", "protogemcouch-shim");

    private ClientCache cache;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker CLI required for chaos tests");
        Assumptions.assumeTrue(containerRunning(COUCHBASE_CONTAINER),
                () -> "Couchbase container '" + COUCHBASE_CONTAINER + "' must be running");
        Assumptions.assumeTrue(containerRunning(SHIM_CONTAINER),
                () -> "Shim container '" + SHIM_CONTAINER + "' must be running");

        waitForShimReady(Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .addPoolServer(HOST, SHIM_PORT)
                .setPoolSubscriptionEnabled(false)
                // Keep retries bounded so an op during the outage fails promptly instead of hanging.
                .setPoolReadTimeout(8000)
                .setPoolRetryAttempts(1)
                .set("log-level", "warn")
                .create();
    }

    @AfterEach
    void tearDown() {
        // Safety net: never leave a container down for the next test class.
        startContainerQuietly(COUCHBASE_CONTAINER);
        startContainerQuietly(SHIM_CONTAINER);
        waitCouchbaseHealthy(Duration.ofSeconds(120));
        try {
            waitForShimReady(Duration.ofSeconds(90));
        } catch (AssertionError ignored) {
            // best effort; the assumption guards in the next test will skip if still down
        }
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void couchbaseHardOutageUnderLoadFailsCleanlyThenRecoversWithConsistentKeyset() throws Exception {
        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("chaos" + UUID.randomUUID().toString().replace("-", ""));

        Set<String> acknowledged = new HashSet<>();

        // Phase A — seed entries that succeed before any chaos.
        for (int i = 0; i < 15; i++) {
            String key = "pre-" + i;
            region.put(key, "v" + i);
            acknowledged.add(key);
        }

        long errorsBefore = metricValue("protogemcouch_request_errors_total");

        // Phase B — Couchbase goes down hard. A burst of concurrent writes must all fail promptly
        // (no hang) and none must be acknowledged.
        stopContainer(COUCHBASE_CONTAINER);
        try {
            int burst = 6;
            ExecutorService pool = Executors.newFixedThreadPool(burst);
            try {
                List<Future<Boolean>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < burst; i++) {
                    String key = "during-" + i;
                    futures.add(pool.submit((Callable<Boolean>) () -> {
                        try {
                            region.put(key, "should-not-persist");
                            return Boolean.TRUE;   // unexpected success
                        } catch (Exception expected) {
                            return Boolean.FALSE;  // failing loudly is correct
                        }
                    }));
                }
                for (Future<Boolean> f : futures) {
                    Boolean succeeded;
                    try {
                        // The op must return/throw within a bounded time — proving it does not hang
                        // indefinitely while the backend is unreachable.
                        succeeded = f.get(30, TimeUnit.SECONDS);
                    } catch (TimeoutException hang) {
                        fail("a write hung for >30s while Couchbase was down (no bounded failure)");
                        return;
                    } catch (ExecutionException e) {
                        succeeded = Boolean.FALSE;
                    }
                    assertTrue(!Boolean.TRUE.equals(succeeded),
                            "a write must not silently succeed while Couchbase is down");
                }
            } finally {
                pool.shutdownNow();
            }

            long errorsAfter = waitForMetricAtLeast("protogemcouch_request_errors_total", errorsBefore + 1, 20);
            assertTrue(errorsAfter >= errorsBefore + 1,
                    "the backend outage is recorded as operation errors (before=" + errorsBefore
                            + ", after=" + errorsAfter + ")");

            // The shim process itself stays alive during the outage (liveness): /metrics still serves.
            assertTrue(!httpBody("http://" + HOST + ":" + HEALTH_PORT + "/metrics").isBlank(),
                    "the shim keeps serving its metrics endpoint during a backend outage");
        } finally {
            // Phase C — Couchbase returns; the shim must recover without being restarted.
            startContainer(COUCHBASE_CONTAINER);
            waitCouchbaseHealthy(Duration.ofSeconds(120));
            waitForShimReady(Duration.ofSeconds(90));
        }

        // Phase D — writes succeed again (retry briefly to absorb SDK reconnection).
        for (int i = 0; i < 15; i++) {
            String key = "post-" + i;
            putWithRetry(region, key, "v" + i, Duration.ofSeconds(60));
            acknowledged.add(key);
        }

        // Phase E — keyset/size reflect exactly the acknowledged writes: every pre-/post- key, and no
        // phantom "during-" key from a write that failed mid-outage.
        Set<Object> serverKeys = new HashSet<>(region.keySetOnServer());
        assertEquals(acknowledged, serverKeys,
                "keyset equals exactly the acknowledged writes (no phantom keys from failed writes)");
        assertEquals(acknowledged.size(), region.sizeOnServer(),
                "size equals the acknowledged write count");
    }

    @Test
    void shimRestartPreservesDataAndResumesService() throws Exception {
        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("chaos" + UUID.randomUUID().toString().replace("-", ""));

        for (int i = 0; i < 10; i++) {
            region.put("k" + i, "v" + i);
        }

        // Restart the (stateless) shim container; its in-memory state is discarded but the data lives
        // in Couchbase.
        restartContainer(SHIM_CONTAINER);
        waitForShimReady(Duration.ofSeconds(120));

        // The client reconnects (retry the first read to absorb the dropped connection), and every
        // previously-acknowledged entry is still present.
        for (int i = 0; i < 10; i++) {
            Object value = getWithRetry(region, "k" + i, Duration.ofSeconds(60));
            assertEquals("v" + i, value, "entry survives a shim restart (data lives in Couchbase)");
        }

        // New writes work after the restart.
        putWithRetry(region, "after-restart", "ok", Duration.ofSeconds(30));
        assertEquals("ok", getWithRetry(region, "after-restart", Duration.ofSeconds(10)));
    }

    // ------------------------------------------------------------------ helpers

    private static void putWithRetry(Region<String, Object> region, String key, Object value, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                region.put(key, value);
                return;
            } catch (RuntimeException e) {
                last = e;
                sleep(Duration.ofSeconds(2));
            }
        }
        throw new AssertionError("put did not succeed within " + timeout, last);
    }

    private static Object getWithRetry(Region<String, Object> region, String key, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return region.get(key);
            } catch (RuntimeException e) {
                last = e;
                sleep(Duration.ofSeconds(2));
            }
        }
        throw new AssertionError("get did not succeed within " + timeout, last);
    }

    private static void stopContainer(String name) {
        runDocker(60, "stop", name);
    }

    private static void startContainer(String name) {
        runDocker(60, "start", name);
    }

    private static void restartContainer(String name) {
        runDocker(60, "restart", name);
    }

    private static void startContainerQuietly(String name) {
        try {
            runDocker(60, "start", name);
        } catch (RuntimeException ignored) {
            // already running or absent
        }
    }

    private static void waitCouchbaseHealthy(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                String status = runDocker(10, "inspect", "-f", "{{.State.Health.Status}}", COUCHBASE_CONTAINER).trim();
                if ("healthy".equals(status)) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // retry
            }
            sleep(Duration.ofSeconds(2));
        }
        // Don't hard-fail here: the per-test recovery waits (waitForShimReady / putWithRetry) will
        // surface a genuine failure to recover with a clearer message.
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

    private void waitForShimReady(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String url = "http://" + HOST + ":" + HEALTH_PORT + "/ready";
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
