package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates correctness across two shim replicas sharing one Couchbase (horizontal scaling).
 *
 * <p>A Geode client pool spans both replicas (ports 40405 and 40409). Concurrent puts are
 * distributed across them; the test asserts that {@code sizeOnServer()}/{@code keySetOnServer()}
 * reflect every key — which requires the keyset-metadata update to be safe across separate shim
 * processes (cross-process CAS) — and that both replicas actually served traffic, so the test
 * genuinely exercises the multi-replica path.
 */
@Tag("integration")
class ProtoGemCouchMultiReplicaIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_A_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_A_PORT = intEnv("IT_HEALTH_PORT", 8081);
    private static final int SHIM_B_PORT = intEnv("IT_REPLICA_SHIM_PORT", 40409);
    private static final int HEALTH_B_PORT = intEnv("IT_REPLICA_HEALTH_PORT", 8085);

    private static final int THREADS = 8;
    private static final int KEYS_PER_THREAD = 15;
    private static final int TOTAL = THREADS * KEYS_PER_THREAD;

    private ClientCache cache;
    private Region<String, Object> region;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_A_PORT + "/ready", Duration.ofSeconds(90));
        waitForReady("http://" + HOST + ":" + HEALTH_B_PORT + "/ready", Duration.ofSeconds(90));

        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(HOST, SHIM_A_PORT)
                .addPoolServer(HOST, SHIM_B_PORT)
                .create();
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("multiReplica" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void concurrentPutsAcrossReplicasKeepKeySetConsistent() throws Exception {
        long opsBeforeA = operationRequests(HEALTH_A_PORT);
        long opsBeforeB = operationRequests(HEALTH_B_PORT);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        Future<?>[] futures = new Future<?>[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            futures[t] = pool.submit(() -> {
                start.await();
                for (int i = 0; i < KEYS_PER_THREAD; i++) {
                    region.put("k-" + threadId + "-" + i, "v");
                }
                return null;
            });
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        int size = region.sizeOnServer();
        Set<String> keys = region.keySetOnServer();
        assertEquals(TOTAL, size,
                "sizeOnServer should count every key written across both replicas (cross-process CAS)");
        assertEquals(TOTAL, keys.size(), "keySetOnServer should contain every key");

        // Confirm the pool actually distributed work across both replicas, so this really tested
        // the multi-replica path rather than hitting a single instance.
        long opsAfterA = operationRequests(HEALTH_A_PORT);
        long opsAfterB = operationRequests(HEALTH_B_PORT);
        assertTrue(opsAfterA > opsBeforeA, "replica A should have served operations");
        assertTrue(opsAfterB > opsBeforeB, "replica B should have served operations");
    }

    private static long operationRequests(int healthPort) {
        String body = httpBody("http://" + HOST + ":" + healthPort + "/metrics");
        long sum = 0;
        for (String line : body.split("\\R")) {
            if (line.startsWith("protogemcouch_operation_requests_total{")) {
                String[] parts = line.trim().split("\\s+");
                try {
                    sum += (long) Double.parseDouble(parts[parts.length - 1]);
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return sum;
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
        } catch (Exception e) {
            return "";
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
