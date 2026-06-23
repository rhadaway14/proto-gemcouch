package com.protogemcouch.integration;

import org.apache.geode.cache.EntryEvent;
import org.apache.geode.cache.InterestResultPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.CqAttributesFactory;
import org.apache.geode.cache.query.CqEvent;
import org.apache.geode.cache.query.CqListener;
import org.apache.geode.cache.query.QueryService;
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
    private static final String AWAY_REGISTERED_METRIC = "protogemcouch_durable_away_registered";

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
        double awayBefore = readMetric(HEALTH_PORT, AWAY_REGISTERED_METRIC);
        d1.close(true);     // keepalive: retain the subscription queue while away

        // Gate: the origin enqueues only for away clients in its registry cache (refreshed on an
        // interval), so wait until the shim actually sees this client as away before mutating —
        // otherwise the missed event is never enqueued. Far more load-tolerant than a fixed sleep.
        awaitMetricAtLeast(HEALTH_PORT, AWAY_REGISTERED_METRIC, awayBefore + 1, Duration.ofSeconds(30));

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

    @Test
    void durableClientReplaysCqEventsMissedWhileDisconnected() throws Exception {
        String region = "dcq" + UUID.randomUUID().toString().replace("-", "");
        String durableId = "ITDCQ" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String cqName = "durCq";
        String cqText = "SELECT * FROM /" + region; // no predicate -> matches any value (incl. a String)

        // Phase 1: durable client registers a CQ, is ready, then closes keeping its queue.
        ClientCache d1 = durableCache(durableId);
        d1.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(region);
        QueryService qs1 = d1.getQueryService();
        CqAttributesFactory caf1 = new CqAttributesFactory();
        caf1.addCqListener(noopCqListener());
        qs1.newCq(cqName, cqText, caf1.create()).execute();
        d1.readyForEvents();
        Thread.sleep(1000);
        double awayBefore = readMetric(HEALTH_PORT, AWAY_REGISTERED_METRIC);
        d1.close(true);

        // Gate on the shim seeing this client as away (its persisted CQ def loaded into the origin's
        // registry cache) before mutating, so the CQ-enqueue path actually runs.
        awaitMetricAtLeast(HEALTH_PORT, AWAY_REGISTERED_METRIC, awayBefore + 1, Duration.ofSeconds(30));

        // Phase 2: a CQ-matching mutation arrives while the durable client is disconnected.
        runPutOnce(region, "cqmissed", "v");

        // Phase 3: reconnect, re-register the CQ with a listener; readyForEvents must replay the CQ event.
        CountDownLatch fired = new CountDownLatch(1);
        AtomicReference<Object> key = new AtomicReference<>();
        ClientCache d2 = durableCache(durableId);
        try {
            d2.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(region);
            QueryService qs2 = d2.getQueryService();
            CqAttributesFactory caf2 = new CqAttributesFactory();
            caf2.addCqListener(new CqListener() {
                @Override
                public void onEvent(CqEvent event) {
                    key.set(event.getKey());
                    fired.countDown();
                }

                @Override
                public void onError(CqEvent event) {
                }

                @Override
                public void close() {
                }
            });
            qs2.newCq(cqName, cqText, caf2.create()).execute();
            d2.readyForEvents();

            assertTrue(fired.await(20, TimeUnit.SECONDS),
                    "durable CQ replays the event it missed while disconnected");
            assertEquals("cqmissed", key.get());
        } finally {
            d2.close(false);
        }
    }

    private static CqListener noopCqListener() {
        return new CqListener() {
            @Override
            public void onEvent(CqEvent event) {
            }

            @Override
            public void onError(CqEvent event) {
            }

            @Override
            public void close() {
            }
        };
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

    /** Polls a shim's Prometheus {@code /metrics} until {@code metric >= atLeast} or the timeout. */
    private static void awaitMetricAtLeast(int healthPort, String metric, double atLeast, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        double last = -1;
        while (System.nanoTime() < deadline) {
            last = readMetric(healthPort, metric);
            if (last >= atLeast) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted awaiting metric " + metric);
            }
        }
        fail("metric " + metric + " did not reach " + atLeast + " within " + timeout + " (last=" + last + ")");
    }

    /** Reads a single numeric gauge from the shim's Prometheus {@code /metrics} text, or -1 if absent. */
    private static double readMetric(int healthPort, String metric) {
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create("http://" + HOST + ":" + healthPort + "/metrics").toURL().openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setRequestMethod("GET");
            try {
                if (connection.getResponseCode() != 200) {
                    return -1;
                }
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("#") || !line.startsWith(metric)) {
                            continue;
                        }
                        // Match the bare metric name (no labels) followed by whitespace + value.
                        String rest = line.substring(metric.length());
                        if (rest.isEmpty() || !Character.isWhitespace(rest.charAt(0))) {
                            continue;
                        }
                        return Double.parseDouble(rest.trim());
                    }
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
            // treat as not-yet-available
        }
        return -1;
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
