package com.protogemcouch.tools;

import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.CqAttributesFactory;
import org.apache.geode.cache.query.CqEvent;
import org.apache.geode.cache.query.CqListener;
import org.apache.geode.cache.query.CqQuery;
import org.apache.geode.cache.query.QueryService;

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
 * Dev tool: capture the Continuous Query (CQ) protocol of a real Geode server — the EXECUTECQ request
 * (opcode 42/43) and the CQ event pushed down the subscription feed (mode 101). Runs a logging proxy,
 * registers a CQ with a CqListener, then waits while a separate member mutates a matching entry
 * (e.g. {@code gfsh put --region=/cq --key=k --value=v}); dumps every proxied connection's bytes.
 *
 * <p>Usage: {@code java -cp target/protogemcouch.jar com.protogemcouch.tools.CqCapture}
 * Env: GEODE_HOST (127.0.0.1), GEODE_PORT (40404), PROXY_PORT (40499), REGION (cq),
 * CQ (SELECT * FROM /<region>), WAIT_SECONDS (22), WITH_IR (0/1).
 */
public final class CqCapture {

    private CqCapture() {
    }

    public static void main(String[] args) throws Exception {
        String geodeHost = env("GEODE_HOST", "127.0.0.1");
        int geodePort = Integer.parseInt(env("GEODE_PORT", "40404"));
        int proxyPort = Integer.parseInt(env("PROXY_PORT", "40499"));
        String region = env("REGION", "cq");
        String cqText = env("CQ", "SELECT * FROM /" + region);
        int waitSeconds = Integer.parseInt(env("WAIT_SECONDS", "22"));
        boolean withIr = "1".equals(env("WITH_IR", "0"));

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
                .setPoolSubscriptionEnabled(true)
                .setPoolSubscriptionRedundancy(0)
                .setPoolSubscriptionAckInterval(100)
                .setPoolPRSingleHopEnabled(false)
                .addPoolServer("127.0.0.1", proxyPort)
                .create();
        cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY).create(region);
        Thread.sleep(3000); // let the subscription queue (feed) establish before creating the CQ

        QueryService qs = cache.getQueryService();
        CqAttributesFactory caf = new CqAttributesFactory();
        caf.addCqListener(new CqListener() {
            @Override
            public void onEvent(CqEvent event) {
                System.out.println("=== CqListener.onEvent op=" + event.getQueryOperation()
                        + " key=" + event.getKey() + " value=" + event.getNewValue() + " ===");
            }

            @Override
            public void onError(CqEvent event) {
            }

            @Override
            public void close() {
            }
        });
        CqQuery cq = qs.newCq("myCq", cqText, caf.create());
        System.out.println("=== executing CQ '" + cqText + "' withIR=" + withIr + " ===");
        if (withIr) {
            cq.executeWithInitialResults();
        } else {
            cq.execute();
        }

        System.out.println("=== waiting " + waitSeconds + "s; mutate a matching entry now ===");
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
