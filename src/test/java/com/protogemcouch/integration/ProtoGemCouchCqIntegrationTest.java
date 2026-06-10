package com.protogemcouch.integration;

import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.CqAttributesFactory;
import org.apache.geode.cache.query.CqEvent;
import org.apache.geode.cache.query.CqListener;
import org.apache.geode.cache.query.CqQuery;
import org.apache.geode.cache.query.QueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1 gate for Continuous Queries: a real Geode 1.15 client registers a CQ with an OQL predicate and a
 * {@code CqListener}; when a separate client makes a mutation that matches the predicate, the shim
 * pushes a CQ event and the listener fires (and does NOT fire for a non-matching mutation). Requires
 * the {@code geode-cq} test dependency for the client-side CQ engine.
 */
@Tag("integration")
class ProtoGemCouchCqIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private ClientCache cache;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(true)
                .setPoolSubscriptionRedundancy(0)
                .setPoolSubscriptionAckInterval(100)
                .addPoolServer(HOST, SHIM_PORT)
                .create();
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void cqListenerFiresOnlyForPredicateMatchingMutation() throws Exception {
        String regionName = "cq" + UUID.randomUUID().toString().replace("-", "");
        cache.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(regionName);
        // Let the subscription queue establish before registering the CQ.
        Thread.sleep(3000);

        CountDownLatch matched = new CountDownLatch(1);
        AtomicReference<Object> key = new AtomicReference<>();
        AtomicReference<Object> op = new AtomicReference<>();

        QueryService qs = cache.getQueryService();
        CqAttributesFactory caf = new CqAttributesFactory();
        caf.addCqListener(new CqListener() {
            @Override
            public void onEvent(CqEvent event) {
                key.set(event.getKey());
                op.set(event.getQueryOperation());
                matched.countDown();
            }

            @Override
            public void onError(CqEvent event) {
            }

            @Override
            public void close() {
            }
        });
        CqQuery cq = qs.newCq("itCq", "SELECT * FROM /" + regionName + " r WHERE r.amount > 10", caf.create());
        cq.execute();

        // From a separate client: a non-matching entry (amount 5) then a matching one (amount 20).
        runPutMap(regionName, "low", 5);
        runPutMap(regionName, "high", 20);

        assertTrue(matched.await(20, TimeUnit.SECONDS), "the CqListener fired for the matching mutation");
        assertEquals("high", key.get(),
                "the CQ fired only for the predicate-matching entry (not the non-matching 'low')");
    }

    private static void runPutMap(String region, String key, int amount) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        Process process = new ProcessBuilder(
                javaBin, "-cp", classpath, "com.protogemcouch.tools.PutOnce",
                HOST, Integer.toString(SHIM_PORT), region, key, Integer.toString(amount), "map")
                .inheritIO()
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("PutOnce mutator process did not finish in time");
        }
        assertEquals(0, process.exitValue(), "PutOnce mutator process succeeded");
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
