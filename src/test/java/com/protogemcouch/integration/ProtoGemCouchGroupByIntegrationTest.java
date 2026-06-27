package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.cache.query.Struct;
import org.apache.geode.pdx.PdxInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end validation of OQL GROUP BY queries against a real Geode client and the live shim +
 * Couchbase. Each GROUP BY result is a SelectResults of Struct rows, with field names matching
 * the Geode 1.15 column-naming convention (COUNT → "0", SUM/AVG/MIN/MAX → field name).
 */
@Tag("integration")
class ProtoGemCouchGroupByIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

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
        regionName = "grp" + UUID.randomUUID().toString().replace("-", "");
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    // --- GROUP BY + COUNT(*) ---

    @Test
    void groupByStatusCountStar() throws Exception {
        seedOrders();
        SelectResults<?> results = query(
                "SELECT status, COUNT(*) FROM /" + regionName + " GROUP BY status");

        assertEquals(2, results.size(), "two distinct status groups");
        Map<String, Integer> counts = collectCounts(results, "status", "0");
        assertEquals(2, counts.get("active"), "active count");
        assertEquals(2, counts.get("closed"), "closed count");
    }

    @Test
    void groupByStatusCountStarWithWhere() throws Exception {
        seedOrders();
        SelectResults<?> results = query(
                "SELECT status, COUNT(*) FROM /" + regionName
                        + " WHERE category = 'A' GROUP BY status");

        // WHERE category='A': k1(active/A), k3(closed/A), k4(closed/A)
        assertEquals(2, results.size(), "two groups after WHERE filter");
        Map<String, Integer> counts = collectCounts(results, "status", "0");
        assertEquals(1, counts.get("active"), "one active row with category A");
        assertEquals(2, counts.get("closed"), "two closed rows with category A");
    }

    @Test
    void groupByStatusCountStarEmptyRegion() throws Exception {
        SelectResults<?> results = query(
                "SELECT status, COUNT(*) FROM /" + regionName + " GROUP BY status");
        assertEquals(0, results.size(), "empty region produces no groups");
    }

    // --- GROUP BY + COUNT(field) ---

    @Test
    void groupByStatusCountField() throws Exception {
        seedOrders();
        SelectResults<?> results = query(
                "SELECT status, COUNT(amount) FROM /" + regionName + " GROUP BY status");

        assertEquals(2, results.size());
        Map<String, Integer> counts = collectCounts(results, "status", "0");
        assertEquals(2, counts.get("active"));
        assertEquals(2, counts.get("closed"));
    }

    // --- GROUP BY + SUM ---

    @Test
    void groupByStatusSum() throws Exception {
        seedOrders();
        SelectResults<?> results = query(
                "SELECT status, SUM(amount) FROM /" + regionName + " GROUP BY status");

        assertEquals(2, results.size());
        Map<String, Number> sums = collectNumbers(results, "status", "amount");
        assertEquals(30, sums.get("active").intValue(), "active: 10+20=30");
        assertEquals(70, sums.get("closed").intValue(), "closed: 30+40=70");
    }

    // --- GROUP BY + AVG ---

    @Test
    void groupByStatusAvg() throws Exception {
        seedOrders();
        SelectResults<?> results = query(
                "SELECT status, AVG(amount) FROM /" + regionName + " GROUP BY status");

        assertEquals(2, results.size());
        Map<String, Number> avgs = collectNumbers(results, "status", "amount");
        // Integer-arithmetic AVG: (10+20)/2=15, (30+40)/2=35
        assertEquals(15, avgs.get("active").intValue(), "active avg = 15");
        assertEquals(35, avgs.get("closed").intValue(), "closed avg = 35");
    }

    // --- GROUP BY + MIN ---

    @Test
    void groupByStatusMin() throws Exception {
        seedOrders();
        SelectResults<?> results = query(
                "SELECT status, MIN(amount) FROM /" + regionName + " GROUP BY status");

        assertEquals(2, results.size());
        Map<String, Number> mins = collectNumbers(results, "status", "amount");
        assertEquals(10, mins.get("active").intValue(), "active min = 10");
        assertEquals(30, mins.get("closed").intValue(), "closed min = 30");
    }

    // --- GROUP BY + MAX ---

    @Test
    void groupByStatusMax() throws Exception {
        seedOrders();
        SelectResults<?> results = query(
                "SELECT status, MAX(amount) FROM /" + regionName + " GROUP BY status");

        assertEquals(2, results.size());
        Map<String, Number> maxs = collectNumbers(results, "status", "amount");
        assertEquals(20, maxs.get("active").intValue(), "active max = 20");
        assertEquals(40, maxs.get("closed").intValue(), "closed max = 40");
    }

    // --- GROUP BY over PDX values ---

    @Test
    void groupByOverPdxValues() throws Exception {
        region.put("k1", pdxOrder("active", "A", 10));
        region.put("k2", pdxOrder("active", "B", 20));
        region.put("k3", pdxOrder("closed", "A", 30));
        region.put("k4", pdxOrder("closed", "A", 40));

        SelectResults<?> countResults = query(
                "SELECT status, COUNT(*) FROM /" + regionName + " GROUP BY status");
        assertEquals(2, countResults.size(), "two groups over PDX values");
        Map<String, Integer> counts = collectCounts(countResults, "status", "0");
        assertEquals(2, counts.get("active"));
        assertEquals(2, counts.get("closed"));

        SelectResults<?> sumResults = query(
                "SELECT status, SUM(amount) FROM /" + regionName + " GROUP BY status");
        Map<String, Number> sums = collectNumbers(sumResults, "status", "amount");
        assertEquals(30, sums.get("active").intValue());
        assertEquals(70, sums.get("closed").intValue());
    }

    // --- GROUP BY with alias ---

    @Test
    void groupByWithAlias() throws Exception {
        seedOrders();
        // Geode OQL allows an alias on the FROM; the GROUP BY key references the alias-stripped field.
        SelectResults<?> results = query(
                "SELECT o.status, COUNT(*) FROM /" + regionName + " o GROUP BY o.status");
        assertEquals(2, results.size());
    }

    // --- helpers ---

    private void seedOrders() {
        // k1=active/A/10, k2=active/B/20, k3=closed/A/30, k4=closed/A/40
        region.put("k1", mapOrder("active", "A", 10));
        region.put("k2", mapOrder("active", "B", 20));
        region.put("k3", mapOrder("closed", "A", 30));
        region.put("k4", mapOrder("closed", "A", 40));
    }

    private static Map<String, Object> mapOrder(String status, String category, int amount) {
        HashMap<String, Object> m = new HashMap<>();
        m.put("status", status);
        m.put("category", category);
        m.put("amount", amount);
        return m;
    }

    private PdxInstance pdxOrder(String status, String category, int amount) {
        return cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", status)
                .writeString("category", category)
                .writeInt("amount", amount)
                .create();
    }

    @SuppressWarnings("unchecked")
    private SelectResults<?> query(String oql) throws Exception {
        return (SelectResults<?>) cache.getQueryService().newQuery(oql).execute();
    }

    /** Collect GROUP BY results into a map of groupKey → count-column value (Integer). */
    private static Map<String, Integer> collectCounts(SelectResults<?> results,
                                                       String keyField, String countField) {
        Map<String, Integer> map = new HashMap<>();
        for (Object row : results) {
            Struct s = (Struct) row;
            String key = (String) s.get(keyField);
            Number count = (Number) s.get(countField);
            map.put(key, count.intValue());
        }
        return map;
    }

    /** Collect GROUP BY results into a map of groupKey → aggregate numeric value. */
    private static Map<String, Number> collectNumbers(SelectResults<?> results,
                                                       String keyField, String aggField) {
        Map<String, Number> map = new HashMap<>();
        for (Object row : results) {
            Struct s = (Struct) row;
            String key = (String) s.get(keyField);
            Number value = (Number) s.get(aggField);
            map.put(key, value);
        }
        return map;
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
