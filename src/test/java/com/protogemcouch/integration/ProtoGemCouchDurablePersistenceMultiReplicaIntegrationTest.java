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
 * 1.2.0-M1 Slice 2 gate: with {@code DURABLE_PERSISTENCE} on, a durable client's missed events survive
 * in Couchbase and replay on a reconnect to a <em>different</em> replica — proving the queue is no
 * longer tied to the replica that owned the client.
 *
 * <p>Flow: durable client connects to replica A and registers interest, then disconnects (keepalive).
 * A mutation on A is enqueued to Couchbase for the away client. The durable client reconnects to
 * replica B (same durable id) and {@code readyForEvents()} replays the missed event from Couchbase.
 *
 * <p>Requires both shims from {@code docker-compose.yml} ({@code protogemcouch} at A, and
 * {@code protogemcouch-replica} at B), both with {@code DURABLE_PERSISTENCE=true}.
 */
@Tag("integration")
class ProtoGemCouchDurablePersistenceMultiReplicaIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int A_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int A_HEALTH = intEnv("IT_HEALTH_PORT", 8081);
    private static final int B_PORT = intEnv("IT_REPLICA_PORT", 40409);
    private static final int B_HEALTH = intEnv("IT_REPLICA_HEALTH_PORT", 8085);
    private static final String AWAY_REGISTERED_METRIC = "protogemcouch_durable_away_registered";

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + A_HEALTH + "/ready", Duration.ofSeconds(90));
        waitForReady("http://" + HOST + ":" + B_HEALTH + "/ready", Duration.ofSeconds(90));
    }

    @Test
    void durableClientReplaysMissedEventAfterReconnectingToAnotherReplica() throws Exception {
        String region = "dpm" + UUID.randomUUID().toString().replace("-", "");
        String durableId = "ITDPM" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // Phase 1: durable client connects to replica A, registers interest, is ready, then closes
        // keeping its subscription (keepalive). A retains its interest and persists the record (away).
        ClientCache a = durableCache(durableId, A_PORT);
        Region<String, Object> ra = a.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .create(region);
        ra.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);
        a.readyForEvents();
        Thread.sleep(1000);
        double awayBeforeA = readMetric(A_HEALTH, AWAY_REGISTERED_METRIC);
        a.close(true);

        // Gate: A (the origin for the Phase-2 mutation) only enqueues for clients in its away-registry
        // cache, so wait until A sees this client as away before mutating. Load-tolerant vs. a fixed sleep.
        awaitMetricAtLeast(A_HEALTH, AWAY_REGISTERED_METRIC, awayBeforeA + 1, Duration.ofSeconds(30));

        // Phase 2: a mutation lands on replica A while the durable client is away -> A enqueues the
        // event to the durable client's Couchbase queue (not just A's memory).
        runPutOnce(A_PORT, region, "missed-x", "hello-from-A");

        // Phase 3: the durable client reconnects to replica B (which never saw it) and readyForEvents()
        // must replay the missed event by draining the Couchbase queue.
        CountDownLatch replayed = new CountDownLatch(1);
        AtomicReference<Object> value = new AtomicReference<>();
        ClientCache b = durableCache(durableId, B_PORT);
        try {
            Region<String, Object> rb = b.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                    .addCacheListener(new CacheListenerAdapter<String, Object>() {
                        @Override
                        public void afterCreate(EntryEvent<String, Object> event) {
                            if ("missed-x".equals(event.getKey())) {
                                value.set(event.getNewValue());
                                replayed.countDown();
                            }
                        }

                        @Override
                        public void afterUpdate(EntryEvent<String, Object> event) {
                            if ("missed-x".equals(event.getKey())) {
                                value.set(event.getNewValue());
                                replayed.countDown();
                            }
                        }
                    })
                    .create(region);
            rb.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);
            b.readyForEvents();

            assertTrue(replayed.await(20, TimeUnit.SECONDS),
                    "durable client replays the missed event after reconnecting to the other replica");
            assertEquals("hello-from-A", value.get());
        } finally {
            b.close(false);
        }
    }

    @Test
    void nonOwnerReplicaEnqueuesForAwayClientFromTheRegistry() throws Exception {
        String region = "dpo" + UUID.randomUUID().toString().replace("-", "");
        String durableId = "ITDPO" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // Phase 1: durable client connects to replica A, registers interest, is ready, then closes
        // keeping its subscription. A persists the away record (interests) to Couchbase.
        ClientCache a = durableCache(durableId, A_PORT);
        Region<String, Object> ra = a.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .create(region);
        ra.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);
        a.readyForEvents();
        Thread.sleep(1000);
        double awayBeforeB = readMetric(B_HEALTH, AWAY_REGISTERED_METRIC); // client still connected -> not yet away
        a.close(true);

        // Gate: B (the origin for the Phase-2 mutation) never owned this client, so it must refresh its
        // away-registry cache from Couchbase before it knows to enqueue. Wait until B sees the away
        // client rather than guessing with a fixed sleep (DURABLE_AWAY_REFRESH_MS default 1000ms).
        awaitMetricAtLeast(B_HEALTH, AWAY_REGISTERED_METRIC, awayBeforeB + 1, Duration.ofSeconds(30));

        // Phase 2: the mutation lands on replica B — which never saw this client. As the origin, B reads
        // the persisted registry and enqueues the event for the away client (Slice 3, owner-independent).
        runPutOnce(B_PORT, region, "by-nonowner", "hello-from-B");

        // Phase 3: the durable client reconnects (to B) and readyForEvents() replays the event that the
        // non-owner replica enqueued from the registry.
        CountDownLatch replayed = new CountDownLatch(1);
        AtomicReference<Object> value = new AtomicReference<>();
        ClientCache b = durableCache(durableId, B_PORT);
        try {
            Region<String, Object> rb = b.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                    .addCacheListener(new CacheListenerAdapter<String, Object>() {
                        @Override
                        public void afterCreate(EntryEvent<String, Object> event) {
                            if ("by-nonowner".equals(event.getKey())) {
                                value.set(event.getNewValue());
                                replayed.countDown();
                            }
                        }

                        @Override
                        public void afterUpdate(EntryEvent<String, Object> event) {
                            if ("by-nonowner".equals(event.getKey())) {
                                value.set(event.getNewValue());
                                replayed.countDown();
                            }
                        }
                    })
                    .create(region);
            rb.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);
            b.readyForEvents();

            assertTrue(replayed.await(20, TimeUnit.SECONDS),
                    "a non-owner replica enqueues an away client's event from the persisted registry");
            assertEquals("hello-from-B", value.get());
        } finally {
            b.close(false);
        }
    }

    @Test
    void nonOwnerReplicaEnqueuesCqEventForAwayClient() throws Exception {
        String region = "dpc" + UUID.randomUUID().toString().replace("-", "");
        String durableId = "ITDPC" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String cqName = "durCqCrossReplica";
        String cqText = "SELECT * FROM /" + region; // no predicate -> matches any value

        // Phase 1: durable client registers a CQ on replica A, is ready, then closes keeping its queue.
        // A persists the CQ definition (name/region/OQL text) into the away record.
        ClientCache a = durableCache(durableId, A_PORT);
        a.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(region);
        QueryService qsa = a.getQueryService();
        CqAttributesFactory cafa = new CqAttributesFactory();
        cafa.addCqListener(noopCqListener());
        qsa.newCq(cqName, cqText, cafa.create()).execute();
        a.readyForEvents();
        Thread.sleep(1000);
        double awayBeforeB = readMetric(B_HEALTH, AWAY_REGISTERED_METRIC); // client still connected -> not yet away
        a.close(true);

        // Gate: wait until replica B has refreshed its away-registry cache (incl. the persisted CQ
        // definition) from Couchbase and sees this away client, before the Phase-2 mutation.
        awaitMetricAtLeast(B_HEALTH, AWAY_REGISTERED_METRIC, awayBeforeB + 1, Duration.ofSeconds(30));

        // Phase 2: the CQ-matching mutation lands on replica B (which never saw this client). As the
        // origin, B recompiles the away client's persisted CQ, matches it, and enqueues the CQ event.
        runPutOnce(B_PORT, region, "cq-by-b", "v");

        // Phase 3: reconnect to B, re-register the CQ with a listener; readyForEvents replays the CQ event.
        CountDownLatch fired = new CountDownLatch(1);
        AtomicReference<Object> key = new AtomicReference<>();
        ClientCache b = durableCache(durableId, B_PORT);
        try {
            b.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(region);
            QueryService qsb = b.getQueryService();
            CqAttributesFactory cafb = new CqAttributesFactory();
            cafb.addCqListener(new CqListener() {
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
            qsb.newCq(cqName, cqText, cafb.create()).execute();
            b.readyForEvents();

            assertTrue(fired.await(20, TimeUnit.SECONDS),
                    "a non-owner replica enqueues an away client's CQ event from the persisted registry");
            assertEquals("cq-by-b", key.get());
        } finally {
            b.close(false);
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

    private static ClientCache durableCache(String durableId, int port) {
        return new ClientCacheFactory()
                .set("log-level", "warn")
                .set("durable-client-id", durableId)
                .set("durable-client-timeout", "300")
                .setPoolSubscriptionEnabled(true)
                .setPoolSubscriptionRedundancy(0)
                .setPoolSubscriptionAckInterval(100)
                .addPoolServer(HOST, port)
                .create();
    }

    private static void runPutOnce(int port, String region, String key, String value) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        Process process = new ProcessBuilder(
                javaBin, "-cp", classpath, "com.protogemcouch.tools.PutOnce",
                HOST, Integer.toString(port), region, key, value)
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

    /** Reads a single numeric gauge from a shim's Prometheus {@code /metrics} text, or -1 if absent. */
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
                return;
            }
        }
        fail("shim not ready at " + url + " within timeout");
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
