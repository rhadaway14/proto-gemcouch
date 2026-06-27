package com.protogemcouch.tools;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.SelectResults;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev tool: capture the exact wire bytes a real Geode server returns for a query, to nail the
 * chunked query-response format for the shim. It runs a logging TCP proxy between a Geode client and
 * the real server, seeds a region, executes the query through the proxy, and dumps the server→client
 * bytes that arrived during the query (the chunked response) as hex.
 *
 * <p>Usage (against the geode/docker-compose.yml server on 40404):
 * <pre>java -cp target/protogemcouch.jar com.protogemcouch.tools.GeodeQueryCapture</pre>
 * Env overrides: GEODE_HOST, GEODE_PORT (40404), PROXY_PORT (40499), REGION (helloWorld), QUERY.
 */
public final class GeodeQueryCapture {

    private GeodeQueryCapture() {
    }

    public static void main(String[] args) throws Exception {
        String geodeHost = env("GEODE_HOST", "127.0.0.1");
        int geodePort = Integer.parseInt(env("GEODE_PORT", "40404"));
        int proxyPort = Integer.parseInt(env("PROXY_PORT", "40499"));
        String region = env("REGION", "helloWorld");
        String query = env("QUERY", "SELECT * FROM /" + region);

        boolean direct = "1".equals(env("CAPTURE_DIRECT", "0"));
        List<ByteArrayOutputStream> serverToClient = Collections.synchronizedList(new ArrayList<>());
        List<ByteArrayOutputStream> clientToServer = Collections.synchronizedList(new ArrayList<>());
        ServerSocket proxy = null;

        if (!direct) {
            proxy = new ServerSocket(proxyPort);
            ServerSocket bound = proxy;
            Thread acceptor = new Thread(() -> {
                try {
                    while (!bound.isClosed()) {
                        Socket client = bound.accept();
                        Socket upstream = new Socket(geodeHost, geodePort);
                        System.err.println("[proxy] accepted client, connected upstream " + geodeHost + ":" + geodePort);
                        ByteArrayOutputStream s2c = new ByteArrayOutputStream();
                        ByteArrayOutputStream c2s = new ByteArrayOutputStream();
                        serverToClient.add(s2c);
                        clientToServer.add(c2s);
                        pump(client.getInputStream(), upstream.getOutputStream(), c2s, "c->s");
                        pump(upstream.getInputStream(), client.getOutputStream(), s2c, "s->c");
                    }
                } catch (IOException e) {
                    System.err.println("[proxy] acceptor stopped: " + e);
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String poolHost = "127.0.0.1";
        int poolPort = direct ? geodePort : proxyPort;
        System.out.println("=== connecting via " + (direct ? "DIRECT" : "PROXY") + " to " + poolHost + ":" + poolPort + " ===");
        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .setPoolPRSingleHopEnabled(false) // force ops through the configured (proxied) server
                .addPoolServer(poolHost, poolPort)
                .create();
        Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(region);
        // Start from an empty region (helloWorld persists across runs on the real server).
        java.util.Set<String> existing = new java.util.HashSet<>(r.keySetOnServer());
        if (!existing.isEmpty()) {
            r.removeAll(existing);
        }
        if ("1".equals(env("DISTINCT_CAPTURE", "0"))) {
            runDistinctCapture(cache, r, region, serverToClient, proxy);
            return;
        }
        if ("1".equals(env("GROUP_BY_CAPTURE", "0"))) {
            runGroupByCapture(cache, r, region, serverToClient, proxy);
            return;
        }

        if ("1".equals(env("SEED_TX", "0"))) {
            org.apache.geode.cache.CacheTransactionManager txMgr = cache.getCacheTransactionManager();
            int txOps = Integer.parseInt(env("SEED_COUNT", "2"));
            txMgr.begin();
            for (int n = 1; n <= txOps; n++) {
                r.put("tx" + n, "v" + n);
            }
            if ("1".equals(env("ROLLBACK", "0"))) {
                System.out.println("=== rolling back tx (" + txOps + " ops) ===");
                txMgr.rollback();
                System.out.println("=== tx rolled back ===");
            } else {
                System.out.println("=== committing tx (" + txOps + " ops) ===");
                txMgr.commit();
                System.out.println("=== tx committed ===");
            }
            Thread.sleep(500);
            synchronized (clientToServer) {
                int i = 0;
                for (ByteArrayOutputStream s : clientToServer) {
                    byte[] all = s.toByteArray();
                    System.out.println("=== CONN " + (i++) + " CLIENT->SERVER " + all.length + " bytes ===");
                    System.out.println(hex(all));
                }
            }
            synchronized (serverToClient) {
                int i = 0;
                for (ByteArrayOutputStream s : serverToClient) {
                    byte[] all = s.toByteArray();
                    System.out.println("=== CONN " + (i++) + " SERVER->CLIENT " + all.length + " bytes ===");
                    System.out.println(hex(all));
                }
            }
            cache.close();
            if (proxy != null) {
                proxy.close();
            }
            System.exit(0);
        }

        int seed = Integer.parseInt(env("SEED_COUNT", "2"));
        boolean maps = "1".equals(env("SEED_MAPS", "0"));
        boolean pdx = "1".equals(env("SEED_PDX", "0"));
        if (pdx) {
            for (int n = 1; n <= seed; n++) {
                org.apache.geode.pdx.PdxInstance instance = cache.createPdxInstanceFactory("demo.Order")
                        .writeString("status", n % 2 == 0 ? "active" : "closed")
                        .writeInt("amount", n * 10)
                        .create();
                r.put("k" + n, instance);
            }
        }
        for (int n = 1; n <= seed && !pdx; n++) {
            if (maps) {
                java.util.HashMap<String, Object> m = new java.util.HashMap<>();
                m.put("status", n % 2 == 0 ? "active" : "closed");
                m.put("amount", n * 10);
                r.put("k" + n, m);
            } else {
                r.put("k" + n, "v" + n);
            }
        }

        // Snapshot how many server->client bytes each connection has seen, so we can dump only the
        // bytes produced by the query that follows.
        Map<ByteArrayOutputStream, Integer> before = new IdentityHashMap<>();
        synchronized (serverToClient) {
            for (ByteArrayOutputStream s : serverToClient) {
                before.put(s, s.size());
            }
        }

        System.out.println("=== executing: " + query + " ===");
        String rawParams = env("QUERY_PARAMS", "");
        SelectResults<?> results;
        if (rawParams.isBlank()) {
            results = (SelectResults<?>) cache.getQueryService().newQuery(query).execute();
        } else {
            // Comma-separated bind parameters; numeric tokens become Integer, otherwise String.
            String[] tokens = rawParams.split(",");
            Object[] params = new Object[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                String t = tokens[i].trim();
                try {
                    params[i] = Integer.valueOf(t);
                } catch (NumberFormatException nfe) {
                    params[i] = t;
                }
            }
            System.out.println("=== params=" + java.util.Arrays.toString(params) + " ===");
            results = (SelectResults<?>) cache.getQueryService().newQuery(query).execute(params);
        }
        System.out.println("=== result size=" + results.size() + " values=" + new ArrayList<>(results) + " ===");

        Thread.sleep(750);

        synchronized (serverToClient) {
            int i = 0;
            for (ByteArrayOutputStream s : serverToClient) {
                byte[] all = s.toByteArray();
                int from = before.getOrDefault(s, 0);
                if (all.length > from) {
                    byte[] delta = new byte[all.length - from];
                    System.arraycopy(all, from, delta, 0, delta.length);
                    System.out.println("=== CONN " + i + " QUERY-RESPONSE " + delta.length + " bytes ===");
                    System.out.println(hex(delta));
                    analyzeChunks(delta);
                }
                i++;
            }
        }

        if ("1".equals(env("DUMP_C2S", "0"))) {
            synchronized (clientToServer) {
                int i = 0;
                for (ByteArrayOutputStream s : clientToServer) {
                    byte[] all = s.toByteArray();
                    System.out.println("=== CONN " + (i++) + " CLIENT->SERVER " + all.length + " bytes ===");
                    System.out.println(hex(all));
                }
            }
        }

        cache.close();
        if (proxy != null) {
            proxy.close();
        }
        System.exit(0);
    }

    /**
     * GROUP_BY_CAPTURE=1 mode: seed 4 map entries, run GROUP BY queries and dump the response bytes.
     * Queries:
     *   1. SELECT status, COUNT(*) FROM /r GROUP BY status
     *   2. SELECT status, SUM(amount) FROM /r GROUP BY status
     *   3. SELECT status, COUNT(*) FROM /r GROUP BY status HAVING COUNT(*) > 1
     *   4. SELECT status, COUNT(*) FROM /r WHERE amount > 10 GROUP BY status
     *   5. SELECT status, category, COUNT(*) FROM /r GROUP BY status, category  (multi-key)
     */
    /**
     * GROUP_BY_CAPTURE=1 mode: seed 4 PDX entries (so field access works in Geode OQL), run GROUP BY
     * queries, and dump the response bytes per query.
     * <p>
     * Queries:
     *   1. SELECT status, COUNT(*) FROM /r e GROUP BY status                (2 groups, count per group)
     *   2. SELECT status, SUM(amount) FROM /r e GROUP BY status             (sum per group)
     *   3. SELECT status, MIN(amount) FROM /r e GROUP BY status             (min per group)
     *   4. SELECT status, MAX(amount) FROM /r e GROUP BY status             (max per group)
     *   5. SELECT status, AVG(amount) FROM /r e GROUP BY status             (avg per group)
     *   6. SELECT status, COUNT(*) FROM /r e WHERE amount > 10 GROUP BY status (with WHERE)
     *   7. SELECT status, category, COUNT(*) FROM /r e GROUP BY status, category (multi-key)
     *   8. SELECT status, COUNT(*) FROM /r e GROUP BY status HAVING COUNT(*) > 1 (HAVING — may fail on server)
     */
    private static void runDistinctCapture(ClientCache cache, Region<String, Object> r, String region,
                                           List<ByteArrayOutputStream> serverToClient,
                                           ServerSocket proxy) throws Exception {
        // Seed 5 PDX rows: 3 distinct statuses, 2 distinct categories, with deliberate duplicates
        // so DISTINCT actually collapses rows.
        //   k1=active/A/10, k2=active/B/20, k3=closed/A/30, k4=active/A/40, k5=pending/C/50
        cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", "active").writeString("category", "A").writeInt("amount", 10)
                .create(); // factory warmup
        r.put("k1", cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", "active").writeString("category", "A").writeInt("amount", 10).create());
        r.put("k2", cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", "active").writeString("category", "B").writeInt("amount", 20).create());
        r.put("k3", cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", "closed").writeString("category", "A").writeInt("amount", 30).create());
        r.put("k4", cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", "active").writeString("category", "A").writeInt("amount", 40).create());
        r.put("k5", cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", "pending").writeString("category", "C").writeInt("amount", 50).create());

        List<String> queries = new ArrayList<>(List.of(
                // Single-field DISTINCT — 3 distinct status values
                "SELECT DISTINCT status FROM /" + region + " e",
                // Multi-field DISTINCT struct — 4 distinct (status, category) pairs
                "SELECT DISTINCT e.status, e.category FROM /" + region + " e",
                // DISTINCT with WHERE — only active rows, 2 distinct categories
                "SELECT DISTINCT status FROM /" + region + " e WHERE category = 'A'",
                // DISTINCT * — should return all 5 rows (all different PDX instances)
                "SELECT DISTINCT * FROM /" + region + " e",
                // DISTINCT on numeric field — 5 distinct amounts
                "SELECT DISTINCT amount FROM /" + region + " e"
        ));

        for (String q : queries) {
            try {
                runCapture(cache, q, serverToClient);
            } catch (Exception e) {
                System.out.println("=== QUERY FAILED: " + q + " => " + e.getMessage() + " ===");
            }
        }

        cache.close();
        if (proxy != null) {
            proxy.close();
        }
        System.exit(0);
    }

    private static void runGroupByCapture(ClientCache cache, Region<String, Object> r, String region,
                                          List<ByteArrayOutputStream> serverToClient,
                                          ServerSocket proxy) throws Exception {
        // Seed with PDX so Geode OQL can resolve named fields
        cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", "active").writeString("category", "A").writeInt("amount", 10)
                .create();  // factory warmup — first create doesn't write to region
        r.put("k1", cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", "active").writeString("category", "A").writeInt("amount", 10).create());
        r.put("k2", cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", "active").writeString("category", "B").writeInt("amount", 20).create());
        r.put("k3", cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", "closed").writeString("category", "A").writeInt("amount", 30).create());
        r.put("k4", cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", "closed").writeString("category", "A").writeInt("amount", 40).create());

        List<String> queries = new ArrayList<>(List.of(
                "SELECT status, COUNT(*) FROM /" + region + " e GROUP BY status",
                "SELECT status, SUM(amount) FROM /" + region + " e GROUP BY status",
                "SELECT status, MIN(amount) FROM /" + region + " e GROUP BY status",
                "SELECT status, MAX(amount) FROM /" + region + " e GROUP BY status",
                "SELECT status, AVG(amount) FROM /" + region + " e GROUP BY status",
                "SELECT status, COUNT(*) FROM /" + region + " e WHERE amount > 10 GROUP BY status",
                "SELECT status, category, COUNT(*) FROM /" + region + " e GROUP BY status, category",
                "SELECT status, COUNT(*) FROM /" + region + " e GROUP BY status HAVING COUNT(*) > 1"
        ));

        for (String q : queries) {
            try {
                runCapture(cache, q, serverToClient);
            } catch (Exception e) {
                System.out.println("=== QUERY FAILED: " + q + " => " + e.getMessage() + " ===");
            }
        }

        cache.close();
        if (proxy != null) {
            proxy.close();
        }
        System.exit(0);
    }

    private static void runCapture(ClientCache cache, String query,
                                   List<ByteArrayOutputStream> serverToClient) throws Exception {
        Map<ByteArrayOutputStream, Integer> before = new IdentityHashMap<>();
        synchronized (serverToClient) {
            for (ByteArrayOutputStream s : serverToClient) {
                before.put(s, s.size());
            }
        }

        System.out.println("=== executing: " + query + " ===");
        SelectResults<?> results = (SelectResults<?>) cache.getQueryService().newQuery(query).execute();
        System.out.println("=== result size=" + results.size() + " values=" + new ArrayList<>(results) + " ===");

        Thread.sleep(750);

        synchronized (serverToClient) {
            int i = 0;
            for (ByteArrayOutputStream s : serverToClient) {
                byte[] all = s.toByteArray();
                int from = before.getOrDefault(s, 0);
                if (all.length > from) {
                    byte[] delta = new byte[all.length - from];
                    System.arraycopy(all, from, delta, 0, delta.length);
                    System.out.println("=== CONN " + i + " QUERY-RESPONSE " + delta.length + " bytes ===");
                    System.out.println(hex(delta));
                    analyzeChunks(delta);
                }
                i++;
            }
        }
    }

    private static void pump(InputStream in, OutputStream out, ByteArrayOutputStream capture, String dir) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                    if (capture != null) {
                        synchronized (capture) {
                            capture.write(buf, 0, n);
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("[proxy " + dir + "] closed: " + e);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** Parse a chunked query-response: 12-byte header, then chunks of [chunkLength(int), lastChunk(byte), payload]. */
    private static void analyzeChunks(byte[] b) {
        if (b.length < 12) {
            return;
        }
        int msgType = i32(b, 0);
        int numParts = i32(b, 4);
        System.out.println("--- chunk analysis: msgType=" + msgType + " numParts=" + numParts
                + " txId=" + Integer.toHexString(i32(b, 8)));
        int o = 12;
        int chunk = 0;
        while (o + 5 <= b.length) {
            int chunkLen = i32(b, o);
            int last = b[o + 4] & 0xff;
            System.out.println("    chunk#" + chunk + " off=" + o + " chunkLength=" + chunkLen + " lastChunk=" + last);
            o += 5 + chunkLen;
            chunk++;
            if (chunk > 50) {
                System.out.println("    ...stopping");
                break;
            }
        }
        System.out.println("--- chunks total=" + chunk + " endOffset=" + o + " (len=" + b.length + ")");
    }

    private static int i32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
