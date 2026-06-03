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
 * End-to-end validation of the CAS-backed atomic operations against a real Geode client and the
 * live shim + Couchbase. Asserts the exact {@code Region} contract Geode clients rely on:
 *
 * <ul>
 *   <li>{@code putIfAbsent} stores only when absent and returns the prior value (null when stored);</li>
 *   <li>{@code replace(k,v)} replaces only when present and returns the prior value (null when absent);</li>
 *   <li>{@code replace(k,old,new)} replaces only on an exact match and returns the boolean outcome;</li>
 *   <li>{@code remove(k,v)} removes only on an exact match and returns the boolean outcome.</li>
 * </ul>
 *
 * <p>This is the authoritative validation for the wire-protocol decode and reply format (per the
 * project's "validate against the real client" rule).
 */
@Tag("integration")
class ProtoGemCouchAtomicOpsIntegrationTest {

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
                .create("atomicOps" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void putIfAbsentStoresOnlyWhenAbsent() {
        String key = "pia-" + UUID.randomUUID();

        assertNull(region.putIfAbsent(key, "first"), "putIfAbsent on absent key returns null");
        assertEquals("first", region.get(key));

        Object prior = region.putIfAbsent(key, "second");
        assertEquals("first", prior, "putIfAbsent on present key returns the existing value");
        assertEquals("first", region.get(key), "putIfAbsent must not overwrite an existing value");
    }

    @Test
    void replaceOnlyWhenPresent() {
        String key = "rep-" + UUID.randomUUID();

        assertNull(region.replace(key, "x"), "replace on absent key returns null");
        assertNull(region.get(key), "replace must not create the key when absent");

        region.put(key, "old");
        assertEquals("old", region.replace(key, "new"), "replace returns the prior value");
        assertEquals("new", region.get(key));
    }

    @Test
    void compareAndReplace() {
        String key = "car-" + UUID.randomUUID();
        region.put(key, "v1");

        assertFalse(region.replace(key, "wrong", "v2"), "no replace when current != expected");
        assertEquals("v1", region.get(key));

        assertTrue(region.replace(key, "v1", "v2"), "replace when current == expected");
        assertEquals("v2", region.get(key));
    }

    @Test
    void compareAndRemove() {
        String key = "crm-" + UUID.randomUUID();
        region.put(key, "v1");

        assertFalse(region.remove(key, "wrong"), "no remove when current != expected");
        assertTrue(region.containsKeyOnServer(key));

        assertTrue(region.remove(key, "v1"), "remove when current == expected");
        assertFalse(region.containsKeyOnServer(key), "key gone after a matching remove(k,v)");
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
