package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the keyset-metadata update is concurrency-safe.
 *
 * <p>{@code size}/{@code keySet} are backed by a single per-region metadata document maintained by
 * read-modify-write. A plain read-then-upsert loses updates under concurrent writers. This test
 * hammers a fresh region with many concurrent puts and asserts every key is reflected in
 * {@code sizeOnServer()} / {@code keySetOnServer()} — which only holds if the update is done with
 * compare-and-swap retries.
 */
@Tag("integration")
class ProtoGemCouchKeysetConcurrencyIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private static final int THREADS = 8;
    private static final int KEYS_PER_THREAD = 15;
    private static final int TOTAL = THREADS * KEYS_PER_THREAD;

    private ClientCache cache;
    private Region<String, Object> region;
    private String regionName;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));

        // A unique region so its keyset-metadata document is isolated from other tests.
        regionName = "keysetConc" + UUID.randomUUID().toString().replace("-", "");
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(HOST, SHIM_PORT)
                .create();
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void concurrentPutsAreAllReflectedInKeySetAndSize() throws Exception {
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

        start.countDown(); // release all threads at once for maximum contention
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        int size = region.sizeOnServer();
        Set<String> keys = region.keySetOnServer();

        assertEquals(TOTAL, size,
                "sizeOnServer should count every concurrently-put key (no lost keyset updates)");
        assertEquals(TOTAL, keys.size(),
                "keySetOnServer should contain every concurrently-put key");
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
