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
            }
        } finally {
            cache.close();
        }
        System.exit(0);
    }
}
