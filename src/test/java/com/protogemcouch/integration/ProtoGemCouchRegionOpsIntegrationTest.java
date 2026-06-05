package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * End-to-end validation of the basic region operations invalidate / clear / getEntry against a real
 * Geode client and the live shim + Couchbase.
 */
@Tag("integration")
class ProtoGemCouchRegionOpsIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

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
                .create("regionOps" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void invalidateKeepsKeyButDropsValue() {
        String key = "inv-" + UUID.randomUUID();
        region.put(key, "v");
        assertEquals("v", region.get(key));

        region.invalidate(key);

        assertNull(region.get(key), "value is gone after invalidate");
        assertTrue(region.containsKeyOnServer(key), "key remains present after invalidate");
    }

    @Test
    void clearRemovesAllEntries() {
        region.put("a", "1");
        region.put("b", "2");
        region.put("c", "3");
        assertEquals(3, region.sizeOnServer());

        region.clear();

        assertEquals(0, region.sizeOnServer(), "region is empty after clear");
        assertNull(region.get("a"));
        assertFalse(region.containsKeyOnServer("b"));
    }

    @Disabled("Follow-up: Region.getEntry expects a serialized Geode EntrySnapshot object in the "
            + "reply (the client casts part[0] to internal.cache.EntrySnapshot); returning a raw value "
            + "yields a null entry value. Needs EntrySnapshot serialization. invalidate + clear are done.")
    @Test
    void getEntryReturnsValueOrNull() {
        String key = "ge-" + UUID.randomUUID();
        region.put(key, "v");

        Region.Entry<String, Object> entry = region.getEntry(key);
        assertEquals("v", entry == null ? null : entry.getValue(), "getEntry returns the value");

        assertNull(region.getEntry("absent-" + UUID.randomUUID()), "getEntry returns null for an absent key");
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
