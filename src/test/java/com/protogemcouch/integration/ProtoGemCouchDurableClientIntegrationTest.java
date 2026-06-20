package com.protogemcouch.integration;

import org.apache.geode.cache.EntryEvent;
import org.apache.geode.cache.InterestResultPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.util.CacheListenerAdapter;
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
 * P3 gate for durable clients (single-instance): a durable client that disconnects must have its
 * interest + event queue retained, and on reconnect + {@code readyForEvents()} the events it missed
 * while away must be replayed to its {@code CacheListener}.
 */
@Tag("integration")
class ProtoGemCouchDurableClientIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
    }

    @Test
    void durableClientReplaysEventsMissedWhileDisconnected() throws Exception {
        String region = "dur" + UUID.randomUUID().toString().replace("-", "");
        String durableId = "ITDUR" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // Phase 1: durable client connects, registers interest, is ready, then closes KEEPING its queue.
        ClientCache d1 = durableCache(durableId);
        Region<String, Object> r1 = d1.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .create(region);
        r1.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);
        d1.readyForEvents();
        Thread.sleep(1000); // let the server-side interest register
        d1.close(true);     // keepalive: retain the subscription queue while away

        // Phase 2: a separate client mutates the region while the durable client is disconnected.
        runPutOnce(region, "missed1", "hello");

        // Phase 3: the durable client reconnects (same durable id) with a listener; readyForEvents()
        // must replay the event it missed.
        CountDownLatch replayed = new CountDownLatch(1);
        AtomicReference<Object> value = new AtomicReference<>();
        ClientCache d2 = durableCache(durableId);
        try {
            Region<String, Object> r2 = d2.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                    .addCacheListener(new CacheListenerAdapter<String, Object>() {
                        @Override
                        public void afterCreate(EntryEvent<String, Object> event) {
                            if ("missed1".equals(event.getKey())) {
                                value.set(event.getNewValue());
                                replayed.countDown();
                            }
                        }

                        @Override
                        public void afterUpdate(EntryEvent<String, Object> event) {
                            if ("missed1".equals(event.getKey())) {
                                value.set(event.getNewValue());
                                replayed.countDown();
                            }
                        }
                    })
                    .create(region);
            r2.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);
            d2.readyForEvents(); // triggers replay of the queued event

            assertTrue(replayed.await(20, TimeUnit.SECONDS),
                    "durable client replays the event it missed while disconnected");
            assertEquals("hello", value.get());
        } finally {
            d2.close(false);
        }
    }

    private static ClientCache durableCache(String durableId) {
        return new ClientCacheFactory()
                .set("log-level", "warn")
                .set("durable-client-id", durableId)
                .set("durable-client-timeout", "300")
                .setPoolSubscriptionEnabled(true)
                .setPoolSubscriptionRedundancy(0)
                .setPoolSubscriptionAckInterval(100)
                .addPoolServer(HOST, SHIM_PORT)
                .create();
    }

    private static void runPutOnce(String region, String key, String value) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        Process process = new ProcessBuilder(
                javaBin, "-cp", classpath, "com.protogemcouch.tools.PutOnce",
                HOST, Integer.toString(SHIM_PORT), region, key, value)
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
