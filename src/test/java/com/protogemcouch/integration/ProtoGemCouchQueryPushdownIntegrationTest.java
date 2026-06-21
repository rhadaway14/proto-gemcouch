package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.pdx.PdxInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end validation of OQL pushdown against the {@code OQL_PUSHDOWN=true} shim instance
 * (docker-compose service {@code protogemcouch-pushdown}, geode 40414 / health 8090) and a real Geode
 * client. The contract M2 must hold is <strong>results identical to the scan path</strong>: pushdown
 * is only an optimization, so every query here must return exactly the rows the scan would. The cases
 * deliberately span the eligible path (string equality / AND) and the ineligible-fallback path (OR,
 * range), plus PDX values — where the N1QL candidate set is a superset and the shim's PDX-aware matcher
 * is authoritative.
 */
@Tag("integration")
class ProtoGemCouchQueryPushdownIntegrationTest {

    private static final String HOST = envOrDefault("IT_PUSHDOWN_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_PUSHDOWN_PORT", 40414);
    private static final int HEALTH_PORT = intEnv("IT_PUSHDOWN_HEALTH_PORT", 8090);

    private ClientCache cache;
    private Region<String, Object> region;
    private String regionName;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .setPdxReadSerialized(true)
                .addPoolServer(HOST, SHIM_PORT)
                .create();
        regionName = "pd" + UUID.randomUUID().toString().replace("-", "");
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void stringEqualityPushdownReturnsExactMatches() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 100)));
        region.put("b", new HashMap<>(Map.of("status", "closed", "amount", 50)));
        region.put("c", new HashMap<>(Map.of("status", "active", "amount", 10)));

        SelectResults<?> active = query("SELECT * FROM /" + regionName + " WHERE status = 'active'");
        assertEquals(2, active.size(), "pushdown returns exactly the active rows");
    }

    @Test
    void andOfEqualitiesPushdownNarrowsCorrectly() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "tier", "gold")));
        region.put("b", new HashMap<>(Map.of("status", "active", "tier", "silver")));
        region.put("c", new HashMap<>(Map.of("status", "closed", "tier", "gold")));

        SelectResults<?> r = query(
                "SELECT * FROM /" + regionName + " WHERE status = 'active' AND tier = 'gold'");
        assertEquals(1, r.size(), "ANDed equalities narrow to the single matching row");
    }

    @Test
    void equalityWithNoMatchReturnsNoRows() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active")));
        region.put("b", new HashMap<>(Map.of("status", "closed")));

        SelectResults<?> none = query("SELECT * FROM /" + regionName + " WHERE status = 'archived'");
        assertEquals(0, none.size(), "a selective predicate with no match returns nothing");
    }

    @Test
    void ineligibleRangeQueryFallsBackToScanAndIsCorrect() throws Exception {
        // A range predicate is not pushdown-eligible; the handler must fall back to the scan and still
        // return the correct rows even with the flag on.
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 100)));
        region.put("b", new HashMap<>(Map.of("status", "active", "amount", 10)));

        SelectResults<?> big = query("SELECT * FROM /" + regionName + " WHERE amount > 50");
        assertEquals(1, big.size(), "range query (scan fallback) returns the correct row");
    }

    @Test
    void ineligibleOrQueryFallsBackToScanAndIsCorrect() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active")));
        region.put("b", new HashMap<>(Map.of("status", "closed")));
        region.put("c", new HashMap<>(Map.of("status", "vip")));

        SelectResults<?> r = query(
                "SELECT * FROM /" + regionName + " WHERE status = 'active' OR status = 'vip'");
        assertEquals(2, r.size(), "OR query (scan fallback) returns both matching rows");
    }

    @Test
    void pdxValuesAreNotDroppedByPushdown() throws Exception {
        // PDX fields live outside the map `value` path the N1QL predicate reads, so the candidate set
        // includes all PDX docs (superset) and the shim's PDX-aware matcher does the real filtering.
        // This is the critical correctness case: pushdown must never drop a true PDX match.
        region.put("a", pdxOrder("active", 100));
        region.put("b", pdxOrder("closed", 50));
        region.put("c", pdxOrder("active", 10));

        SelectResults<?> active = query("SELECT * FROM /" + regionName + " WHERE status = 'active'");
        assertEquals(2, active.size(), "PDX equality matches survive pushdown (superset + re-filter)");
    }

    @Test
    void mixedMapAndPdxRegionStaysCorrect() throws Exception {
        region.put("m1", new HashMap<>(Map.of("status", "active")));
        region.put("m2", new HashMap<>(Map.of("status", "closed")));
        region.put("p1", pdxOrder("active", 1));
        region.put("p2", pdxOrder("closed", 2));

        SelectResults<?> active = query("SELECT * FROM /" + regionName + " WHERE status = 'active'");
        assertEquals(2, active.size(), "one map + one PDX row match, across the mixed region");
    }

    @Test
    void projectionWithPushdownReturnsFieldValues() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 100)));
        region.put("b", new HashMap<>(Map.of("status", "closed", "amount", 50)));
        region.put("c", new HashMap<>(Map.of("status", "active", "amount", 10)));

        SelectResults<?> amounts = query(
                "SELECT e.amount FROM /" + regionName + " e WHERE status = 'active'");
        assertEquals(2, amounts.size(), "projection still applied on pushdown candidates");
        assertEquals(Set.of(100, 10), new HashSet<>(amounts), "projected field values are correct");
    }

    private SelectResults<?> query(String oql) throws Exception {
        return (SelectResults<?>) cache.getQueryService().newQuery(oql).execute();
    }

    private PdxInstance pdxOrder(String status, int amount) {
        return cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", status)
                .writeInt("amount", amount)
                .create();
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
        fail("pushdown shim did not become ready before timeout: " + url);
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
