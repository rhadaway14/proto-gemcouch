package com.protogemcouch.tools;

import org.apache.geode.DataSerializer;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

/**
 * Dev probe: deserialize a candidate KEYS_VALUES register-interest VersionedObjectList payload (hex
 * via arg or env VOL_HEX) through Geode's own DataSerializer and print the resulting object, so the
 * GII payload builder can be iterated against the real parser without a full docker cycle. Run with a
 * Geode server reachable (for the serialization context).
 */
public final class VolProbe {

    private VolProbe() {
    }

    public static void main(String[] args) throws Exception {
        String hex = args.length > 0 ? args[0] : System.getenv("VOL_HEX");
        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer("127.0.0.1", Integer.parseInt(env("GEODE_PORT", "40404")))
                .create();
        try {
            byte[] obj = unhex(hex);
            System.out.println("=== deserializing " + obj.length + " bytes (first=" + String.format("%02x%02x", obj[0], obj[1]) + ") ===");
            Object o = DataSerializer.readObject(new DataInputStream(new ByteArrayInputStream(obj)));
            System.out.println("type=" + (o == null ? "null" : o.getClass().getName()));
            System.out.println("value=" + o);
        } catch (Throwable t) {
            System.out.println("FAILED: " + t);
            t.printStackTrace(System.out);
        } finally {
            cache.close();
        }
        System.exit(0);
    }

    private static byte[] unhex(String s) {
        s = s.trim();
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
