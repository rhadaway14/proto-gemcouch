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
import java.util.HashSet;
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
 * 1.2.0-M2 gate: keyset-metadata SHARDING is correct. Runs against a shim configured with
 * {@code KEYSET_SHARDS=16} (the {@code protogemcouch-keysetshards} compose instance), so each region's
 * keyset is split across 16 docs keyed by {@code floorMod(key.hashCode(), 16)}. Asserts the same
 * exact-size/keySet guarantees as the single-doc path — concurrent puts are all reflected, removes and
 * clear update across shards — so sharding lifts the 20 MiB single-doc ceiling without changing behavior.
 */
@Tag("integration")
class ProtoGemCouchKeysetShardingIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_KEYSET_SHARDS_PORT", 40416);
    private static final int HEALTH_PORT = intEnv("IT_KEYSET_SHARDS_HEALTH_PORT", 8096);

    private static final int THREADS = 8;
    private static final int KEYS_PER_THREAD = 40; // 320 keys >> 16 shards, so every shard is exercised
    private static final int TOTAL = THREADS * KEYS_PER_THREAD;

    private ClientCache cache;
    private Region<String, Object> region;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        String regionName = "keysetShard" + UUID.randomUUID().toString().replace("-", "");
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
    void shardedKeysetIsExactUnderConcurrentPutsRemovesAndClear() throws Exception {
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
        start.countDown(); // release all threads at once for maximum cross-shard contention
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // All keys reflected across shards.
        assertEquals(TOTAL, region.sizeOnServer(),
                "sizeOnServer must count every concurrently-put key across all shards");
        Set<String> keys = region.keySetOnServer();
        assertEquals(TOTAL, keys.size(), "keySetOnServer must union every key across all shards");

        // Remove a spread of keys (likely landing in different shards) and re-check exactness.
        Set<String> removed = new HashSet<>();
        for (int t = 0; t < THREADS; t++) {
            String k = "k-" + t + "-0";
            region.remove(k);
            removed.add(k);
        }
        assertEquals(TOTAL - removed.size(), region.sizeOnServer(),
                "removes must shrink the count exactly across shards");
        Set<String> afterRemove = region.keySetOnServer();
        assertEquals(TOTAL - removed.size(), afterRemove.size());
        for (String r : removed) {
            assertTrue(!afterRemove.contains(r), "removed key must be gone from the sharded keyset: " + r);
        }

        // Clear must empty every shard.
        region.clear();
        assertEquals(0, region.sizeOnServer(), "clear must empty all keyset shards");
        assertEquals(0, region.keySetOnServer().size());
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
        fail("sharded shim did not become ready before timeout: " + url);
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
