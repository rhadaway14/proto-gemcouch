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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end validation of OQL aggregate functions (COUNT/SUM/MIN/MAX/AVG) against a real Geode
 * client and the live shim + Couchbase. Each aggregate result is returned as a one-element
 * SelectResults whose single element is the scalar value.
 */
@Tag("integration")
class ProtoGemCouchAggregateQueryIntegrationTest {

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
        regionName = "agg" + UUID.randomUUID().toString().replace("-", "");
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    // --- COUNT(*) ---

    @Test
    void countStarReturnsNumberOfRows() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 10)));
        region.put("b", new HashMap<>(Map.of("status", "closed", "amount", 20)));
        region.put("c", new HashMap<>(Map.of("status", "active", "amount", 30)));

        SelectResults<?> results = query("SELECT COUNT(*) FROM /" + regionName);
        assertEquals(3, scalar(results, Integer.class).intValue(), "COUNT(*) counts all rows");
    }

    @Test
    void countStarOverEmptyRegionIsZero() throws Exception {
        SelectResults<?> results = query("SELECT COUNT(*) FROM /" + regionName);
        assertEquals(0, scalar(results, Integer.class).intValue(), "COUNT(*) on empty region is 0");
    }

    @Test
    void countStarWithWhereCountsMatchingRows() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 10)));
        region.put("b", new HashMap<>(Map.of("status", "closed", "amount", 20)));
        region.put("c", new HashMap<>(Map.of("status", "active", "amount", 30)));

        SelectResults<?> results = query("SELECT COUNT(*) FROM /" + regionName + " WHERE status = 'active'");
        assertEquals(2, scalar(results, Integer.class).intValue(), "COUNT(*) WHERE counts matching rows only");
    }

    // --- COUNT(field) ---

    @Test
    void countFieldSkipsNullAndAbsentFields() throws Exception {
        region.put("a", new HashMap<>(Map.of("amount", 10)));
        region.put("b", mapWithNull("amount"));          // explicit null
        region.put("c", new HashMap<>(Map.of("other", "x"))); // amount absent

        SelectResults<?> results = query("SELECT COUNT(amount) FROM /" + regionName);
        assertEquals(1, scalar(results, Integer.class).intValue(), "COUNT(field) skips null/absent");
    }

    @Test
    void countFieldCountsNonNullValues() throws Exception {
        region.put("a", new HashMap<>(Map.of("amount", 10)));
        region.put("b", new HashMap<>(Map.of("amount", 20)));
        region.put("c", new HashMap<>(Map.of("amount", 30)));

        SelectResults<?> results = query("SELECT COUNT(amount) FROM /" + regionName);
        assertEquals(3, scalar(results, Integer.class).intValue(), "COUNT(field) counts non-null values");
    }

    // --- SUM ---

    @Test
    void sumReturnsDoubleSum() throws Exception {
        region.put("a", new HashMap<>(Map.of("amount", 10)));
        region.put("b", new HashMap<>(Map.of("amount", 20)));
        region.put("c", new HashMap<>(Map.of("amount", 30)));
        region.put("d", new HashMap<>(Map.of("amount", 40)));

        SelectResults<?> results = query("SELECT SUM(amount) FROM /" + regionName);
        assertEquals(100.0, scalar(results, Number.class).doubleValue(), 1e-9, "SUM returns total");
    }

    @Test
    void sumWithWhereFiltersBySumOfMatchingRows() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 10)));
        region.put("b", new HashMap<>(Map.of("status", "closed", "amount", 20)));
        region.put("c", new HashMap<>(Map.of("status", "active", "amount", 30)));

        SelectResults<?> results = query(
                "SELECT SUM(amount) FROM /" + regionName + " WHERE status = 'active'");
        assertEquals(40.0, scalar(results, Number.class).doubleValue(), 1e-9,
                "SUM WHERE sums only the matching rows");
    }

    @Test
    void sumOverEmptyRegionIsZero() throws Exception {
        SelectResults<?> results = query("SELECT SUM(amount) FROM /" + regionName);
        assertEquals(0.0, scalar(results, Number.class).doubleValue(), 1e-9,
                "SUM on empty region is 0.0");
    }

    // --- AVG ---

    @Test
    void avgReturnsMean() throws Exception {
        region.put("a", new HashMap<>(Map.of("amount", 10)));
        region.put("b", new HashMap<>(Map.of("amount", 20)));
        region.put("c", new HashMap<>(Map.of("amount", 30)));
        region.put("d", new HashMap<>(Map.of("amount", 40)));

        SelectResults<?> results = query("SELECT AVG(amount) FROM /" + regionName);
        assertEquals(25.0, scalar(results, Number.class).doubleValue(), 1e-9, "AVG returns mean");
    }

    @Test
    void avgOverEmptyRegionIsNull() throws Exception {
        SelectResults<?> results = query("SELECT AVG(amount) FROM /" + regionName);
        assertEquals(1, results.size(), "AVG on empty region returns a one-element result set");
        assertNull(results.iterator().next(), "AVG on empty region is null");
    }

    // --- MIN / MAX ---

    @Test
    void minReturnsMinimumNumericValue() throws Exception {
        region.put("a", new HashMap<>(Map.of("amount", 10)));
        region.put("b", new HashMap<>(Map.of("amount", 5)));
        region.put("c", new HashMap<>(Map.of("amount", 20)));

        SelectResults<?> results = query("SELECT MIN(amount) FROM /" + regionName);
        assertEquals(5, scalar(results, Number.class).intValue(), "MIN returns the smallest value");
    }

    @Test
    void maxReturnsMaximumNumericValue() throws Exception {
        region.put("a", new HashMap<>(Map.of("amount", 10)));
        region.put("b", new HashMap<>(Map.of("amount", 5)));
        region.put("c", new HashMap<>(Map.of("amount", 20)));

        SelectResults<?> results = query("SELECT MAX(amount) FROM /" + regionName);
        assertEquals(20, scalar(results, Number.class).intValue(), "MAX returns the largest value");
    }

    @Test
    void minMaxOverEmptyRegionIsNull() throws Exception {
        SelectResults<?> minResults = query("SELECT MIN(amount) FROM /" + regionName);
        assertEquals(1, minResults.size(), "MIN on empty region returns a one-element result set");
        assertNull(minResults.iterator().next(), "MIN on empty region is null");

        SelectResults<?> maxResults = query("SELECT MAX(amount) FROM /" + regionName);
        assertNull(maxResults.iterator().next(), "MAX on empty region is null");
    }

    @Test
    void minMaxOnStringField() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "closed")));
        region.put("b", new HashMap<>(Map.of("status", "active")));
        region.put("c", new HashMap<>(Map.of("status", "pending")));

        SelectResults<?> minResults = query("SELECT MIN(status) FROM /" + regionName);
        assertEquals("active", minResults.iterator().next(), "MIN on string returns lexicographic minimum");

        SelectResults<?> maxResults = query("SELECT MAX(status) FROM /" + regionName);
        assertEquals("pending", maxResults.iterator().next(), "MAX on string returns lexicographic maximum");
    }

    // --- PDX values ---

    @Test
    void aggregateOverPdxValues() throws Exception {
        region.put("a", pdxOrder("active", 10));
        region.put("b", pdxOrder("closed", 20));
        region.put("c", pdxOrder("active", 30));

        SelectResults<?> count = query("SELECT COUNT(*) FROM /" + regionName);
        assertEquals(3, scalar(count, Integer.class).intValue(), "COUNT(*) over PDX values");

        SelectResults<?> countActive = query(
                "SELECT COUNT(*) FROM /" + regionName + " WHERE status = 'active'");
        assertEquals(2, scalar(countActive, Integer.class).intValue(),
                "COUNT(*) WHERE on PDX field");

        SelectResults<?> sum = query("SELECT SUM(amount) FROM /" + regionName);
        assertEquals(60.0, scalar(sum, Number.class).doubleValue(), 1e-9, "SUM over PDX field");

        SelectResults<?> avg = query("SELECT AVG(amount) FROM /" + regionName);
        assertEquals(20.0, scalar(avg, Number.class).doubleValue(), 1e-9, "AVG over PDX field");

        SelectResults<?> min = query("SELECT MIN(amount) FROM /" + regionName);
        assertEquals(10, scalar(min, Number.class).intValue(), "MIN over PDX field");

        SelectResults<?> max = query("SELECT MAX(amount) FROM /" + regionName);
        assertEquals(30, scalar(max, Number.class).intValue(), "MAX over PDX field");
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private SelectResults<?> query(String oql) throws Exception {
        return (SelectResults<?>) cache.getQueryService().newQuery(oql).execute();
    }

    private <T> T scalar(SelectResults<?> results, Class<T> type) {
        assertEquals(1, results.size(), "aggregate result set must have exactly one element");
        Object value = results.iterator().next();
        assertNotNull(value, "aggregate scalar must not be null (use assertNull for empty-set cases)");
        return type.cast(value);
    }

    private PdxInstance pdxOrder(String status, int amount) {
        return cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", status)
                .writeInt("amount", amount)
                .create();
    }

    private static Map<String, Object> mapWithNull(String key) {
        HashMap<String, Object> m = new HashMap<>();
        m.put(key, null);
        return m;
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
