package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.cache.query.Struct;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end validation of first-cut OQL ({@code SELECT * FROM /region}) against a real Geode client
 * and the live shim + Couchbase: the shim produces the chunked query response and the client
 * assembles it into a {@code SelectResults}.
 */
@Tag("integration")
class ProtoGemCouchQueryIntegrationTest {

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
                .addPoolServer(HOST, SHIM_PORT)
                .create();
        regionName = "q" + UUID.randomUUID().toString().replace("-", "");
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
    void selectStarReturnsAllValues() throws Exception {
        region.put("k1", "v1");
        region.put("k2", "v2");
        region.put("k3", "v3");

        SelectResults<?> results = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName).execute();

        assertEquals(3, results.size(), "all rows returned");
        assertTrue(new HashSet<>(results).containsAll(Set.of("v1", "v2", "v3")), "values match");
    }

    @Test
    void selectStarOnEmptyRegionReturnsNoRows() throws Exception {
        SelectResults<?> results = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName).execute();

        assertEquals(0, results.size(), "empty region yields no rows");
    }

    @Test
    void selectStarWithWhereFiltersByField() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 100)));
        region.put("b", new HashMap<>(Map.of("status", "closed", "amount", 50)));
        region.put("c", new HashMap<>(Map.of("status", "active", "amount", 10)));

        SelectResults<?> active = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " WHERE status = 'active'").execute();
        assertEquals(2, active.size(), "two active rows match");

        SelectResults<?> activeBig = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " WHERE status = 'active' AND amount > 50").execute();
        assertEquals(1, activeBig.size(), "ANDed conditions narrow to one row");
    }

    @Test
    void whereWithOrMatchesEitherGroup() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active")));
        region.put("b", new HashMap<>(Map.of("status", "closed")));
        region.put("c", new HashMap<>(Map.of("status", "vip")));

        SelectResults<?> results = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " WHERE status = 'active' OR status = 'vip'").execute();
        assertEquals(2, results.size(), "OR matches active and vip");
    }

    @Test
    void singleFieldProjectionReturnsFieldValues() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 100)));
        region.put("b", new HashMap<>(Map.of("status", "closed", "amount", 50)));

        SelectResults<?> statuses = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.status FROM /" + regionName + " e").execute();
        assertEquals(2, statuses.size());
        assertTrue(new HashSet<>(statuses).containsAll(Set.of("active", "closed")),
                "projection returns the field values");

        SelectResults<?> bigAmount = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.amount FROM /" + regionName + " e WHERE status = 'active'").execute();
        assertEquals(1, bigAmount.size());
        assertTrue(new HashSet<>(bigAmount).contains(100), "projection + WHERE returns the matching field value");
    }

    @Test
    void multiFieldStructProjection() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 100)));
        region.put("b", new HashMap<>(Map.of("status", "closed", "amount", 50)));

        SelectResults<?> all = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.status, e.amount FROM /" + regionName + " e").execute();
        assertEquals(2, all.size());
        Set<String> pairs = new HashSet<>();
        for (Object o : all) {
            Object[] fields = ((Struct) o).getFieldValues();
            pairs.add(fields[0] + ":" + fields[1]);
        }
        assertTrue(pairs.containsAll(Set.of("active:100", "closed:50")), "structs carry both fields: " + pairs);

        SelectResults<?> filtered = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.status, e.amount FROM /" + regionName + " e WHERE amount > 60").execute();
        assertEquals(1, filtered.size(), "struct projection honors WHERE");
        assertEquals("active", ((Struct) filtered.iterator().next()).getFieldValues()[0]);
    }

    @Test
    void unsupportedQueryRaisesAnError() {
        region.put("k1", "v1");
        // DISTINCT is outside the supported subset and must surface a server error rather than
        // silently mishandling.
        assertThrows(Exception.class, () -> cache.getQueryService()
                .newQuery("SELECT DISTINCT * FROM /" + regionName).execute(),
                "an unsupported query surfaces a server error rather than wrong results");
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
