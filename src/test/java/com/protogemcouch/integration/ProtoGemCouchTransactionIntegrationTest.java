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
    void bufferedRemoveIsAppliedOnCommit() {
        region.put("doomed", "x"); // committed outside any transaction
        assertEquals("x", region.get("doomed"));

        txMgr.begin();
        region.remove("doomed");
        assertNull(region.get("doomed"), "the remove is visible inside the transaction");
        txMgr.commit();

        assertNull(region.get("doomed"), "the remove is applied on commit");
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
