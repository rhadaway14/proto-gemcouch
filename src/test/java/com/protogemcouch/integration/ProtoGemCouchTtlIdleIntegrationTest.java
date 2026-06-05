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
 * Validates idle-mode TTL (entry-idle-time) against the idle shim (CB_TTL_MODE=idle, CB_TTL_SECONDS=4):
 * reads refresh the expiry (get-and-touch), so an entry accessed faster than the TTL stays alive past
 * the TTL, then expires once access stops.
 */
@Tag("integration")
class ProtoGemCouchTtlIdleIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_TTL_IDLE_SHIM_PORT", 40411);
    private static final int HEALTH_PORT = intEnv("IT_TTL_IDLE_HEALTH_PORT", 8087);
    private static final int TTL_SECONDS = intEnv("IT_TTL_IDLE_SECONDS", 4);

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
                .create("ttlIdle" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void readsRefreshExpiryThenEntryExpiresWhenIdle() throws InterruptedException {
        String key = "idle-" + UUID.randomUUID();
        region.put(key, "v");

        long step = (TTL_SECONDS - 1L) * 1000L; // access faster than the TTL to keep it alive
        region.get(key);
        Thread.sleep(step);
        region.get(key);
        Thread.sleep(step);

        // Total elapsed now exceeds the TTL, but each read refreshed the expiry, so it survives.
        assertEquals("v", region.get(key), "idle reads keep the entry alive past the TTL");

        // Stop accessing; after the TTL with margin it expires.
        Thread.sleep((TTL_SECONDS + 3L) * 1000L);
        assertNull(region.get(key), "entry expires once reads stop");
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
