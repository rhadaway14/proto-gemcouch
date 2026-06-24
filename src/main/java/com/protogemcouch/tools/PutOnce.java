package com.protogemcouch.tools;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

/**
 * Minimal mutator: connect a (non-subscription) client to the shim, put one key/value, and exit. Used
 * as a separate process by the subscription integration test so a mutation originates from a
 * <em>different</em> client than the listening one — the listener must then fire from the remote
 * server-pushed event, not a local operation.
 *
 * <p>Args: host port region key value
 */
public final class PutOnce {

    private PutOnce() {
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 40405;
        String region = args.length > 2 ? args[2] : "demo";
        String key = args.length > 3 ? args[3] : "k";
        String value = args.length > 4 ? args[4] : "v";

        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(host, port)
                .create();
        try {
            Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                    .create(region);
            r.put(key, value);
            System.out.println("PutOnce: put " + region + "/" + key + "=" + value);
            String op = args.length > 5 ? args[5] : "";
            if ("remove".equalsIgnoreCase(op)) {
                r.remove(key);
                System.out.println("PutOnce: removed " + region + "/" + key);
            } else if ("update".equalsIgnoreCase(op)) {
                // Second put to the same key exercises the LOCAL_UPDATE notification.
                r.put(key, value + "-upd");
                System.out.println("PutOnce: updated " + region + "/" + key + "=" + value + "-upd");
            } else if ("invalidate".equalsIgnoreCase(op)) {
                r.invalidate(key);
                System.out.println("PutOnce: invalidated " + region + "/" + key);
            } else if ("map".equalsIgnoreCase(op) || "mapdestroy".equalsIgnoreCase(op)) {
                // Overwrite the key with a HashMap {amount: <value as int>} for CQ predicate tests.
                java.util.HashMap<String, Object> m = new java.util.HashMap<>();
                m.put("amount", Integer.parseInt(value));
                r.put(key, m);
                System.out.println("PutOnce: put map " + region + "/" + key + "={amount:" + value + "}");
                if ("mapdestroy".equalsIgnoreCase(op)) {
                    r.remove(key);
                    System.out.println("PutOnce: removed " + region + "/" + key);
                }
            } else if ("pdx".equalsIgnoreCase(op)) {
                // Put a PDX object demo.Order { String status; int amount } so a CQ predicate on a PDX
                // field (e.g. amount > 10) can be exercised end-to-end from a separate client.
                r.put(key, cache.createPdxInstanceFactory("demo.Order")
                        .writeString("status", "active")
                        .writeInt("amount", Integer.parseInt(value))
                        .create());
                System.out.println("PutOnce: put pdx " + region + "/" + key + " {amount:" + value + "}");
            } else if ("pdxnested".equalsIgnoreCase(op)) {
                // Put a PDX object with a NESTED PDX object field (demo.Order { status; demo.Address
                // address { zip } }) so a CQ predicate on a nested PDX field (r.address.zip) is exercised
                // end-to-end from a separate client. The `value` arg is the zip.
                org.apache.geode.pdx.PdxInstance address = cache.createPdxInstanceFactory("demo.Address")
                        .writeString("zip", value)
                        .create();
                r.put(key, cache.createPdxInstanceFactory("demo.Order")
                        .writeString("status", "active")
                        .writeObject("address", address)
                        .create());
                System.out.println("PutOnce: put nested pdx " + region + "/" + key + " {address.zip:" + value + "}");
            } else if ("pdxobjarray".equalsIgnoreCase(op)) {
                // Put a PDX object with an OBJECT-ARRAY field of nested PDX (demo.Customer { status;
                // demo.Address[] addresses }) so a CQ predicate on an object-array element field
                // (r.addresses[0].zip) is exercised end-to-end from a separate client. `value` is the zip.
                org.apache.geode.pdx.PdxInstance address = cache.createPdxInstanceFactory("demo.Address")
                        .writeString("zip", value)
                        .create();
                r.put(key, cache.createPdxInstanceFactory("demo.Customer")
                        .writeString("status", "active")
                        .writeObjectArray("addresses", new org.apache.geode.pdx.PdxInstance[] {address})
                        .create());
                System.out.println("PutOnce: put object-array pdx " + region + "/" + key
                        + " {addresses[0].zip:" + value + "}");
            } else if ("mapstops".equalsIgnoreCase(op)) {
                // Put a matching map {amount: value}, then update it to a non-matching {amount: 1}
                // (stops-matching -> CQ DESTROY).
                java.util.HashMap<String, Object> m1 = new java.util.HashMap<>();
                m1.put("amount", Integer.parseInt(value));
                r.put(key, m1);
                java.util.HashMap<String, Object> m2 = new java.util.HashMap<>();
                m2.put("amount", 1);
                r.put(key, m2);
                System.out.println("PutOnce: " + region + "/" + key + " {amount:" + value + "} then {amount:1}");
            }
        } finally {
            cache.close();
        }
        System.exit(0);
    }
}
