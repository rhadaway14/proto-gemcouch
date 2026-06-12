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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates the max-value-size guard against the dedicated low-limit shim instance
 * ({@code CB_MAX_VALUE_BYTES=4096}). A value whose encoded document exceeds the limit is rejected with
 * a clean server error <em>before</em> it reaches Couchbase, leaving the region's size and keyset
 * untouched; under-limit values still store normally, and a {@code putAll} batch rejects only the
 * oversized entries (the rest persist).
 */
@Tag("integration")
class ProtoGemCouchLargeValueIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_MAXVALUE_SHIM_PORT", 40412);
    private static final int HEALTH_PORT = intEnv("IT_MAXVALUE_HEALTH_PORT", 8088);
    private static final int LIMIT_BYTES = intEnv("IT_MAXVALUE_LIMIT_BYTES", 4096);

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
                .create("maxval" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void oversizedValueIsRejectedCleanlyAndLeavesSizeAndKeysetUntouched() {
        region.put("ok", "small-value");

        String oversized = "x".repeat(LIMIT_BYTES + 1000);
        Exception thrown = assertThrows(Exception.class, () -> region.put("big", oversized),
                "an oversized value must be rejected with a server error, not silently stored");
        assertTrue(mentionsLimit(thrown), "the rejection explains the size limit (got: " + describe(thrown) + ")");

        // The oversized value never persisted and never entered the keyset; the prior entry is intact.
        assertNull(region.get("big"), "the oversized value was not stored");
        assertEquals("small-value", region.get("ok"), "the under-limit value is unaffected");
        assertEquals(1, region.sizeOnServer(), "size counts only the under-limit value");
        assertTrue(region.keySetOnServer().contains("ok"));
        assertFalse(region.keySetOnServer().contains("big"), "the rejected key never entered the keyset");
    }

    @Test
    void underLimitValueStoresNormally() {
        String key = "k-" + UUID.randomUUID();
        // Comfortably under the limit, including the JSON wrapper overhead.
        String value = "y".repeat(LIMIT_BYTES / 2);
        region.put(key, value);
        assertEquals(value, region.get(key), "an under-limit value round-trips normally");
    }

    @Test
    void putAllRejectsOnlyTheOversizedEntries() {
        Map<String, Object> batch = new HashMap<>();
        batch.put("small1", "a");
        batch.put("small2", "b");
        batch.put("toobig", "z".repeat(LIMIT_BYTES + 1000));

        // A partial failure surfaces as a server error (the failed key is named), but the under-limit
        // entries still persist (per-key putAll semantics).
        assertThrows(Exception.class, () -> region.putAll(batch),
                "a putAll containing an oversized value surfaces a partial-failure error");

        assertEquals("a", region.get("small1"), "under-limit entry persisted");
        assertEquals("b", region.get("small2"), "under-limit entry persisted");
        assertNull(region.get("toobig"), "the oversized entry was not stored");
        assertFalse(region.keySetOnServer().contains("toobig"), "the oversized key never entered the keyset");
    }

    private static boolean mentionsLimit(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && (m.contains("maximum") || m.contains("exceeds") || m.contains("CB_MAX_VALUE_BYTES"))) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
        }
        return sb.toString();
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
