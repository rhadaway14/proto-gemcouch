package com.protogemcouch.tools;

import org.apache.geode.DataSerializer;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.internal.cache.EventID;
import org.apache.geode.internal.cache.versions.VMVersionTag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/**
 * Dev probe: confirm the shim can construct + serialize the two Geode objects an event notification
 * needs — {@link EventID} (with a fabricated membership id + incrementing sequence) and a
 * {@link VMVersionTag} — via Geode's own {@link DataSerializer}, so the event-push builders can embed
 * exact bytes. Prints the serialized hex.
 */
public final class EventSerializeProbe {

    private EventSerializeProbe() {
    }

    public static void main(String[] args) throws Exception {
        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer("127.0.0.1", Integer.parseInt(env("GEODE_PORT", "40404")))
                .create();

        byte[] memberId = ByteUtilsHex("0a0000bc0000e29a");  // fabricated, stable shim identity bytes

        EventID e1 = new EventID(memberId, 1L, 1L);
        EventID e2 = new EventID(memberId, 1L, 2L);
        System.out.println("EventID(seq=1) = " + hex(serialize(e1)));
        System.out.println("EventID(seq=2) = " + hex(serialize(e2)));
        System.out.println("getOptimizedByteArrayForEventID(1,1) = "
                + hex(EventID.getOptimizedByteArrayForEventID(1L, 1L)));

        VMVersionTag vt = new VMVersionTag();
        vt.setEntryVersion(1);
        vt.setRegionVersion(1L);
        vt.setVersionTimeStamp(System.currentTimeMillis());
        try {
            System.out.println("VMVersionTag(member=null) = " + hex(serialize(vt)));
        } catch (Throwable t) {
            System.out.println("VMVersionTag serialize FAILED: " + t);
        }

        cache.close();
        System.exit(0);
    }

    private static byte[] serialize(Object o) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataSerializer.writeObject(o, new DataOutputStream(baos));
        return baos.toByteArray();
    }

    private static byte[] ByteUtilsHex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
