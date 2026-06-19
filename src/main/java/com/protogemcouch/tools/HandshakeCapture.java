package com.protogemcouch.tools;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HexFormat;

/**
 * Dev capture tool: records the exact bytes a real Geode client sends in its op-connection handshake,
 * so we can locate the client version ordinal within it (for protocol version negotiation).
 *
 * <p>No Couchbase / shim needed — it opens a throwaway {@link ServerSocket}, points a real Geode
 * {@code ClientCache} at it, and prints the hex of the handshake the client sends before it blocks
 * waiting for a reply (which we never send). Run:
 *
 * <pre>mvn -o compile exec:java -Dexec.mainClass=com.protogemcouch.tools.HandshakeCapture</pre>
 *
 * <p>Layout reference (CommunicationMode + ClientSideHandshakeImpl.write): byte 0 = communication mode,
 * bytes 1..4 = client read-timeout (int), then the handshake body whose first version field is the
 * client {@code Version} ordinal written via {@code StaticSerialization.writeOrdinal} (1 byte if the
 * ordinal &lt;= 127, else token byte 0xFF followed by a 2-byte short).
 */
public final class HandshakeCapture {

    private HandshakeCapture() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("capture.port", 41999);

        try (ServerSocket server = new ServerSocket(port)) {
            Thread client = new Thread(() -> {
                try {
                    ClientCache cache = new ClientCacheFactory()
                            .addPoolServer("127.0.0.1", port)
                            .setPoolReadTimeout(2000)
                            .setPoolRetryAttempts(0)
                            .set("log-level", "warn")
                            .create();
                    Region<String, String> region = cache
                            .<String, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                            .create("captureRegion");
                    region.put("k", "v"); // forces a pool connection -> handshake
                } catch (Throwable ignored) {
                    // expected: we never reply, so the client eventually errors out
                }
            });
            client.setDaemon(true);
            client.start();

            try (Socket socket = server.accept()) {
                Thread.sleep(800); // let the whole handshake arrive (it may come in >1 write)
                InputStream in = socket.getInputStream();
                int available = in.available();
                byte[] buf = new byte[Math.max(available, 1)];
                int n = in.read(buf);
                byte[] handshake = new byte[Math.max(n, 0)];
                System.arraycopy(buf, 0, handshake, 0, handshake.length);

                String hex = HexFormat.of().formatHex(handshake);
                System.out.println("HANDSHAKE_LEN " + handshake.length);
                System.out.println("HANDSHAKE_HEX " + hex);
                if (handshake.length > 0) {
                    System.out.println("MODE_BYTE " + (handshake[0] & 0xff));
                }
            }
        }
        System.exit(0);
    }
}
