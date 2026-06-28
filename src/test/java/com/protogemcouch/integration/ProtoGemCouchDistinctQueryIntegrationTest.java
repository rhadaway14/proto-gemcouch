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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end validation of {@code SELECT DISTINCT} queries against the live shim + Couchbase via a
 * real Geode client. Verifies that duplicate rows are deduplicated and that the correct wire shape
 * (Set / StructSet) is returned to the client.
 */
@Tag("integration")
class ProtoGemCouchDistinctQueryIntegrationTest {

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
        regionName = "dist" + UUID.randomUUID().toString().replace("-", "");
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) cache.close();
    }

    // --- single-field DISTINCT ---

    @Test
    void distinctSingleFieldDeduplicates() throws Exception {
        // 5 rows: active, active, closed, active, pending → 3 distinct status values
        seedOrders();
        SelectResults<?> results = query("SELECT DISTINCT status FROM /" + regionName);

        assertEquals(3, results.size(), "3 distinct status values");
        Set<String> values = new HashSet<>();
        for (Object r : results) values.add((String) r);
        assertTrue(values.contains("active"));
        assertTrue(values.contains("closed"));
        assertTrue(values.contains("pending"));
    }

    @Test
    void distinctSingleFieldEmptyRegionReturnsNoRows() throws Exception {
        SelectResults<?> results = query("SELECT DISTINCT status FROM /" + regionName);
        assertEquals(0, results.size(), "empty region produces no distinct rows");
    }

    @Test
    void distinctSingleFieldWithWhere() throws Exception {
        seedOrders();
        // WHERE category='A': k1(active/A), k3(closed/A), k4(active/A) → distinct status = active, closed
        SelectResults<?> results = query(
                "SELECT DISTINCT status FROM /" + regionName + " WHERE category = 'A'");
        assertEquals(2, results.size(), "2 distinct statuses in category A");
        Set<String> values = new HashSet<>();
        for (Object r : results) values.add((String) r);
        assertTrue(values.contains("active"));
        assertTrue(values.contains("closed"));
    }

    @Test
    void distinctSingleFieldWithLimit() throws Exception {
        seedOrders();
        // LIMIT 2 applied after dedup: at most 2 distinct values returned
        SelectResults<?> results = query("SELECT DISTINCT status FROM /" + regionName + " LIMIT 2");
        assertTrue(results.size() <= 2, "LIMIT caps distinct result count");
    }

    // --- multi-field DISTINCT (StructSet) ---

    @Test
    void distinctMultiFieldDeduplicates() throws Exception {
        // k1=(active,A), k2=(active,B), k3=(closed,A), k4=(active,A) dup, k5=(pending,C)
        seedOrders();
        SelectResults<?> results = query(
                "SELECT DISTINCT status, category FROM /" + regionName);

        // 4 distinct pairs: (active,A), (active,B), (closed,A), (pending,C)
        assertEquals(4, results.size(), "4 distinct (status, category) pairs");
        Set<String> keys = new HashSet<>();
        for (Object r : results) {
            Struct s = (Struct) r;
            keys.add(s.get("status") + "/" + s.get("category"));
        }
        assertTrue(keys.contains("active/A"));
        assertTrue(keys.contains("active/B"));
        assertTrue(keys.contains("closed/A"));
        assertTrue(keys.contains("pending/C"));
    }

    @Test
    void distinctMultiFieldWithAlias() throws Exception {
        seedOrders();
        SelectResults<?> results = query(
                "SELECT DISTINCT e.status, e.category FROM /" + regionName + " e");
        assertEquals(4, results.size());
        for (Object r : results) {
            assertInstanceOf(Struct.class, r, "multi-field DISTINCT rows are Struct instances");
        }
    }

    // --- DISTINCT over PDX values ---

    @Test
    void distinctOverPdxValuesDeduplicates() throws Exception {
        region.put("k1", pdxOrder("active", "A"));
        region.put("k2", pdxOrder("active", "B"));
        region.put("k3", pdxOrder("closed", "A"));
        region.put("k4", pdxOrder("active", "A")); // dup
        region.put("k5", pdxOrder("pending", "C"));

        SelectResults<?> results = query("SELECT DISTINCT status FROM /" + regionName);
        assertEquals(3, results.size(), "3 distinct status values in PDX docs");
    }

    // --- helpers ---

    private void seedOrders() {
        // k1=active/A, k2=active/B, k3=closed/A, k4=active/A (dup), k5=pending/C
        region.put("k1", order("active", "A"));
        region.put("k2", order("active", "B"));
        region.put("k3", order("closed", "A"));
        region.put("k4", order("active", "A"));
        region.put("k5", order("pending", "C"));
    }

    private static Map<String, Object> order(String status, String category) {
        HashMap<String, Object> m = new HashMap<>();
        m.put("status", status);
        m.put("category", category);
        return m;
    }

    private PdxInstance pdxOrder(String status, String category) {
        return cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", status)
                .writeString("category", category)
                .create();
    }

    @SuppressWarnings("unchecked")
    private SelectResults<?> query(String oql) throws Exception {
        return (SelectResults<?>) cache.getQueryService().newQuery(oql).execute();
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
                    if (connection.getResponseCode() == 200) return;
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(500); } catch (InterruptedException e) {
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
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
