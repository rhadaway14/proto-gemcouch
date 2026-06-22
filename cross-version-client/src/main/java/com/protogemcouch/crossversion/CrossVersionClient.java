package com.protogemcouch.crossversion;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.pdx.PdxInstance;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A real Geode client exercising the shim's core wire surface — CRUD, bulk {@code putAll}/{@code getAll},
 * key metadata ({@code size}/{@code containsKey}), and OQL queries over both map and PDX values — using
 * <strong>only the public Geode client API</strong>. Built and run against a chosen Geode client version
 * (the {@code geode.client.version} property), it connects to a shim that is itself built/run at its
 * pinned version, so a green run confirms cross-version wire interoperability.
 *
 * <p>Exits {@code 0} on success, {@code 1} on the first failed assertion (so it can drive a CI matrix).
 *
 * <pre>Args: host port   (defaults 127.0.0.1 40415)</pre>
 */
public final class CrossVersionClient {

    private CrossVersionClient() {
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 40415;
        String geodeVersion = System.getProperty("geode.client.version", "unknown");

        System.out.println("CrossVersionClient: geode client " + geodeVersion + " -> " + host + ":" + port);
        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .setPdxReadSerialized(true) // SELECT * on PDX returns PdxInstances (no domain classes)
                .addPoolServer(host, port)
                .create();
        try {
            String regionName = "xv" + UUID.randomUUID().toString().replace("-", "");
            Region<String, Object> region = cache.<String, Object>createClientRegionFactory(
                    ClientRegionShortcut.PROXY).create(regionName);

            // --- CRUD ---
            region.put("k1", "v1");
            check("get round-trips", "v1".equals(region.get("k1")));
            check("containsKeyOnServer", region.containsKeyOnServer("k1"));
            region.remove("k1");
            check("remove clears the value", region.get("k1") == null);

            // --- bulk putAll / getAll + size ---
            Map<String, Object> bulk = new LinkedHashMap<>();
            for (int i = 0; i < 25; i++) {
                bulk.put("b" + i, "val" + i);
            }
            region.putAll(bulk);
            Map<String, Object> got = region.getAll(bulk.keySet());
            check("getAll returns every bulk entry", got.size() == 25 && "val7".equals(got.get("b7")));
            check("sizeOnServer counts the bulk entries", region.sizeOnServer() >= 25);

            // --- OQL over map values ---
            region.put("m1", new HashMap<>(Map.of("status", "active", "amount", 100)));
            region.put("m2", new HashMap<>(Map.of("status", "closed", "amount", 50)));
            SelectResults<?> active = (SelectResults<?>) cache.getQueryService()
                    .newQuery("SELECT * FROM /" + regionName + " r WHERE r.status = 'active'").execute();
            check("OQL filters map field", active.size() == 1);

            // --- OQL over a PDX value (PDX-aware field resolution end-to-end across versions) ---
            region.put("p1", cache.createPdxInstanceFactory("demo.Order")
                    .writeString("status", "active").writeInt("amount", 200).create());
            region.put("p2", cache.createPdxInstanceFactory("demo.Order")
                    .writeString("status", "closed").writeInt("amount", 10).create());
            SelectResults<?> pdxActive = (SelectResults<?>) cache.getQueryService()
                    .newQuery("SELECT * FROM /" + regionName + " r WHERE r.status = 'active' AND r.amount > 100")
                    .execute();
            check("OQL filters PDX object fields", pdxActive.size() == 1);
            Object first = pdxActive.iterator().next();
            check("PDX read-serialized round-trip", first instanceof PdxInstance
                    && "active".equals(((PdxInstance) first).getField("status")));

            // --- single-field projection assembled correctly ---
            SelectResults<?> statuses = (SelectResults<?>) cache.getQueryService()
                    .newQuery("SELECT e.status FROM /" + regionName + " e WHERE e.status = 'closed'").execute();
            check("projection returns field values", new HashSet<>(statuses).contains("closed"));

            System.out.println("CrossVersionClient: PASS (geode client " + geodeVersion + ")");
        } catch (Exception e) {
            System.err.println("CrossVersionClient: FAIL — " + e);
            cache.close();
            System.exit(1);
        }
        cache.close();
        System.exit(0);
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            throw new IllegalStateException("assertion failed: " + what);
        }
        System.out.println("  ok: " + what);
    }
}
