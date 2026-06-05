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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates entry TTL / expiration against the dedicated TTL shim instance (CB_TTL_SECONDS=3),
 * which applies a Couchbase document expiry to value writes. A value is readable immediately after
 * the put and gone after the TTL elapses (Couchbase expires it lazily on access).
 */
@Tag("integration")
class ProtoGemCouchTtlIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_TTL_SHIM_PORT", 40410);
    private static final int HEALTH_PORT = intEnv("IT_TTL_HEALTH_PORT", 8086);
    private static final int TTL_SECONDS = intEnv("IT_TTL_SECONDS", 3);

    private ClientCache cache;
    private Region<String, Object> region;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(HOST, SHIM_PORT)
                .create();
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("ttl" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void valueExpiresAfterTtl() throws InterruptedException {
        String key = "ttl-" + UUID.randomUUID();
        region.put(key, "v");

        assertEquals("v", region.get(key), "value is readable immediately after the put");

        // Wait past the TTL with margin; Couchbase expires the document lazily on access.
        Thread.sleep((TTL_SECONDS + 3L) * 1000L);

        assertNull(region.get(key), "value is gone after the TTL elapses");
        assertFalse(region.containsKeyOnServer(key), "key no longer present after expiry");
    }

    @Test
    void keysetEvictedAfterExpiry() throws InterruptedException {
        region.put("e1", "1");
        region.put("e2", "2");
        region.put("e3", "3");
        assertEquals(3, region.sizeOnServer());

        Thread.sleep((TTL_SECONDS + 3L) * 1000L);

        // keySet/size verify existence and prune expired keys from the keyset metadata.
        assertEquals(0, region.sizeOnServer(), "size reflects eviction of expired keys");
        assertTrue(region.keySetOnServer().isEmpty(), "keySet is pruned of expired keys");
    }

    @Test
    void perRegionOverrideKeepsEntriesLonger() throws InterruptedException {
        // The "ttlLong" region is configured (CB_TTL_REGIONS) to 30s, overriding the 3s default.
        Region<String, Object> longRegion = cache.<String, Object>createClientRegionFactory(
                ClientRegionShortcut.PROXY).create("ttlLong");
        String key = "k-" + UUID.randomUUID();
        longRegion.put(key, "v");

        // Wait past the default TTL (3s) but well under the per-region override (30s).
        Thread.sleep((TTL_SECONDS + 3L) * 1000L);

        assertEquals("v", longRegion.get(key),
                "per-region override keeps the entry past the default TTL");
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
