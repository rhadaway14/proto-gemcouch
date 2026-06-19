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

/**
 * End-to-end cross-replica eventing check, run as two pods against two <em>specific</em> shim replicas
 * (by pod IP) so the path is deterministically cross-replica:
 *
 * <ul>
 *   <li><b>subscriber</b> (pinned to replica A): registers interest + a {@code CacheListener}, prints
 *       {@code SUBSCRIBER_READY}, then waits for an event on the agreed key and prints
 *       {@code CROSS_REPLICA_EVENT_CHECK PASS|FAIL}.</li>
 *   <li><b>mutator</b> (pinned to replica B): repeatedly puts the agreed key.</li>
 * </ul>
 *
 * If the backplane works, the mutation on B is broadcast to A, whose feed pushes it to the subscriber's
 * listener. With no backplane, the subscriber never hears it and the check FAILs. A separate JVM per
 * role is required because a Geode {@code ClientCache} is a per-JVM singleton.
 *
 * <p>Env: {@code ROLE} (or argv[0]) = subscriber|mutator; {@code TARGET} = host:port of the shim pod;
 * {@code REGION} (default {@code crossReplica}); {@code KEY} (default {@code cross-replica-key});
 * {@code TIMEOUT_SECONDS} (subscriber, default 90); {@code MUTATE_SECONDS} (mutator, default 60).
 */
public final class CrossReplicaEventCheck {

    private CrossReplicaEventCheck() {
    }

    public static void main(String[] args) throws Exception {
        String role = args.length > 0 ? args[0] : System.getenv("ROLE");
        String target = required("TARGET");
        String region = envOr("REGION", "crossReplica");
        String key = envOr("KEY", "cross-replica-key");
        int colon = target.lastIndexOf(':');
        String host = target.substring(0, colon);
        int port = Integer.parseInt(target.substring(colon + 1));

        if ("subscriber".equals(role)) {
            runSubscriber(host, port, region, key, Long.parseLong(envOr("TIMEOUT_SECONDS", "90")));
        } else if ("mutator".equals(role)) {
            runMutator(host, port, region, key, Long.parseLong(envOr("MUTATE_SECONDS", "60")));
        } else {
            System.err.println("usage: CrossReplicaEventCheck <subscriber|mutator> (TARGET=host:port)");
            System.exit(2);
        }
    }

    private static void runSubscriber(String host, int port, String region, String key, long timeoutSeconds)
            throws InterruptedException {
        ClientCache cache = new ClientCacheFactory()
                .addPoolServer(host, port)
                .setPoolSubscriptionEnabled(true)
                .setPoolReadTimeout(10000)
                .set("log-level", "warn")
                .create();
        CountDownLatch fired = new CountDownLatch(1);
        Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .addCacheListener(new CacheListenerAdapter<String, Object>() {
                    @Override
                    public void afterCreate(EntryEvent<String, Object> event) {
                        if (key.equals(event.getKey())) {
                            fired.countDown();
                        }
                    }

                    @Override
                    public void afterUpdate(EntryEvent<String, Object> event) {
                        if (key.equals(event.getKey())) {
                            fired.countDown();
                        }
                    }
                })
                .create(region);
        r.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);
        System.out.println("SUBSCRIBER_READY target=" + host + ":" + port + " region=" + region + " key=" + key);

        boolean ok = fired.await(timeoutSeconds, TimeUnit.SECONDS);
        System.out.println(ok ? "CROSS_REPLICA_EVENT_CHECK PASS" : "CROSS_REPLICA_EVENT_CHECK FAIL");
        cache.close();
        System.exit(ok ? 0 : 1);
    }

    private static void runMutator(String host, int port, String region, String key, long mutateSeconds) {
        ClientCache cache = new ClientCacheFactory()
                .addPoolServer(host, port)
                .set("log-level", "warn")
                .create();
        Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(region);
        // Put repeatedly over a window so the event still propagates if mesh peer-discovery converges a
        // moment after the subscriber registered. The subscriber fires on the first delivery.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(mutateSeconds);
        int i = 0;
        while (System.nanoTime() < deadline) {
            r.put(key, "v-" + (i++));
            System.out.println("MUTATED key=" + key + " target=" + host + ":" + port + " n=" + i);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        cache.close();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println("missing required env: " + name);
            System.exit(2);
        }
        return value;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
