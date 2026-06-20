package com.protogemcouch.integration;

import org.apache.geode.cache.CacheLoader;
import org.apache.geode.cache.CacheLoaderException;
import org.apache.geode.cache.CacheWriterException;
import org.apache.geode.cache.LoaderHelper;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.util.CacheWriterAdapter;
import org.apache.geode.cache.EntryEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates client-side Geode cache callbacks against the shim: a {@code CacheLoader} fills a get-miss,
 * and a {@code CacheWriter} can veto or allow a write before it reaches the server. These callbacks run
 * in the client JVM, so the shim only has to behave correctly for the underlying get/put/destroy — this
 * proves that client-side cache semantics compose with the shim as the backend. (Server-side
 * CacheLoader/CacheWriter that run user code on the server are a documented non-goal, like functions.)
 */
@Tag("integration")
class ProtoGemCouchCacheCallbacksIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private ClientCache cache;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(HOST, SHIM_PORT)
                .create();
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    private Region<String, Object> freshRegion(ClientRegionShortcut shortcut) {
        return cache.<String, Object>createClientRegionFactory(shortcut)
                .create("cb" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Test
    void cacheLoaderFillsGetMissClientSide() {
        Region<String, Object> region = freshRegion(ClientRegionShortcut.CACHING_PROXY);
        AtomicInteger loads = new AtomicInteger();
        region.getAttributesMutator().setCacheLoader(new CacheLoader<String, Object>() {
            @Override
            public Object load(LoaderHelper<String, Object> helper) throws CacheLoaderException {
                loads.incrementAndGet();
                return "loaded-" + helper.getKey();
            }
            @Override
            public void close() {
            }
        });

        String key = "miss-" + UUID.randomUUID();
        Object value = region.get(key);

        // The shim returns null for the missing key (so the client loader is actually invoked), and the
        // client-side loader supplies the value. loads==1 proves the loader ran on the miss.
        assertEquals("loaded-" + key, value, "the client-side CacheLoader supplied the value on a get-miss");
        assertEquals(1, loads.get(), "the loader ran exactly once (only on a real miss)");
    }

    @Test
    void cacheWriterVetoBlocksTheWriteFromReachingTheServer() {
        Region<String, Object> region = freshRegion(ClientRegionShortcut.PROXY);
        region.getAttributesMutator().setCacheWriter(new CacheWriterAdapter<String, Object>() {
            @Override
            public void beforeCreate(EntryEvent<String, Object> event) throws CacheWriterException {
                throw new CacheWriterException("vetoed by client CacheWriter");
            }
        });

        String key = "veto-" + UUID.randomUUID();
        assertThrows(CacheWriterException.class, () -> region.put(key, "v"),
                "the client CacheWriter veto surfaces to the caller");
        // The op never reached the shim — a PROXY get goes straight to the server, which has nothing.
        assertNull(region.get(key), "a vetoed put must not be applied on the server");
    }

    @Test
    void cacheWriterAllowAppliesTheWrite() {
        Region<String, Object> region = freshRegion(ClientRegionShortcut.PROXY);
        AtomicInteger writes = new AtomicInteger();
        region.getAttributesMutator().setCacheWriter(new CacheWriterAdapter<String, Object>() {
            @Override
            public void beforeCreate(EntryEvent<String, Object> event) {
                writes.incrementAndGet(); // no veto
            }
        });

        String key = "allow-" + UUID.randomUUID();
        region.put(key, "v");

        assertTrue(writes.get() >= 1, "the CacheWriter saw the write");
        // A PROXY get reads straight from the server, confirming the allowed put propagated.
        assertEquals("v", region.get(key), "an allowed put is applied on the server");
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
