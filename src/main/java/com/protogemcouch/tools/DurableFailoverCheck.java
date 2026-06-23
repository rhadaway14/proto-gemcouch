package com.protogemcouch.tools;

import org.apache.geode.cache.EntryEvent;
import org.apache.geode.cache.InterestResultPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.util.CacheListenerAdapter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-cluster check for 1.2.0-M1 Slice 4 — multi-replica durable-subscription failover on real
 * Kubernetes. Driven by {@code scripts/k8s-durable-failover-e2e.sh}, which runs the three roles as pods
 * pinned to specific shim replicas (by pod IP) and kills a replica between them:
 *
 * <ol>
 *   <li><b>subscribe</b> (pinned to replica A): a durable client registers interest, calls
 *       {@code readyForEvents()}, then closes keeping its subscription — so A persists the away record
 *       to Couchbase. Prints {@code DURABLE_SUBSCRIBED}.</li>
 *   <li>the script then <b>kills pod A</b> and</li>
 *   <li><b>mutate</b> (pinned to replica B): a plain client puts the key. As the origin, B enqueues the
 *       event for the away client from the persisted registry (Slice 3). Prints {@code DURABLE_MUTATED}.</li>
 *   <li><b>verify</b> (pinned to replica B): the durable client reconnects with the same durable id and
 *       {@code readyForEvents()} must replay the event missed while away — even though the replica it
 *       was originally on (A) is gone. Prints {@code DURABLE_FAILOVER_CHECK PASS|FAIL}.</li>
 * </ol>
 *
 * <p>Config via env: {@code TARGET} ({@code host:port}), {@code DURABLE_ID}, {@code REGION},
 * {@code KEY}, {@code VALUE}, {@code TIMEOUT_SECONDS} (default 60).
 */
public final class DurableFailoverCheck {

    private DurableFailoverCheck() {
    }

    public static void main(String[] args) throws Exception {
        String role = args.length > 0 ? args[0] : env("ROLE", "verify");
        String[] target = env("TARGET", "127.0.0.1:40405").split(":");
        String host = target[0];
        int port = Integer.parseInt(target[1]);
        String durableId = env("DURABLE_ID", "ITK8SDUR");
        String region = env("REGION", "durFailover");
        String key = env("KEY", "missed-key");
        String value = env("VALUE", "missed-value");
        int timeoutSeconds = Integer.parseInt(env("TIMEOUT_SECONDS", "60"));

        switch (role) {
            case "subscribe" -> subscribe(host, port, durableId, region);
            case "mutate" -> mutate(host, port, region, key, value);
            case "verify" -> verify(host, port, durableId, region, key, value, timeoutSeconds);
            default -> {
                System.out.println("unknown role: " + role);
                System.exit(2);
            }
        }
    }

    /** Phase 1: durable client on replica A registers interest, becomes ready, closes keeping its queue. */
    private static void subscribe(String host, int port, String durableId, String region) throws Exception {
        ClientCache cache = durableCache(host, port, durableId);
        Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .create(region);
        r.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);
        cache.readyForEvents();
        Thread.sleep(1500); // let the server register the interest + persist on close
        System.out.println("DURABLE_SUBSCRIBED durableId=" + durableId + " target=" + host + ":" + port);
        cache.close(true); // keepalive: retain the subscription/queue while away
    }

    /** Phase 2: a plain client puts the key on replica B (the origin enqueues for the away client). */
    private static void mutate(String host, int port, String region, String key, String value) {
        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(host, port)
                .create();
        try {
            Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                    .create(region);
            r.put(key, value);
            System.out.println("DURABLE_MUTATED key=" + key + " target=" + host + ":" + port);
        } finally {
            cache.close();
        }
    }

    /** Phase 3: durable client reconnects to replica B; readyForEvents() must replay the missed event. */
    private static void verify(String host, int port, String durableId, String region,
                               String key, String value, int timeoutSeconds) throws Exception {
        CountDownLatch replayed = new CountDownLatch(1);
        AtomicReference<Object> got = new AtomicReference<>();
        ClientCache cache = durableCache(host, port, durableId);
        try {
            Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                    .addCacheListener(new CacheListenerAdapter<String, Object>() {
                        @Override
                        public void afterCreate(EntryEvent<String, Object> event) {
                            record(event);
                        }

                        @Override
                        public void afterUpdate(EntryEvent<String, Object> event) {
                            record(event);
                        }

                        private void record(EntryEvent<String, Object> event) {
                            if (key.equals(event.getKey())) {
                                got.set(event.getNewValue());
                                replayed.countDown();
                            }
                        }
                    })
                    .create(region);
            r.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);
            cache.readyForEvents(); // triggers replay of the queue drained from Couchbase

            boolean ok = replayed.await(timeoutSeconds, TimeUnit.SECONDS) && value.equals(got.get());
            System.out.println("DURABLE_FAILOVER_CHECK " + (ok ? "PASS" : "FAIL")
                    + " durableId=" + durableId + " key=" + key + " got=" + got.get());
            if (!ok) {
                System.exit(1);
            }
        } finally {
            cache.close(false);
        }
    }

    private static ClientCache durableCache(String host, int port, String durableId) {
        return new ClientCacheFactory()
                .set("log-level", "warn")
                .set("durable-client-id", durableId)
                .set("durable-client-timeout", "300")
                .setPoolSubscriptionEnabled(true)
                .setPoolSubscriptionRedundancy(0)
                .setPoolSubscriptionAckInterval(100)
                .addPoolServer(host, port)
                .create();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
