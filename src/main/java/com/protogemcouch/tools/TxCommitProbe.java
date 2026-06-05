package com.protogemcouch.tools;

import org.apache.geode.DataSerializer;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

/**
 * Dev probe for the COMMIT response (TXCommitMessage):
 * <ol>
 *   <li>Run a real begin/put/commit through a capturing proxy to a real Geode server.</li>
 *   <li>Extract the serialized TXCommitMessage object from the server's reply and deserialize it via
 *       Geode's own {@link DataSerializer} (proving we read the bytes correctly).</li>
 *   <li>Reflectively empty its {@code regions}/{@code farSideEntryOps} lists and re-serialize via
 *       Geode's own {@code toData}, yielding a valid 0-region commit message.</li>
 *   <li>Deserialize that 0-region message back to confirm a real client would accept it, and dump its
 *       hex so the shim can emit the same shape.</li>
 * </ol>
 */
public final class TxCommitProbe {

    private TxCommitProbe() {
    }

    public static void main(String[] args) throws Exception {
        String geodeHost = env("GEODE_HOST", "127.0.0.1");
        int geodePort = Integer.parseInt(env("GEODE_PORT", "40404"));
        int proxyPort = Integer.parseInt(env("PROXY_PORT", "40499"));
        String region = env("REGION", "repl");

        ByteArrayOutputStream s2c = new ByteArrayOutputStream();
        ServerSocket proxy = new ServerSocket(proxyPort);
        Thread acceptor = new Thread(() -> {
            try {
                while (!proxy.isClosed()) {
                    Socket client = proxy.accept();
                    Socket upstream = new Socket(geodeHost, geodePort);
                    pump(client.getInputStream(), upstream.getOutputStream(), null);
                    pump(upstream.getInputStream(), client.getOutputStream(), s2c);
                }
            } catch (IOException ignored) {
                // proxy closed
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
        Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(region);

        if ("1".equals(env("CHECK_BUILT", "0"))) {
            // Validate the shim's own COMMIT reply bytes deserialize as a real client would read them.
            int txId = 7;
            byte[] msg = com.protogemcouch.wire.GemResponseWriter.buildCommitResponse(txId);
            // Skip the 17-byte message header (msgType, len, numParts, txId, flag) + part header
            // (len:int, isObject:byte) to reach the object bytes.
            int partLen = ((msg[17] & 0xff) << 24) | ((msg[18] & 0xff) << 16)
                    | ((msg[19] & 0xff) << 8) | (msg[20] & 0xff);
            byte[] objBytes = new byte[partLen];
            System.arraycopy(msg, 22, objBytes, 0, partLen);
            Object built = DataSerializer.readObject(new DataInputStream(new ByteArrayInputStream(objBytes)));
            System.out.println("=== buildCommitResponse(" + txId + ") object=" + partLen + " bytes ===");
            System.out.println("deserialized: " + built);
            cache.close();
            proxy.close();
            System.exit(0);
        }

        org.apache.geode.cache.CacheTransactionManager txMgr = cache.getCacheTransactionManager();
        txMgr.begin();
        r.put("tx1", "v1");
        r.put("tx2", "v2");
        txMgr.commit();
        Thread.sleep(500);

        byte[] all = s2c.toByteArray();
        // The commit-response object begins with 01 6e (DS_FIXED_ID_BYTE + dsfid 110). The 5 bytes
        // before it are the part: <length:int><isObject=01>.
        int obj = indexOf(all, new byte[] {0x01, 0x6e});
        if (obj < 5) {
            System.out.println("commit object not found");
            return;
        }
        int objLen = readInt(all, obj - 5);
        System.out.println("=== part length=" + objLen + " object starts 01 6e ===");
        byte[] objBytes = new byte[objLen];
        System.arraycopy(all, obj, objBytes, 0, objLen);

        Object o = DataSerializer.readObject(new DataInputStream(new ByteArrayInputStream(objBytes)));
        System.out.println("=== REAL object deserialized: " + o.getClass().getName() + " ===");
        System.out.println(o);

        // Empty the regions so the re-serialized message carries no region content change.
        Field regions = o.getClass().getDeclaredField("regions");
        regions.setAccessible(true);
        regions.set(o, new ArrayList<>());
        try {
            Field fse = o.getClass().getDeclaredField("farSideEntryOps");
            fse.setAccessible(true);
            fse.set(o, new ArrayList<>());
        } catch (NoSuchFieldException ignored) {
            // optional
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataSerializer.writeObject(o, new DataOutputStream(baos));
        byte[] zeroRegion = baos.toByteArray();
        System.out.println("=== 0-region message " + zeroRegion.length + " bytes ===");
        System.out.println(hex(zeroRegion));

        Object back = DataSerializer.readObject(new DataInputStream(new ByteArrayInputStream(zeroRegion)));
        System.out.println("=== 0-region re-deserialized OK: " + back.getClass().getName() + " ===");
        System.out.println(back);

        cache.close();
        proxy.close();
        System.exit(0);
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void pump(InputStream in, OutputStream out, ByteArrayOutputStream capture) {
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
