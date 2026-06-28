package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.SelectResults;
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
 * End-to-end validation of parenthesized AND/OR in WHERE clauses. Verifies that the DNF expansion
 * in the shim produces the same result set as the equivalent flat unparenthesized query.
 */
@Tag("integration")
class ProtoGemCouchParenWhereIntegrationTest {

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
        regionName = "pw" + UUID.randomUUID().toString().replace("-", "");
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) cache.close();
    }

    // k1=active/A/10, k2=active/B/20, k3=closed/A/30, k4=closed/B/40, k5=pending/A/50

    @Test
    void trivialParensAroundAndGroup() throws Exception {
        seedRows();
        // (status='active' AND category='A') is the same as status='active' AND category='A'
        SelectResults<?> r = query(
                "SELECT * FROM /" + regionName + " WHERE (status = 'active' AND category = 'A')");
        assertEquals(1, r.size(), "only k1 matches active+A");
    }

    @Test
    void andWithParenthesizedOrClause() throws Exception {
        seedRows();
        // status='active' AND (category='A' OR category='B') matches k1 and k2
        SelectResults<?> r = query(
                "SELECT * FROM /" + regionName
                        + " WHERE status = 'active' AND (category = 'A' OR category = 'B')");
        assertEquals(2, r.size(), "both active rows match");
    }

    @Test
    void parenthesizedAndClauseOrPlainCondition() throws Exception {
        seedRows();
        // (status='active' AND category='A') OR status='pending' → k1 + k5
        SelectResults<?> r = query(
                "SELECT * FROM /" + regionName
                        + " WHERE (status = 'active' AND category = 'A') OR status = 'pending'");
        assertEquals(2, r.size(), "k1 (active/A) and k5 (pending) match");
    }

    @Test
    void crossProductOfParenthesizedOrGroups() throws Exception {
        seedRows();
        // (status='active' OR status='closed') AND (category='A' OR category='B')
        // DNF: active+A, active+B, closed+A, closed+B → k1,k2,k3,k4
        SelectResults<?> r = query(
                "SELECT * FROM /" + regionName
                        + " WHERE (status = 'active' OR status = 'closed') AND (category = 'A' OR category = 'B')");
        assertEquals(4, r.size(), "all non-pending rows match");
    }

    @Test
    void doubleNestedParens() throws Exception {
        seedRows();
        // ((status='active')) is the same as status='active'
        SelectResults<?> r = query(
                "SELECT * FROM /" + regionName + " WHERE ((status = 'active'))");
        assertEquals(2, r.size(), "k1 and k2 match active");
    }

    @Test
    void numericComparisonInsideParens() throws Exception {
        seedRows();
        // (amount > 25) AND (status = 'closed' OR status = 'pending') → k3(30),k4(40),k5(50)
        SelectResults<?> r = query(
                "SELECT * FROM /" + regionName
                        + " WHERE (amount > 25) AND (status = 'closed' OR status = 'pending')");
        assertEquals(3, r.size(), "k3, k4, and k5 match");
    }

    @Test
    void parenWhereWithDistinct() throws Exception {
        seedRows();
        // SELECT DISTINCT status WHERE (status='active' OR status='closed') → 2 distinct values
        SelectResults<?> r = query(
                "SELECT DISTINCT status FROM /" + regionName
                        + " WHERE (status = 'active' OR status = 'closed')");
        assertEquals(2, r.size(), "2 distinct status values");
    }

    // --- helpers ---

    private void seedRows() {
        region.put("k1", row("active",  "A", 10));
        region.put("k2", row("active",  "B", 20));
        region.put("k3", row("closed",  "A", 30));
        region.put("k4", row("closed",  "B", 40));
        region.put("k5", row("pending", "A", 50));
    }

    private static Map<String, Object> row(String status, String category, int amount) {
        HashMap<String, Object> m = new HashMap<>();
        m.put("status", status);
        m.put("category", category);
        m.put("amount", amount);
        return m;
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
