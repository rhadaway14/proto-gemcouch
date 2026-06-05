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
