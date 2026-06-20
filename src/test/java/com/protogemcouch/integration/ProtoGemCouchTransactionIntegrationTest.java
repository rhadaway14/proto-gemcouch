package com.protogemcouch.integration;

import org.apache.geode.cache.CacheTransactionManager;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates client transactions end-to-end against the shim with a real Geode 1.15 client:
 * begin/commit persists the buffered writes, begin/rollback discards them, reads inside a
 * transaction see the transaction's own writes (read-your-writes), and a buffered remove is applied
 * on commit.
 */
@Tag("integration")
class ProtoGemCouchTransactionIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private ClientCache cache;
    private Region<String, Object> region;
    private CacheTransactionManager txMgr;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(HOST, SHIM_PORT)
                .create();
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("tx" + UUID.randomUUID().toString().replace("-", ""));
        txMgr = cache.getCacheTransactionManager();
    }

    @AfterEach
    void tearDown() {
        if (txMgr != null && txMgr.exists()) {
            txMgr.rollback();
        }
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void commitPersistsBufferedWrites() {
        txMgr.begin();
        region.put("a", "1");
        region.put("b", "2");
        txMgr.commit();

        assertEquals("1", region.get("a"));
        assertEquals("2", region.get("b"));
    }

    @Test
    void rollbackDiscardsBufferedWrites() {
        txMgr.begin();
        region.put("ghost", "value");
        txMgr.rollback();

        assertNull(region.get("ghost"), "a rolled-back write must not be persisted");
    }

    @Test
    void readsInsideTransactionSeeItsOwnWrites() {
        txMgr.begin();
        region.put("ryow", "seen");
        // The committed store does not have it yet, but read-your-writes must return the buffered value.
        assertEquals("seen", region.get("ryow"), "read-your-writes inside the transaction");
        txMgr.commit();
        assertEquals("seen", region.get("ryow"));
    }

    @Test
    void txPutIfAbsentOnExistingKeyReturnsPriorValueAndDoesNotOverwrite() {
        region.put("k", "orig"); // committed before the tx
        txMgr.begin();
        Object prior = region.putIfAbsent("k", "new");
        txMgr.commit();

        assertEquals("orig", prior, "putIfAbsent in a tx returns the existing value");
        assertEquals("orig", region.get("k"), "putIfAbsent in a tx must not overwrite an existing key");
    }

    @Test
    void txReplaceOnAbsentKeyDoesNothing() {
        txMgr.begin();
        Object prior = region.replace("absent", "v");
        txMgr.commit();

        assertNull(prior, "replace in a tx returns null when the key is absent");
        assertNull(region.get("absent"), "replace in a tx must not create an absent key");
    }

    @Test
    void txReplaceOnExistingKeyReplacesAndReturnsPrior() {
        region.put("k", "a");
        txMgr.begin();
        Object prior = region.replace("k", "b");
        txMgr.commit();

        assertEquals("a", prior, "replace in a tx returns the prior value");
        assertEquals("b", region.get("k"), "replace in a tx updates an existing key");
    }

    @Test
    void txRemoveWithWrongValueDoesNotRemove() {
        region.put("k", "x");
        txMgr.begin();
        boolean removed = region.remove("k", "wrong");
        txMgr.commit();

        assertTrue(!removed, "remove(k,v) in a tx returns false on a value mismatch");
        assertEquals("x", region.get("k"), "remove(k,v) in a tx must not remove on a value mismatch");
    }

    @Test
    void txRemoveWithMatchingValueRemoves() {
        region.put("k", "y");
        txMgr.begin();
        boolean removed = region.remove("k", "y");
        txMgr.commit();

        assertTrue(removed, "remove(k,v) in a tx returns true on a value match");
        assertNull(region.get("k"), "remove(k,v) in a tx removes the entry on a value match");
    }

    @Test
    void containsKeyReflectsBufferedWritesInsideTransaction() {
        region.put("base", "b"); // committed outside the transaction

        txMgr.begin();
        region.put("fresh", "n");
        region.remove("base");
        assertEquals(Boolean.TRUE, region.containsKeyOnServer("fresh"),
                "a buffered put is visible to containsKey inside the transaction");
        assertEquals(Boolean.FALSE, region.containsKeyOnServer("base"),
                "a buffered remove hides a committed key inside the transaction");
        txMgr.commit();
    }

    @Test
    void sizeAndKeySetReflectBufferedWritesInsideTransaction() {
        region.put("k1", "1");
        region.put("k2", "2"); // committed: 2 keys

        txMgr.begin();
        region.put("k3", "3"); // +1
        region.remove("k1");   // -1  -> net still 2 inside the tx
        assertEquals(2, region.sizeOnServer(), "size reflects buffered add and remove");
        java.util.Set<String> keys = region.keySetOnServer();
        assertTrue(keys.contains("k2") && keys.contains("k3") && !keys.contains("k1"),
                "keySet overlays buffered writes: " + keys);
        txMgr.commit();

        assertEquals(2, region.sizeOnServer());
    }

    @Test
    void getAllOverlaysBufferedWritesInsideTransaction() {
        region.put("g1", "1");
        region.put("g2", "2"); // committed

        txMgr.begin();
        region.put("g2", "two"); // overwrite
        region.put("g3", "3");   // new
        region.remove("g1");     // removed
        java.util.Map<String, Object> all =
                region.getAll(java.util.List.of("g1", "g2", "g3"));
        assertNull(all.get("g1"), "buffered remove reads as absent");
        assertEquals("two", all.get("g2"), "buffered overwrite is seen");
        assertEquals("3", all.get("g3"), "buffered new key is seen");
        txMgr.commit();
    }

    @Test
    void bufferedRemoveIsAppliedOnCommit() {
        region.put("doomed", "x"); // committed outside any transaction
        assertEquals("x", region.get("doomed"));

        txMgr.begin();
        region.remove("doomed");
        assertNull(region.get("doomed"), "the remove is visible inside the transaction");
        txMgr.commit();

        assertNull(region.get("doomed"), "the remove is applied on commit");
    }

    @Test
    void commitIsAtomicWhenAnOperationFails() {
        region.put("survivor", "before"); // committed before the transaction

        txMgr.begin();
        region.put("survivor", "after");          // would overwrite
        region.put("ok", "v");                     // new key
        region.put("x".repeat(300), "tooLong");    // doc id exceeds Couchbase's 250-byte limit
        assertThrows(Exception.class, () -> txMgr.commit(),
                "an invalid operation fails the whole commit");

        // All-or-nothing: nothing from the failed transaction was persisted.
        assertEquals("before", region.get("survivor"), "the prior committed value is unchanged");
        assertNull(region.get("ok"), "no buffered write from a failed commit is persisted");
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
