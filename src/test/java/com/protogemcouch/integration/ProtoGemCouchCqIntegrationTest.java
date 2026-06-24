package com.protogemcouch.integration;

import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.CqAttributesFactory;
import org.apache.geode.cache.query.CqEvent;
import org.apache.geode.cache.query.CqListener;
import org.apache.geode.cache.query.CqQuery;
import org.apache.geode.cache.query.CqResults;
import org.apache.geode.cache.query.QueryService;
import org.apache.geode.cache.query.Struct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
                // PDX CQ events carry serialized PdxInstances; read them as PdxInstance (no domain class).
                .setPdxReadSerialized(true)
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

    @Test
    void executeWithInitialResultsReturnsCurrentMatchingEntries() throws Exception {
        String regionName = "cq" + UUID.randomUUID().toString().replace("-", "");
        var region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .create(regionName);
        Thread.sleep(3000);

        // Seed entries BEFORE registering the CQ: two match the predicate (amount > 10), one does not.
        region.put("low", new HashMap<>(Map.of("amount", 5)));
        region.put("hi1", new HashMap<>(Map.of("amount", 20)));
        region.put("hi2", new HashMap<>(Map.of("amount", 30)));

        QueryService qs = cache.getQueryService();
        CqAttributesFactory caf = new CqAttributesFactory();
        caf.addCqListener(new CqListener() {
            @Override
            public void onEvent(CqEvent event) {
            }

            @Override
            public void onError(CqEvent event) {
            }

            @Override
            public void close() {
            }
        });
        CqQuery cq = qs.newCq("itCqIr", "SELECT * FROM /" + regionName + " r WHERE r.amount > 10", caf.create());

        CqResults<?> results = cq.executeWithInitialResults();

        // The initial result set is a CqResults of Struct{key, value}; collect the keys.
        Set<Object> keys = new HashSet<>();
        for (Object row : results.asList()) {
            keys.add(((Struct) row).get("key"));
        }
        assertEquals(Set.of("hi1", "hi2"), keys,
                "executeWithInitialResults returns exactly the entries matching the predicate");
    }

    @Test
    void multipleCqsOnOneClientEachFireForAMatchingMutation() throws Exception {
        String regionName = "cq" + UUID.randomUUID().toString().replace("-", "");
        cache.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(regionName);
        Thread.sleep(3000);

        CountDownLatch cqA = new CountDownLatch(1);
        CountDownLatch cqB = new CountDownLatch(1);
        QueryService qs = cache.getQueryService();

        CqAttributesFactory cafA = new CqAttributesFactory();
        cafA.addCqListener(countingListener(cqA));
        qs.newCq("cqA", "SELECT * FROM /" + regionName + " r WHERE r.amount > 10", cafA.create()).execute();

        CqAttributesFactory cafB = new CqAttributesFactory();
        cafB.addCqListener(countingListener(cqB));
        qs.newCq("cqB", "SELECT * FROM /" + regionName + " r WHERE r.amount > 5", cafB.create()).execute();

        // amount 20 matches both predicates -> both CQs must fire for the one mutation.
        runPutMap(regionName, "both", 20);

        assertTrue(cqA.await(20, TimeUnit.SECONDS), "cqA (>10) fired for the matching mutation");
        assertTrue(cqB.await(20, TimeUnit.SECONDS), "cqB (>5) also fired for the same mutation");
    }

    @Test
    void cqStatisticsCountReceivedEvents() throws Exception {
        String regionName = "cq" + UUID.randomUUID().toString().replace("-", "");
        cache.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(regionName);
        Thread.sleep(3000);

        CountDownLatch matched = new CountDownLatch(1);
        QueryService qs = cache.getQueryService();
        CqAttributesFactory caf = new CqAttributesFactory();
        caf.addCqListener(countingListener(matched));
        CqQuery cq = qs.newCq("statCq", "SELECT * FROM /" + regionName + " r WHERE r.amount > 10", caf.create());
        cq.execute();

        runPutMap(regionName, "x", 20);
        assertTrue(matched.await(20, TimeUnit.SECONDS), "CQ event received");
        Thread.sleep(500); // let the client's CqStatistics settle

        assertTrue(cq.getStatistics().numEvents() >= 1,
                "CQ statistics count the received event (numEvents=" + cq.getStatistics().numEvents() + ")");
    }

    private static CqListener countingListener(CountDownLatch latch) {
        return new CqListener() {
            @Override
            public void onEvent(CqEvent event) {
                latch.countDown();
            }

            @Override
            public void onError(CqEvent event) {
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void cqListenerFiresForPredicateMatchingPdxObject() throws Exception {
        String regionName = "cq" + UUID.randomUUID().toString().replace("-", "");
        cache.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(regionName);
        Thread.sleep(3000);

        CountDownLatch matched = new CountDownLatch(1);
        AtomicReference<Object> key = new AtomicReference<>();

        QueryService qs = cache.getQueryService();
        CqAttributesFactory caf = new CqAttributesFactory();
        caf.addCqListener(new CqListener() {
            @Override
            public void onEvent(CqEvent event) {
                key.set(event.getKey());
                matched.countDown();
            }

            @Override
            public void onError(CqEvent event) {
            }

            @Override
            public void close() {
            }
        });
        // Predicate on a PDX object field — matching must use PDX-aware field resolution, not the
        // map resolver, to see r.amount inside the stored PdxInstance.
        CqQuery cq = qs.newCq("itCqPdx", "SELECT * FROM /" + regionName + " r WHERE r.amount > 10", caf.create());
        cq.execute();

        // From a separate client: a non-matching PDX object (amount 5) then a matching one (amount 20).
        runPutMap(regionName, "plow", 5, "pdx");
        runPutMap(regionName, "phigh", 20, "pdx");

        assertTrue(matched.await(20, TimeUnit.SECONDS),
                "the CqListener fired for the PDX object matching the predicate");
        assertEquals("phigh", key.get(),
                "the CQ fired only for the predicate-matching PDX object (not the non-matching 'plow')");
    }

    @Test
    void cqListenerFiresForPredicateMatchingNestedPdxField() throws Exception {
        String regionName = "cq" + UUID.randomUUID().toString().replace("-", "");
        cache.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(regionName);
        Thread.sleep(3000);

        CountDownLatch matched = new CountDownLatch(1);
        AtomicReference<Object> key = new AtomicReference<>();

        QueryService qs = cache.getQueryService();
        CqAttributesFactory caf = new CqAttributesFactory();
        caf.addCqListener(new CqListener() {
            @Override
            public void onEvent(CqEvent event) {
                key.set(event.getKey());
                matched.countDown();
            }

            @Override
            public void onError(CqEvent event) {
            }

            @Override
            public void close() {
            }
        });
        // Predicate on a NESTED PDX object field — CQ matching must navigate r.address.zip inside the
        // stored PdxInstance, the same nested resolution the one-shot QUERY path uses.
        CqQuery cq = qs.newCq("itCqNested",
                "SELECT * FROM /" + regionName + " r WHERE r.address.zip = '78701'", caf.create());
        cq.execute();

        // From a separate client: a non-matching nested PDX (zip 10001) then a matching one (zip 78701).
        runPutMap(regionName, "nlow", 10001, "pdxnested");
        runPutMap(regionName, "nhigh", 78701, "pdxnested");

        assertTrue(matched.await(20, TimeUnit.SECONDS),
                "the CqListener fired for the nested-PDX object matching the predicate");
        assertEquals("nhigh", key.get(),
                "the CQ fired only for the nested-field match (not the non-matching 'nlow')");
    }

    @Test
    void cqListenerFiresForPredicateMatchingPdxObjectArrayElementField() throws Exception {
        String regionName = "cq" + UUID.randomUUID().toString().replace("-", "");
        cache.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(regionName);
        Thread.sleep(3000);

        CountDownLatch matched = new CountDownLatch(1);
        AtomicReference<Object> key = new AtomicReference<>();

        QueryService qs = cache.getQueryService();
        CqAttributesFactory caf = new CqAttributesFactory();
        caf.addCqListener(new CqListener() {
            @Override
            public void onEvent(CqEvent event) {
                key.set(event.getKey());
                matched.countDown();
            }

            @Override
            public void onError(CqEvent event) {
            }

            @Override
            public void close() {
            }
        });
        // Predicate on a PDX OBJECT-ARRAY element field — CQ matching must index into r.addresses and
        // navigate the nested PDX element's zip, the same resolution the one-shot QUERY path uses.
        CqQuery cq = qs.newCq("itCqObjArray",
                "SELECT * FROM /" + regionName + " r WHERE r.addresses[0].zip = '78701'", caf.create());
        cq.execute();

        // From a separate client: a non-matching object-array PDX (zip 10001) then a matching one (78701).
        runPutMap(regionName, "olow", 10001, "pdxobjarray");
        runPutMap(regionName, "ohigh", 78701, "pdxobjarray");

        assertTrue(matched.await(20, TimeUnit.SECONDS),
                "the CqListener fired for the object-array element matching the predicate");
        assertEquals("ohigh", key.get(),
                "the CQ fired only for the object-array element match (not the non-matching 'olow')");
    }

    @Test
    void cqListenerFiresDestroyWhenMatchingEntryIsRemoved() throws Exception {
        String regionName = "cq" + UUID.randomUUID().toString().replace("-", "");
        cache.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(regionName);
        Thread.sleep(3000);

        CountDownLatch destroyed = new CountDownLatch(1);
        AtomicReference<Object> destroyedKey = new AtomicReference<>();

        QueryService qs = cache.getQueryService();
        CqAttributesFactory caf = new CqAttributesFactory();
        caf.addCqListener(new CqListener() {
            @Override
            public void onEvent(CqEvent event) {
                if ("DESTROY".equals(String.valueOf(event.getQueryOperation()))) {
                    destroyedKey.set(event.getKey());
                    destroyed.countDown();
                }
            }

            @Override
            public void onError(CqEvent event) {
            }

            @Override
            public void close() {
            }
        });
        CqQuery cq = qs.newCq("itCqD", "SELECT * FROM /" + regionName + " r WHERE r.amount > 10", caf.create());
        cq.execute();

        // A separate client creates a matching entry then removes it -> CQ DESTROY.
        runPutMap(regionName, "dk", 20, "mapdestroy");

        assertTrue(destroyed.await(20, TimeUnit.SECONDS), "the CqListener fired a DESTROY for the removed match");
        assertEquals("dk", destroyedKey.get());
    }

    @Test
    void cqListenerFiresDestroyWhenUpdatedValueStopsMatching() throws Exception {
        String regionName = "cq" + UUID.randomUUID().toString().replace("-", "");
        cache.createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(regionName);
        Thread.sleep(3000);

        CountDownLatch destroyed = new CountDownLatch(1);
        AtomicReference<Object> destroyedKey = new AtomicReference<>();

        QueryService qs = cache.getQueryService();
        CqAttributesFactory caf = new CqAttributesFactory();
        caf.addCqListener(new CqListener() {
            @Override
            public void onEvent(CqEvent event) {
                if ("DESTROY".equals(String.valueOf(event.getQueryOperation()))) {
                    destroyedKey.set(event.getKey());
                    destroyed.countDown();
                }
            }

            @Override
            public void onError(CqEvent event) {
            }

            @Override
            public void close() {
            }
        });
        CqQuery cq = qs.newCq("itCqS", "SELECT * FROM /" + regionName + " r WHERE r.amount > 10", caf.create());
        cq.execute();

        // A separate client creates a matching entry (amount 20) then updates it to a non-matching one
        // (amount 1): the entry leaves the result set, so the CQ must fire DESTROY (stops-matching).
        runPutMap(regionName, "sk", 20, "mapstops");

        assertTrue(destroyed.await(20, TimeUnit.SECONDS),
                "the CqListener fired DESTROY when the updated value stopped matching the predicate");
        assertEquals("sk", destroyedKey.get());
    }

    private static void runPutMap(String region, String key, int amount) throws Exception {
        runPutMap(region, key, amount, "map");
    }

    private static void runPutMap(String region, String key, int amount, String op) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        Process process = new ProcessBuilder(
                javaBin, "-cp", classpath, "com.protogemcouch.tools.PutOnce",
                HOST, Integer.toString(SHIM_PORT), region, key, Integer.toString(amount), op)
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
