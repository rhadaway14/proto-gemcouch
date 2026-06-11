package com.protogemcouch.tools;

import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.execute.Execution;
import org.apache.geode.cache.execute.FunctionService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dev tool: capture the server-side function-execution protocol of a real Geode server — the
 * EXECUTE_FUNCTION (62) / EXECUTE_REGION_FUNCTION (59) request and the server's reply (here, the
 * "function not registered" error the server returns for an unknown function id, which is the
 * response shape the shim replays to gracefully reject functions it cannot run).
 *
 * <p>Env: GEODE_HOST (127.0.0.1), GEODE_PORT (40404), PROXY_PORT (40499), REGION (helloWorld),
 * FN_ID (shimUnknownFn), ON_REGION (0/1 — onRegion vs onServer).
 */
public final class FunctionCapture {

    private FunctionCapture() {
    }

    public static void main(String[] args) throws Exception {
        String geodeHost = env("GEODE_HOST", "127.0.0.1");
        int geodePort = Integer.parseInt(env("GEODE_PORT", "40404"));
        int proxyPort = Integer.parseInt(env("PROXY_PORT", "40499"));
        String region = env("REGION", "helloWorld");
        String fnId = env("FN_ID", "shimUnknownFn");
        boolean onRegion = "1".equals(env("ON_REGION", "0"));

        List<Conn> conns = Collections.synchronizedList(new ArrayList<>());
        ServerSocket proxy = new ServerSocket(proxyPort);
        Thread acceptor = new Thread(() -> {
            try {
                while (!proxy.isClosed()) {
                    Socket client = proxy.accept();
                    Socket upstream = new Socket(geodeHost, geodePort);
                    Conn conn = new Conn(conns.size());
                    conns.add(conn);
                    pump(client.getInputStream(), upstream.getOutputStream(), conn.c2s);
                    pump(upstream.getInputStream(), client.getOutputStream(), conn.s2c);
                }
            } catch (IOException ignored) {
                // closed
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .setPoolPRSingleHopEnabled(false)
                .addPoolServer("127.0.0.1", proxyPort)
                .create();
        org.apache.geode.cache.Region<String, Object> r =
                cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY).create(region);

        Execution<?, ?, ?> execution = onRegion ? FunctionService.onRegion(r) : FunctionService.onServer(cache);
        System.out.println("=== executing " + (onRegion ? "onRegion" : "onServer") + " function id=" + fnId + " ===");
        try {
            execution.execute(fnId).getResult();
            System.out.println("=== function returned (unexpected) ===");
        } catch (Throwable t) {
            System.out.println("=== client got: " + t.getClass().getName() + ": " + t.getMessage() + " ===");
        }
        Thread.sleep(500);

        synchronized (conns) {
            for (Conn c : conns) {
                byte[] c2s = c.c2s.toByteArray();
                byte[] s2c = c.s2c.toByteArray();
                System.out.println("=== CONN #" + c.id + " firstByte=" + (c2s.length > 0 ? (c2s[0] & 0xff) : -1)
                        + " c2s=" + c2s.length + "B s2c=" + s2c.length + "B ===");
                System.out.println("  C2S: " + hex(c2s));
                System.out.println("  S2C: " + hex(s2c));
            }
        }
        cache.close();
        proxy.close();
        System.exit(0);
    }

    private static final class Conn {
        final int id;
        final ByteArrayOutputStream c2s = new ByteArrayOutputStream();
        final ByteArrayOutputStream s2c = new ByteArrayOutputStream();

        Conn(int id) {
            this.id = id;
        }
    }

    private static void pump(InputStream in, OutputStream out, ByteArrayOutputStream capture) {
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
            } catch (IOException ignored) {
                // closed
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
