package com.protogemcouch.tools;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.InterestResultPolicy;

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
 * Dev tool: capture the client-subscription (server&rarr;client push) protocol of a real Geode server,
 * to scope register-interest/subscriptions for the shim. It runs a logging TCP proxy, opens a
 * subscription-enabled client, registers interest, calls readyForEvents, and then waits — dumping the
 * full bytes of every proxied connection (the op connection starts with mode byte 0x64=100, the
 * subscription connection with 0x65=101). Trigger a mutation from a separate member (e.g.
 * {@code gfsh -e "put --region=/helloWorld --key=k1 --value=v1"}) while it waits to capture the
 * pushed event (CLIENT_MARKER + LOCAL_CREATE/UPDATE/DESTROY/INVALIDATE).
 *
 * <p>Usage: {@code java -cp target/protogemcouch.jar com.protogemcouch.tools.SubscriptionCapture}
 * Env: GEODE_HOST (127.0.0.1), GEODE_PORT (40404), PROXY_PORT (40499), REGION (helloWorld),
 * WAIT_SECONDS (25).
 */
public final class SubscriptionCapture {

    private SubscriptionCapture() {
    }

    public static void main(String[] args) throws Exception {
        String geodeHost = env("GEODE_HOST", "127.0.0.1");
        int geodePort = Integer.parseInt(env("GEODE_PORT", "40404"));
        int proxyPort = Integer.parseInt(env("PROXY_PORT", "40499"));
        String region = env("REGION", "helloWorld");
        int waitSeconds = Integer.parseInt(env("WAIT_SECONDS", "25"));

        List<Conn> conns = Collections.synchronizedList(new ArrayList<>());
        ServerSocket proxy = new ServerSocket(proxyPort);
        Thread acceptor = new Thread(() -> {
            try {
                while (!proxy.isClosed()) {
                    Socket client = proxy.accept();
                    Socket upstream = new Socket(geodeHost, geodePort);
                    Conn conn = new Conn(conns.size());
                    conns.add(conn);
                    System.err.println("[proxy] accepted connection #" + conn.id);
                    pump(client.getInputStream(), upstream.getOutputStream(), conn.c2s, conn, "c2s");
                    pump(upstream.getInputStream(), client.getOutputStream(), conn.s2c, conn, "s2c");
                }
            } catch (IOException e) {
                System.err.println("[proxy] acceptor stopped: " + e);
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(true)               // opens the subscription connection
                .setPoolSubscriptionRedundancy(0)
                .setPoolSubscriptionAckInterval(100)
                .setPoolPRSingleHopEnabled(false)
                .addPoolServer("127.0.0.1", proxyPort)
                .create();
        Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .create(region);

        System.out.println("=== registerInterest(ALL_KEYS, KEYS_VALUES) ===");
        r.registerInterest("ALL_KEYS", InterestResultPolicy.KEYS_VALUES);
        // Non-durable clients receive events automatically after registerInterest (no readyForEvents).

        System.out.println("=== waiting " + waitSeconds + "s for pushed events; trigger a mutation now "
                + "(e.g. gfsh put --region=/" + region + " --key=sub1 --value=hello) ===");
        for (int s = 0; s < waitSeconds; s++) {
            Thread.sleep(1000);
        }

        synchronized (conns) {
            for (Conn c : conns) {
                byte[] c2s = c.c2s.toByteArray();
                byte[] s2c = c.s2c.toByteArray();
                String mode = c2s.length > 0 ? Integer.toString(c2s[0] & 0xff) : "?";
                System.out.println("=== CONN #" + c.id + " firstByte(mode)=" + mode
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

    private static void pump(InputStream in, OutputStream out, ByteArrayOutputStream capture, Conn conn, String dir) {
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
                System.err.println("[proxy #" + conn.id + " " + dir + "] closed: " + e);
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
