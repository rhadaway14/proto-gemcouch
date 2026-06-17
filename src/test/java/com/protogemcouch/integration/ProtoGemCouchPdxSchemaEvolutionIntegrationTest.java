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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates PDX schema evolution end-to-end against a real Geode 1.15 client: several <em>versions</em>
 * of one logical class (the same PDX class name with different field sets — a field added, a field
 * removed) coexist in a region as distinct PDX types, each round-trips with its own fields, and OQL
 * resolves fields per instance using that instance's own version:
 * <ul>
 *   <li>a query on a field common to several versions matches across them;</li>
 *   <li>a query on an evolved field matches only the versions that declare it — older instances that
 *       lack the field are correctly treated as absent (no match), not errors;</li>
 *   <li>projecting an evolved field returns its value from the versions that have it.</li>
 * </ul>
 */
@Tag("integration")
class ProtoGemCouchPdxSchemaEvolutionIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private static final String CLASS = "com.example.evolve.Customer";

    private ClientCache cache;
    private Region<String, Object> region;
    private String regionName;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .setPdxReadSerialized(true) // SELECT */get returns PdxInstances (no domain classes needed)
                .addPoolServer(HOST, SHIM_PORT)
                .create();
        regionName = "pe" + UUID.randomUUID().toString().replace("-", "");
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    // v1 = {id, name}; v2 = {id, name, tier} (field added); v3 = {id} (name removed). Same class name,
    // three distinct field layouts => three distinct PDX types (versions).
    private PdxInstance v1(String id, String name) {
        return cache.createPdxInstanceFactory(CLASS).writeString("id", id).writeString("name", name).create();
    }

    private PdxInstance v2(String id, String name, String tier) {
        return cache.createPdxInstanceFactory(CLASS)
                .writeString("id", id).writeString("name", name).writeString("tier", tier).create();
    }

    private PdxInstance v3(String id) {
        return cache.createPdxInstanceFactory(CLASS).writeString("id", id).create();
    }

    @Test
    void versionsCoexistRoundTripAndQueryResolvesFieldsPerVersion() throws Exception {
        region.put("c1", v1("c1", "Alice"));
        region.put("c2", v2("c2", "Bob", "gold"));
        region.put("c3", v2("c3", "Carol", "silver"));
        region.put("c4", v3("c4"));

        // (a) each instance round-trips with exactly its own version's fields.
        PdxInstance r1 = assertInstanceOf(PdxInstance.class, region.get("c1"), "c1");
        assertEquals("Alice", r1.getField("name"));
        assertFalse(r1.hasField("tier"), "v1 has no tier field");

        PdxInstance r2 = assertInstanceOf(PdxInstance.class, region.get("c2"), "c2");
        assertTrue(r2.hasField("tier"), "v2 has the added tier field");
        assertEquals("gold", r2.getField("tier"));

        PdxInstance r4 = assertInstanceOf(PdxInstance.class, region.get("c4"), "c4");
        assertEquals("c4", r4.getField("id"));
        assertFalse(r4.hasField("name"), "v3 removed the name field");

        // (b) all versions coexist in the region.
        assertEquals(4, query("SELECT * FROM /" + regionName).size(), "all four versioned instances");

        // (c) a field common to v1 + v2 matches across those versions; v3 (no name) is excluded.
        assertEquals(3, query("SELECT * FROM /" + regionName + " WHERE name != 'none'").size(),
                "name is present on v1 + v2 instances (c1,c2,c3), absent on v3 (c4)");

        // (d) an evolved field matches ONLY the versions that declare it — older/other versions that
        //     lack 'tier' are correctly treated as absent, not errors.
        assertEquals(1, query("SELECT * FROM /" + regionName + " WHERE tier = 'gold'").size(),
                "only the v2 instance with tier=gold matches");
        assertEquals(2, query("SELECT * FROM /" + regionName + " WHERE tier = 'gold' OR tier = 'silver'").size(),
                "both v2 instances match; v1/v3 (no tier) excluded");

        // (e) projecting the evolved field returns its values from the versions that have it.
        SelectResults<?> tiers = query("SELECT t.tier FROM /" + regionName + " t WHERE tier != 'none'");
        assertEquals(2, tiers.size(), "tier projection only yields the versions that declare tier");
        assertTrue(new HashSet<>(tiers).containsAll(Set.of("gold", "silver")), "projected tier values: " + tiers);
    }

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
                    if (connection.getResponseCode() == 200) {
                        return;
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {
                // retry until the deadline
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for shim readiness");
            }
        }
        fail("shim did not become ready at " + url + " within " + timeout);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
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
