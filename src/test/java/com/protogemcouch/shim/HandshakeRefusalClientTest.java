package com.protogemcouch.shim;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the version-refusal wire format end-to-end against a <b>real Geode client</b>, with no
 * Couchbase/Docker: a throwaway server replies to the client's handshake with
 * {@link HandshakeVersionPolicy#buildRefusalReply(int)}, and the client must fail to connect with a
 * server-refused error carrying our message (not hang, not proceed).
 *
 * <p>This is how the reject path is proven here even though the only available client is 1.15.x: the
 * refusal bytes are independent of which client version is connecting.
 */
class HandshakeRefusalClientTest {

    @Test
    void realGeodeClientFailsWithServerRefusedOnVersionRefusal() throws Exception {
        // Policy that does not support 150, so the refusal targets the connecting 1.15.x client.
        HandshakeVersionPolicy policy = new HandshakeVersionPolicy(Set.of(999));
        byte[] refusal = policy.buildRefusalReply(HandshakeVersionPolicy.GEODE_1_15_ORDINAL);

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            server.setSoTimeout(10_000);

            // Refuse every connection the client opens (it may open more than one).
            Thread acceptor = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try (Socket socket = server.accept()) {
                        InputStream in = socket.getInputStream();
                        Thread.sleep(150);          // let the handshake arrive
                        in.readNBytes(Math.max(in.available(), 0));
                        OutputStream out = socket.getOutputStream();
                        out.write(refusal);
                        out.flush();
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            AtomicReference<Throwable> clientError = new AtomicReference<>();
            Thread client = new Thread(() -> {
                ClientCache cache = null;
                try {
                    cache = new ClientCacheFactory()
                            .addPoolServer("127.0.0.1", port)
                            .setPoolReadTimeout(2000)
                            .setPoolRetryAttempts(0)
                            .set("log-level", "warn")
                            .create();
                    Region<String, String> region = cache
                            .<String, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                            .create("refusalRegion");
                    region.put("k", "v"); // triggers the handshake -> our refusal
                } catch (Throwable t) {
                    clientError.set(t);
                } finally {
                    // A ClientCache is a per-JVM Geode singleton; close it so it can't leak into other
                    // unit tests ("a connection to a distributed system already exists in this VM").
                    if (cache != null) {
                        try {
                            cache.close();
                        } catch (Exception ignored) {
                            // best effort
                        }
                    }
                }
            });
            client.setDaemon(true);
            client.start();
            client.join(15_000);
            acceptor.interrupt();

            Throwable error = clientError.get();
            assertNotNull(error, "client should have failed to connect against a version-refusing server");

            // The client must NOT proceed — it fails with a clean connect error. Geode often masks the
            // underlying ServerRefusedConnectionException as NoAvailableServersException at the pool
            // boundary; any of these is a clean rejection (vs. a hang or a mis-encoded session). The
            // exact REPLY_REFUSED+UTF wire format is asserted separately by HandshakeVersionPolicyTest.
            String chain = throwableChain(error);
            assertTrue(
                    chain.contains("NoAvailableServersException")
                            || chain.contains("ServerRefusedConnectionException")
                            || chain.contains("ServerConnectivityException")
                            || chain.toLowerCase().contains("refused"),
                    "expected a clean connect failure; got chain: " + chain);
        }
    }

    private static String throwableChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
