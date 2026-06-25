package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.pdx.PdxInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 1.3.0-M2 Slice 2 gate: with {@code PDX_PERSISTENCE} on, a PDX type registered on one replica resolves
 * on a <em>different</em> replica that never saw the registration — proving the type↔id mapping is
 * durable + consistent cluster-wide (not a per-instance in-memory counter).
 *
 * <p>Flow: a client writes a PDX value via replica A (registering the type — A allocates a cluster-wide
 * id and persists it to Couchbase). A second client reads the same key via replica B with
 * {@code pdxReadSerialized}: B must serve the type for the embedded id via {@code GET_PDX_TYPE_BY_ID}
 * (loaded from Couchbase) so the client decodes the fields, and B's own query path must resolve the type
 * by id to match a field predicate. Without persistence, B would assign that id to a different type (or
 * have no such id) and decode/querying would fail.
 *
 * <p>Requires both shims from {@code docker-compose.yml} ({@code protogemcouch} at A, and
 * {@code protogemcouch-replica} at B), both with {@code PDX_PERSISTENCE=true}.
 */
@Tag("integration")
class ProtoGemCouchPdxRegistryPersistenceMultiReplicaIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int A_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int A_HEALTH = intEnv("IT_HEALTH_PORT", 8081);
    private static final int B_PORT = intEnv("IT_REPLICA_PORT", 40409);
    private static final int B_HEALTH = intEnv("IT_REPLICA_HEALTH_PORT", 8085);

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + A_HEALTH + "/ready", Duration.ofSeconds(90));
        waitForReady("http://" + HOST + ":" + B_HEALTH + "/ready", Duration.ofSeconds(90));
    }

    @Test
    void pdxTypeRegisteredOnOneReplicaResolvesAndQueriesOnAnother() throws Exception {
        String region = "pxm" + UUID.randomUUID().toString().replace("-", "");

        // Replica A: writing a PDX value registers its type (A allocates a cluster-wide, durable id and
        // persists it to Couchbase). Geode's ClientCache is a per-JVM singleton, so close A before opening
        // B — the type + value live in Couchbase, so B resolves them independently of A still running.
        ClientCache a = cacheFor(A_PORT, false);
        try {
            a.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY).create(region)
                    .put("k1", a.createPdxInstanceFactory("demo.MultiReplicaCustomer")
                            .writeString("status", "active")
                            .writeInt("score", 42)
                            .create());
        } finally {
            a.close();
        }

        // Replica B never saw the registration: it must resolve the embedded type id from Couchbase.
        ClientCache b = cacheFor(B_PORT, true); // pdxReadSerialized -> get() returns a PdxInstance
        try {
            Region<String, Object> rb = b.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                    .create(region);

            Object got = rb.get("k1");
            PdxInstance pdx = assertInstanceOf(PdxInstance.class, got,
                    "replica B returns the PDX value written via replica A");
            assertEquals("active", pdx.getField("status"),
                    "B decoded a field of a PDX type it never registered (type served from Couchbase)");
            assertEquals(42, pdx.getField("score"));

            // B's server-side query path resolves the same type id by load-on-miss to match a predicate.
            SelectResults<?> matched = (SelectResults<?>) b.getQueryService()
                    .newQuery("SELECT * FROM /" + region + " r WHERE r.status = 'active'").execute();
            assertEquals(1, matched.size(),
                    "B's query resolves the PDX type id it never registered and matches the field");

            SelectResults<?> scores = (SelectResults<?>) b.getQueryService()
                    .newQuery("SELECT r.score FROM /" + region + " r WHERE r.status = 'active'").execute();
            assertEquals(42, scores.iterator().next(), "B projects a field of the cross-replica type");
        } finally {
            b.close();
        }
    }

    @Test
    void manyEvolvingVersionsRegisteredOnOneReplicaAllResolveAndQueryOnAnother() throws Exception {
        // Several versions of one class (distinct field layouts => distinct cluster-wide type ids) are
        // registered on replica A; replica B must resolve every one of them by id and query per-version.
        String region = "pxe" + UUID.randomUUID().toString().replace("-", "");
        String cls = "demo.EvolvingMultiReplica";

        ClientCache a = cacheFor(A_PORT, false);
        try {
            Region<String, Object> ra = a.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                    .create(region);
            ra.put("v1", a.createPdxInstanceFactory(cls)
                    .writeString("id", "v1").writeString("name", "Alice").create());
            ra.put("v2", a.createPdxInstanceFactory(cls)
                    .writeString("id", "v2").writeString("name", "Bob").writeString("tier", "gold").create());
            ra.put("v3", a.createPdxInstanceFactory(cls)
                    .writeString("id", "v3").create()); // name removed
        } finally {
            a.close();
        }

        ClientCache b = cacheFor(B_PORT, true);
        try {
            // B never registered any of these versions; each resolves by id from the durable registry.
            assertEquals(3, query(b, "SELECT * FROM /" + region).size(), "all versions resolve on replica B");
            assertEquals(2, query(b, "SELECT * FROM /" + region + " r WHERE r.name != 'none'").size(),
                    "the field common to v1+v2 matches across them; v3 (no name) is absent, not an error");
            assertEquals(1, query(b, "SELECT * FROM /" + region + " r WHERE r.tier = 'gold'").size(),
                    "the evolved field matches only the version that declares it");
            assertEquals("gold", query(b, "SELECT r.tier FROM /" + region + " r WHERE r.tier = 'gold'")
                    .iterator().next(), "B projects the evolved field of the cross-replica type");
        } finally {
            b.close();
        }
    }

    private static SelectResults<?> query(ClientCache cache, String oql) throws Exception {
        return (SelectResults<?>) cache.getQueryService().newQuery(oql).execute();
    }

    private static ClientCache cacheFor(int port, boolean pdxReadSerialized) {
        return new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .setPdxReadSerialized(pdxReadSerialized)
                .addPoolServer(HOST, port)
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
