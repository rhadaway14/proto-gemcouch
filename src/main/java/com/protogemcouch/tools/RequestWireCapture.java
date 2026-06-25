package com.protogemcouch.tools;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.CqAttributesFactory;
import org.apache.geode.cache.query.QueryService;

import com.protogemcouch.wire.MessageTypes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev tool: capture the real Geode 1.15 client REQUEST bytes for each core opcode, to byte-lock the
 * shim's request-decode surface (the companion to the reply-side golden-wire library). It runs a
 * logging TCP proxy in front of the running shim, performs each operation through it, and — for each
 * op — isolates the request message of the expected opcode from the client→server delta and writes its
 * hex to {@code <OUT_DIR>/<opcode>.hex} (default {@code src/test/resources/golden-wire-requests}).
 *
 * <p>Usage (shim stack up on 40405):
 * <pre>java -cp target/protogemcouch.jar com.protogemcouch.tools.RequestWireCapture</pre>
 * Env: SHIM_HOST (127.0.0.1), SHIM_PORT (40405), PROXY_PORT (40499), OUT_DIR.
 */
public final class RequestWireCapture {

    private static final int HEADER_SIZE = 17; // type(4)+len(4)+parts(4)+txid(4)+flags(1)

    private final Map<String, byte[]> fixtures = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        new RequestWireCapture().run();
    }

    private void run() throws Exception {
        String shimHost = env("SHIM_HOST", "127.0.0.1");
        int shimPort = Integer.parseInt(env("SHIM_PORT", "40405"));
        int proxyPort = Integer.parseInt(env("PROXY_PORT", "40499"));
        Path outDir = Path.of(env("OUT_DIR", "src/test/resources/golden-wire-requests"));

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ServerSocket proxy = new ServerSocket(proxyPort);
        Thread acceptor = new Thread(() -> {
            try {
                while (!proxy.isClosed()) {
                    Socket client = proxy.accept();
                    Socket upstream = new Socket(shimHost, shimPort);
                    pump(client.getInputStream(), upstream.getOutputStream(), sink); // c->s (captured)
                    pump(upstream.getInputStream(), client.getOutputStream(), null); // s->c
                }
            } catch (IOException ignored) {
                // closed
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(true) // needed for register-interest / CQ
                .addPoolServer("127.0.0.1", proxyPort)
                .create();
        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("helloWorld");

        // Let the handshake + region setup traffic settle; each op records its own pre-op offset.
        Thread.sleep(500);

        // PDX registry registration ops (1.3.0-M3): writing a PDX value sends GET_PDX_ID_FOR_TYPE before
        // the PUT; a PDX value with an enum field also sends GET_PDX_ID_FOR_ENUM. Captured here (with
        // fresh types, before the client caches them). The bulk/reverse PDX ops — GET_PDX_TYPES/ENUMS,
        // GET_PDX_TYPE_BY_ID, GET_PDX_ENUM_BY_ID — are driven by internal registry sync (not a plain
        // client write), so they stay covered by tools.GetPdxRegistryCapture + the PDX integration suites.
        capture("get-pdx-id-for-type", MessageTypes.GET_PDX_ID_FOR_TYPE, sink,
                () -> region.put("pdxk", cache.createPdxInstanceFactory("demo.CaptureType")
                        .writeString("status", "active").writeInt("amount", 7).create()));
        capture("get-pdx-id-for-enum", MessageTypes.GET_PDX_ID_FOR_ENUM, sink,
                () -> region.put("pdxe", cache.createPdxInstanceFactory("demo.CaptureEnum")
                        .writeObject("day", java.time.DayOfWeek.MONDAY).create()));

        capture("put", MessageTypes.PUT, sink, () -> region.put("k1", "v1"));
        capture("get", MessageTypes.GET, sink, () -> region.get("k1"));
        capture("contains-key", MessageTypes.CONTAINS_KEY, sink, () -> region.containsKeyOnServer("k1"));
        capture("size", MessageTypes.SIZE, sink, () -> region.sizeOnServer());
        capture("key-set", MessageTypes.KEY_SET, sink, () -> region.keySetOnServer());
        capture("put-all", MessageTypes.PUT_ALL, sink, () -> region.putAll(Map.of("k2", "v2", "k3", "v3")));
        capture("get-all", MessageTypes.GET_ALL_70, sink, () -> region.getAll(List.of("k1", "k2")));
        capture("remove", MessageTypes.REMOVE, sink, () -> region.remove("k3"));
        capture("query", MessageTypes.QUERY, sink, () -> exec(cache, "SELECT * FROM /helloWorld"));
        capture("query-with-parameters", MessageTypes.QUERY_WITH_PARAMETERS, sink,
                () -> execParams(cache, "SELECT * FROM /helloWorld WHERE $1 = $1", 1));
        capture("invalidate", MessageTypes.INVALIDATE, sink, () -> region.invalidate("k1"));
        // Subscription-connection ops: capture CQ + unregister BEFORE the key-list register (whose reply
        // can desync the proxied subscription connection), so their request bytes are sent on a live link.
        capture("register-interest", MessageTypes.REGISTER_INTEREST, sink, () -> region.registerInterest("k1"));
        capture("executecq", MessageTypes.EXECUTECQ, sink, () -> newCq(cache, "SELECT * FROM /helloWorld", false));
        capture("executecq-with-ir", MessageTypes.EXECUTECQ_WITH_IR, sink,
                () -> newCq(cache, "SELECT * FROM /helloWorld c WHERE c = c", true));
        capture("unregister-interest", MessageTypes.UNREGISTER_INTEREST, sink, () -> region.unregisterInterest("k1"));
        @SuppressWarnings({"rawtypes", "unchecked"})
        Region rawRegion = region; // a key list goes through the raw registerInterest(Object) overload
        capture("register-interest-list", MessageTypes.REGISTER_INTEREST_LIST, sink,
                () -> rawRegion.registerInterest(List.of("k2", "k3"),
                        org.apache.geode.cache.InterestResultPolicy.NONE));
        capture("clear-region", MessageTypes.CLEAR_REGION, sink, () -> region.clear());
        // onServer().execute() fetches function attributes first (op 91); the shim rejects it, so the
        // EXECUTE_FUNCTION (62) request is never sent — GET_FUNCTION_ATTRIBUTES is what we capture.
        capture("get-function-attributes", MessageTypes.GET_FUNCTION_ATTRIBUTES, sink, () -> tryFunction(cache));
        // Transactional ops: getEntry only reaches the server in a tx; single-hop sends TX_FAILOVER too.
        org.apache.geode.cache.CacheTransactionManager tx = cache.getCacheTransactionManager();
        tx.begin();
        region.put("rbk", "v");
        capture("rollback", MessageTypes.ROLLBACK, sink, tx::rollback);
        tx.begin();
        region.put("txk", "txv");
        capture("tx-failover", MessageTypes.TX_FAILOVER, sink, () -> region.getEntry("txk"));
        captureFromExisting("get-entry", MessageTypes.GET_ENTRY); // same delta also carried op 89
        capture("commit", MessageTypes.COMMIT, sink, () -> tx.commit());
        capture("destroy-region", MessageTypes.DESTROY_REGION, sink, () -> region.destroyRegion());

        writeFixtures(outDir);
        cache.close();
        proxy.close();
        System.out.println("\n=== wrote " + fixtures.size() + " request fixtures to " + outDir + " ===");
        System.exit(0);
    }

    /** Run one op, isolate the request message of {@code expectedOpcode} from the c->s delta, keep it. */
    private void capture(String name, int expectedOpcode, ByteArrayOutputStream sink, Runnable op) {
        int before = sinkSize(sink);
        try {
            op.run();
        } catch (Throwable e) {
            // The request bytes are already on the wire (the client only failed reading the reply);
            // a failed reply must not abort the capture. Errors (e.g. InternalGemFireError) included.
            System.out.println("[capture] " + name + " reply threw " + e.getClass().getSimpleName() + " (continuing)");
        }
        sleep(350);
        byte[] delta = drain(sink, before);
        lastDelta = delta; // keep the raw delta so a sibling opcode can be pulled out (e.g. GET_ENTRY)
        byte[] message = findMessage(delta, expectedOpcode);
        if (message != null) {
            fixtures.put(name + "  (op " + expectedOpcode + ")", message);
            System.out.println("captured " + name + " op=" + expectedOpcode + " bytes=" + message.length);
        } else {
            System.out.println("MISS " + name + " op=" + expectedOpcode + " — opcodes in delta: " + opcodesIn(delta));
        }
    }

    private byte[] lastDelta;

    /** Pull a second opcode out of the most recent delta (e.g. GET_ENTRY rides with TX_FAILOVER). */
    private void captureFromExisting(String name, int expectedOpcode) {
        if (lastDelta == null) {
            return;
        }
        byte[] message = findMessage(lastDelta, expectedOpcode);
        if (message != null) {
            fixtures.put(name + "  (op " + expectedOpcode + ")", message);
            System.out.println("captured " + name + " op=" + expectedOpcode + " bytes=" + message.length);
        } else {
            System.out.println("MISS " + name + " op=" + expectedOpcode);
        }
    }

    /** Find the first framed message in {@code data} whose messageType == opcode (17 + payloadLength each). */
    private static byte[] findMessage(byte[] data, int opcode) {
        int o = 0;
        while (o + HEADER_SIZE <= data.length) {
            int type = i32(data, o);
            int payloadLength = i32(data, o + 4);
            int total = HEADER_SIZE + payloadLength;
            if (payloadLength < 0 || o + total > data.length) {
                break;
            }
            if (type == opcode) {
                byte[] msg = new byte[total];
                System.arraycopy(data, o, msg, 0, total);
                return msg;
            }
            o += total;
        }
        return null;
    }

    private static String opcodesIn(byte[] data) {
        List<Integer> ops = new ArrayList<>();
        int o = 0;
        while (o + HEADER_SIZE <= data.length) {
            int type = i32(data, o);
            int payloadLength = i32(data, o + 4);
            int total = HEADER_SIZE + payloadLength;
            if (payloadLength < 0 || o + total > data.length) {
                break;
            }
            ops.add(type);
            o += total;
        }
        return ops.toString();
    }

    private void writeFixtures(Path outDir) throws IOException {
        Files.createDirectories(outDir);
        for (Map.Entry<String, byte[]> e : fixtures.entrySet()) {
            String fileName = e.getKey().substring(0, e.getKey().indexOf("  (")).trim() + ".hex";
            Files.writeString(outDir.resolve(fileName), hex(e.getValue()) + System.lineSeparator());
        }
    }

    // --- op helpers ---

    private static void exec(ClientCache cache, String oql) {
        try {
            cache.getQueryService().newQuery(oql).execute();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void execParams(ClientCache cache, String oql, Object... params) {
        try {
            cache.getQueryService().newQuery(oql).execute(params);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void newCq(ClientCache cache, String oql, boolean withIr) {
        try {
            QueryService qs = cache.getQueryService();
            CqAttributesFactory caf = new CqAttributesFactory();
            org.apache.geode.cache.query.CqQuery cq =
                    qs.newCq("cq" + System.nanoTime(), oql, caf.create());
            if (withIr) {
                cq.executeWithInitialResults();
            } else {
                cq.execute();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void tryFunction(ClientCache cache) {
        try {
            org.apache.geode.cache.execute.FunctionService
                    .onServer(cache.getDefaultPool())
                    .execute("noSuchFunction")
                    .getResult();
        } catch (Exception e) {
            throw new RuntimeException(e); // the shim rejects it; the request bytes are what we want
        }
    }

    // --- proxy plumbing ---

    private static int sinkSize(ByteArrayOutputStream sink) {
        synchronized (sink) {
            return sink.size();
        }
    }

    private static byte[] drain(ByteArrayOutputStream sink, int before) {
        byte[] all;
        synchronized (sink) {
            all = sink.toByteArray();
        }
        if (all.length <= before) {
            return new byte[0];
        }
        byte[] delta = new byte[all.length - before];
        System.arraycopy(all, before, delta, 0, delta.length);
        return delta;
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
