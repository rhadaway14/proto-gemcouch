package com.protogemcouch.tools;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.client.Pool;
import org.apache.geode.cache.client.PoolManager;
import org.apache.geode.pdx.PdxInstance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev tool: capture the bulk PDX registry-discovery ops a real Geode server answers — GET_PDX_TYPES
 * (101), GET_PDX_ENUMS (102), and GET_PDX_ENUM_BY_ID (98). It runs a logging TCP proxy to the real
 * server, registers a couple of PDX types and one PDX enum (by writing PdxInstances), then drives the
 * internal client Ops ({@code GetPDXTypesOp}/{@code GetPDXEnumsOp}/{@code GetPDXEnumByIdOp}) by
 * reflection and dumps the request/response bytes for each. The dumped replies are the shapes the shim
 * must reproduce so a client's registry sync sees every type/enum the shim holds.
 *
 * <p>Usage (against geode/docker-compose.yml on 40404):
 * <pre>java -cp target/protogemcouch.jar com.protogemcouch.tools.GetPdxRegistryCapture</pre>
 * Env: GEODE_HOST, GEODE_PORT (40404), PROXY_PORT (40499), REGION (helloWorld).
 */
public final class GetPdxRegistryCapture {

    /** A small Java enum so a PdxInstance enum field registers a PDX enum on the server. */
    public enum Status { ACTIVE, CLOSED }

    private GetPdxRegistryCapture() {
    }

    public static void main(String[] args) throws Exception {
        String geodeHost = env("GEODE_HOST", "127.0.0.1");
        int geodePort = Integer.parseInt(env("GEODE_PORT", "40404"));
        int proxyPort = Integer.parseInt(env("PROXY_PORT", "40499"));
        String region = env("REGION", "helloWorld");

        List<ByteArrayOutputStream> s2cList = Collections.synchronizedList(new ArrayList<>());
        List<ByteArrayOutputStream> c2sList = Collections.synchronizedList(new ArrayList<>());

        ServerSocket proxy = new ServerSocket(proxyPort);
        Thread acceptor = new Thread(() -> {
            try {
                while (!proxy.isClosed()) {
                    Socket client = proxy.accept();
                    Socket upstream = new Socket(geodeHost, geodePort);
                    ByteArrayOutputStream s2c = new ByteArrayOutputStream();
                    ByteArrayOutputStream c2s = new ByteArrayOutputStream();
                    s2cList.add(s2c);
                    c2sList.add(c2s);
                    pump(client.getInputStream(), upstream.getOutputStream(), c2s);
                    pump(upstream.getInputStream(), client.getOutputStream(), s2c);
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
                .addPoolServer("127.0.0.1", proxyPort)
                .create();
        Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(region);

        // Register two distinct PDX types + one PDX enum (the enum object field registers a PDX enum).
        PdxInstance order = cache.createPdxInstanceFactory("demo.Order")
                .writeString("sku", "abc").writeInt("qty", 3)
                .writeObject("status", Status.ACTIVE)
                .create();
        PdxInstance customer = cache.createPdxInstanceFactory("demo.Customer")
                .writeString("name", "acme").writeBoolean("vip", true)
                .create();
        r.put("o1", order);
        r.put("c1", customer);
        System.out.println("=== registered types demo.Order, demo.Customer + enum Status ===");

        Pool pool = PoolManager.getAll().values().iterator().next();

        Object types = capture("GET_PDX_TYPES (101)",
                "org.apache.geode.cache.client.internal.GetPDXTypesOp", pool, null, s2cList, c2sList);
        System.out.println("    parsed types = " + describe(types));

        Object enums = capture("GET_PDX_ENUMS (102)",
                "org.apache.geode.cache.client.internal.GetPDXEnumsOp", pool, null, s2cList, c2sList);
        System.out.println("    parsed enums = " + describe(enums));

        // Pick an enum id to drive GET_PDX_ENUM_BY_ID.
        Integer enumId = firstKey(enums);
        if (enumId != null) {
            Object one = capture("GET_PDX_ENUM_BY_ID (98) id=" + enumId,
                    "org.apache.geode.cache.client.internal.GetPDXEnumByIdOp", pool, enumId, s2cList, c2sList);
            System.out.println("    parsed enum[" + enumId + "] = " + one);
        } else {
            System.out.println("    (no enum id captured; skipping GET_PDX_ENUM_BY_ID)");
        }

        cache.close();
        proxy.close();
        System.exit(0);
    }

    /** Snapshot byte counts, invoke the internal Op by reflection, then dump the request/reply delta. */
    private static Object capture(String label, String opClass, Pool pool, Integer arg,
                                  List<ByteArrayOutputStream> s2cList, List<ByteArrayOutputStream> c2sList)
            throws Exception {
        Map<ByteArrayOutputStream, Integer> beforeS = snapshot(s2cList);
        Map<ByteArrayOutputStream, Integer> beforeC = snapshot(c2sList);

        Class<?> op = Class.forName(opClass);
        Class<?> execPool = Class.forName("org.apache.geode.cache.client.internal.ExecutablePool");
        Object result;
        if (arg == null) {
            Method m = op.getMethod("execute", execPool);
            result = m.invoke(null, pool);
        } else {
            Method m = op.getMethod("execute", execPool, int.class);
            result = m.invoke(null, pool, arg.intValue());
        }
        Thread.sleep(300);

        System.out.println("\n=== " + label + " ===");
        dumpDelta("C2S", c2sList, beforeC);
        dumpDelta("S2C", s2cList, beforeS);
        return result;
    }

    private static Map<ByteArrayOutputStream, Integer> snapshot(List<ByteArrayOutputStream> list) {
        Map<ByteArrayOutputStream, Integer> m = new IdentityHashMap<>();
        synchronized (list) {
            for (ByteArrayOutputStream s : list) {
                m.put(s, s.size());
            }
        }
        return m;
    }

    private static void dumpDelta(String label, List<ByteArrayOutputStream> list,
                                  Map<ByteArrayOutputStream, Integer> before) {
        synchronized (list) {
            int i = 0;
            for (ByteArrayOutputStream s : list) {
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

    @SuppressWarnings("unchecked")
    private static Integer firstKey(Object map) {
        if (map instanceof Map<?, ?> m && !m.isEmpty()) {
            Object k = m.keySet().iterator().next();
            if (k instanceof Integer i) {
                return i;
            }
        }
        return null;
    }

    private static String describe(Object map) {
        if (map instanceof Map<?, ?> m) {
            return m.size() + " entries: " + m.keySet();
        }
        return String.valueOf(map);
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
