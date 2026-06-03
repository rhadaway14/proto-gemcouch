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
 * End-to-end validation of the CAS-backed atomic operations against a real Geode client and the
 * live shim + Couchbase.
 *
 * <p>The active tests assert the <strong>storage</strong> contract, which the operation-decode +
 * repository CAS routing now satisfy: {@code putIfAbsent} does not overwrite, {@code replace} does
 * not create, and the compare-and-replace only writes on an exact match. Storage state is observed
 * via server-side reads, so it reflects what is actually persisted in Couchbase.
 *
 * <p>{@link #fullGeodeReturnSemantics()} is disabled: the value the client <em>returns</em> from
 * {@code putIfAbsent}/{@code replace(old,new)} and {@code remove(k,v)} requires building Geode's
 * old-value / boolean PUT reply and entry-not-found DESTROY reply (and a shared value decoder for
 * the DESTROY expected-value part). That is the documented follow-up.
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

        region.putIfAbsent(key, "first");
        assertEquals("first", region.get(key), "putIfAbsent stores when the key is absent");

        region.putIfAbsent(key, "second");
        assertEquals("first", region.get(key), "putIfAbsent must not overwrite an existing value");
    }

    @Test
    void replaceDoesNotCreateWhenAbsent() {
        String key = "rep-" + UUID.randomUUID();

        region.replace(key, "x");
        assertNull(region.get(key), "replace must not create the key when absent");
    }

    @Test
    void replaceUpdatesWhenPresent() {
        String key = "rep-" + UUID.randomUUID();

        region.put(key, "old");
        region.replace(key, "new");
        assertEquals("new", region.get(key), "replace updates an existing value");
    }

    @Test
    void compareAndReplaceWritesOnlyOnMatch() {
        String key = "car-" + UUID.randomUUID();
        region.put(key, "v1");

        region.replace(key, "wrong", "v2");
        assertEquals("v1", region.get(key), "compare-replace must not write on a mismatch");

        region.replace(key, "v1", "v2");
        assertEquals("v2", region.get(key), "compare-replace writes on an exact match");
    }

    @Test
    void putSideReturnValues() {
        String key = "sem-" + UUID.randomUUID();

        assertNull(region.putIfAbsent(key, "first"), "putIfAbsent on absent returns null");
        assertEquals("first", region.putIfAbsent(key, "second"), "putIfAbsent on present returns existing");

        assertEquals("first", region.replace(key, "third"), "replace returns the prior value");
        assertNull(region.replace("absent-" + UUID.randomUUID(), "x"), "replace on absent returns null");

        assertFalse(region.replace(key, "wrong", "v2"), "compare-replace false on mismatch");
        assertTrue(region.replace(key, "third", "v2"), "compare-replace true on match");
    }

    @Disabled("Follow-up: remove(k,v) needs the entry-not-found DESTROY reply and a shared value "
            + "decoder for the DESTROY expected-value part. The PUT-side atomic ops (above) are done.")
    @Test
    void removeKeyValueReturnValue() {
        String key = "crm-" + UUID.randomUUID();
        region.put(key, "v1");

        assertFalse(region.remove(key, "wrong"), "remove(k,v) false on mismatch");
        assertTrue(region.remove(key, "v1"), "remove(k,v) true on match");
        assertFalse(region.containsKeyOnServer(key));
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
