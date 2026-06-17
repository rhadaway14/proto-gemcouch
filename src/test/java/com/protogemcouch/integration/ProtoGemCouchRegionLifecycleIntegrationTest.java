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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end validation of region lifecycle over the wire: a real Geode client's
 * {@code Region.destroyRegion()} removes all of the region's data through the shim, and the region
 * re-materializes (the shim is schemaless) on the next write.
 */
@Tag("integration")
class ProtoGemCouchRegionLifecycleIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private ClientCache cache;
    private String regionName;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .setPoolReadTimeout(8000) // fail fast if the shim doesn't reply
                .addPoolServer(HOST, SHIM_PORT)
                .create();
        regionName = "rl" + UUID.randomUUID().toString().replace("-", "");
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    private Region<String, Object> proxyRegion() {
        return cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    @Test
    void destroyRegionRemovesAllDataAndRegionReMaterializes() {
        Region<String, Object> region = proxyRegion();
        region.put("k1", "v1");
        region.put("k2", "v2");
        assertEquals("v1", region.get("k1"));

        region.destroyRegion();

        // A fresh handle on the same name re-attaches to the (now empty) server region.
        Region<String, Object> reopened = proxyRegion();
        assertNull(reopened.get("k1"), "data should be gone after destroyRegion()");
        assertNull(reopened.get("k2"), "data should be gone after destroyRegion()");

        // The schemaless shim re-materializes the region on the next write.
        reopened.put("k3", "v3");
        assertEquals("v3", reopened.get("k3"), "region usable again after destroy + put");
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
                // retry until the deadline
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for shim readiness");
            }
        }
        fail("shim did not become ready at " + url + " within " + timeout);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
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
