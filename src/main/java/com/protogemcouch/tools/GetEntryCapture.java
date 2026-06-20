package com.protogemcouch.tools;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

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
 * Dev tool: capture the exact wire bytes a real Geode server returns for {@code Region.getEntry(key)}
 * (GET_ENTRY, opcode 89), to nail the {@code EntrySnapshot} reply shape for the shim. It runs a logging
 * TCP proxy between a Geode client and the real server, seeds one key, calls {@code getEntry} for a
 * present key and an absent key through the proxy, and dumps the server→client bytes for each (the
 * serialized EntrySnapshot / the null form) as hex.
 *
 * <p>Usage (against the geode/docker-compose.yml server on 40404):
 * <pre>java -cp target/protogemcouch.jar com.protogemcouch.tools.GetEntryCapture</pre>
 * Env overrides: GEODE_HOST, GEODE_PORT (40404), PROXY_PORT (40499), REGION (helloWorld).
 */
public final class GetEntryCapture {

    private GetEntryCapture() {
    }

    public static void main(String[] args) throws Exception {
        String geodeHost = env("GEODE_HOST", "127.0.0.1");
        int geodePort = Integer.parseInt(env("GEODE_PORT", "40404"));
        int proxyPort = Integer.parseInt(env("PROXY_PORT", "40499"));
        String region = env("REGION", "helloWorld");

        List<ByteArrayOutputStream> serverToClient = Collections.synchronizedList(new ArrayList<>());
        List<ByteArrayOutputStream> clientToServer = Collections.synchronizedList(new ArrayList<>());

        ServerSocket proxy = new ServerSocket(proxyPort);
        Thread acceptor = new Thread(() -> {
            try {
                while (!proxy.isClosed()) {
                    Socket client = proxy.accept();
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

        System.out.println("=== connecting via PROXY to 127.0.0.1:" + proxyPort + " ===");
        // Single-hop ENABLED (the client default): this makes the client send TX_FAILOVER (opcode 88)
        // before a transactional getEntry, so the capture includes op 88 and its reply.
        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer("127.0.0.1", proxyPort)
                .create();
        Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(region);
        // Start clean.
        java.util.Set<String> existing = new java.util.HashSet<>(r.keySetOnServer());
        if (!existing.isEmpty()) {
            r.removeAll(existing);
        }
        r.put("k1", "v1");

        System.out.println("\n########## NON-TX getEntry (PROXY region) ##########");
        captureGetEntry(r, "k1", serverToClient, clientToServer);
        captureGetEntry(r, "absent", serverToClient, clientToServer);

        System.out.println("\n########## IN-TX getEntry (PROXY region) ##########");
        org.apache.geode.cache.CacheTransactionManager txMgr = cache.getCacheTransactionManager();
        txMgr.begin();
        captureGetEntry(r, "k1", serverToClient, clientToServer);
        captureGetEntry(r, "absent", serverToClient, clientToServer);
        txMgr.commit();

        cache.close();
        proxy.close();
        System.exit(0);
    }

    private static void captureGetEntry(Region<String, Object> r, String key,
                                        List<ByteArrayOutputStream> serverToClient,
                                        List<ByteArrayOutputStream> clientToServer) throws InterruptedException {
        Map<ByteArrayOutputStream, Integer> beforeS2c = new IdentityHashMap<>();
        Map<ByteArrayOutputStream, Integer> beforeC2s = new IdentityHashMap<>();
        synchronized (serverToClient) {
            for (ByteArrayOutputStream s : serverToClient) {
                beforeS2c.put(s, s.size());
            }
        }
        synchronized (clientToServer) {
            for (ByteArrayOutputStream s : clientToServer) {
                beforeC2s.put(s, s.size());
            }
        }

        System.out.println("\n=== getEntry(\"" + key + "\") ===");
        Region.Entry<String, Object> entry = r.getEntry(key);
        System.out.println("=== returned entry=" + entry
                + (entry != null ? " value=" + entry.getValue() : "") + " ===");

        Thread.sleep(500);

        dumpDelta("CLIENT->SERVER", clientToServer, beforeC2s);
        dumpDelta("SERVER->CLIENT", serverToClient, beforeS2c);
    }

    private static void dumpDelta(String label, List<ByteArrayOutputStream> streams,
                                  Map<ByteArrayOutputStream, Integer> before) {
        synchronized (streams) {
            int i = 0;
            for (ByteArrayOutputStream s : streams) {
                byte[] all = s.toByteArray();
                int from = before.getOrDefault(s, 0);
                if (all.length > from) {
                    byte[] delta = new byte[all.length - from];
                    System.arraycopy(all, from, delta, 0, delta.length);
                    System.out.println("--- CONN " + i + " " + label + " " + delta.length + " bytes ---");
                    System.out.println(hex(delta));
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
                    synchronized (capture) {
                        capture.write(buf, 0, n);
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
