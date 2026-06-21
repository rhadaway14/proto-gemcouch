package com.protogemcouch.tools;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Characterizes the keyset-metadata cold path at scale: how the per-region keyset document (one JSON
 * array of every key in the region) makes {@code SIZE} / {@code KEY_SET} (read the whole doc) and
 * {@code REMOVE} / {@code PUT_ALL} (CAS read-modify-write the whole doc) cost grow with the keyspace,
 * while a single {@code PUT} stays a contention-free sub-document append.
 *
 * <p>Seeds a fresh region up to each checkpoint via {@code putAll} (one keyset rewrite per batch) and,
 * at each checkpoint, reports the median latency of {@code KEY_SET}, {@code SIZE}, a single {@code PUT},
 * and a single {@code REMOVE}. Read-only on existing data (uses its own random region). Re-run it to
 * re-characterize after a change.
 *
 * <pre>
 *   java -cp target/protogemcouch.jar com.protogemcouch.tools.KeysetScaleProbe \
 *       [host] [port] [checkpointsCsv] [batchSize] [repeats]
 *   # e.g. KeysetScaleProbe 127.0.0.1 40405 5000,20000,50000 1000 5
 * </pre>
 */
public final class KeysetScaleProbe {

    private KeysetScaleProbe() {
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 40405;
        int[] checkpoints = parseCheckpoints(args.length > 2 ? args[2] : "5000,20000,50000");
        int batchSize = args.length > 3 ? Integer.parseInt(args[3]) : 1000;
        int repeats = args.length > 4 ? Integer.parseInt(args[4]) : 5;

        String region = "ksprobe" + UUID.randomUUID().toString().replace("-", "");
        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(host, port)
                .create();
        try {
            Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                    .create(region);

            System.out.println("KeysetScaleProbe region=" + region + " batch=" + batchSize + " repeats=" + repeats);
            System.out.printf("%-10s %-12s %-12s %-12s %-12s%n",
                    "keys", "keySet_ms", "size_ms", "put_ms", "remove_ms");

            int seeded = 0;
            for (int target : checkpoints) {
                for (; seeded < target; seeded += batchSize) {
                    int upper = Math.min(batchSize, target - seeded);
                    Map<String, Object> batch = new LinkedHashMap<>(upper * 2);
                    for (int i = 0; i < upper; i++) {
                        batch.put("k-" + (seeded + i), "v-" + (seeded + i));
                    }
                    r.putAll(batch);
                }

                double keySetMs = medianMillis(repeats, () -> r.keySetOnServer());
                double sizeMs = medianMillis(repeats, () -> r.sizeOnServer());
                double putMs = medianMillis(repeats, () -> r.put("probe-" + UUID.randomUUID(), "x"));
                // REMOVE an existing seeded key each iteration (then it is gone; we re-add to keep count).
                double removeMs = medianMillis(repeats, () -> {
                    String key = "k-" + (int) (Math.random() * target);
                    r.remove(key);
                    r.put(key, "v-readd");
                });

                System.out.printf("%-10d %-12.2f %-12.2f %-12.2f %-12.2f%n",
                        target, keySetMs, sizeMs, putMs, removeMs);
            }
            System.out.println("KeysetScaleProbe done region=" + region
                    + " (read the keyset doc size from Couchbase: META().id LIKE \"__protogemcouch::keyset::%\")");
        } finally {
            cache.close();
        }
        System.exit(0);
    }

    private static double medianMillis(int repeats, Runnable op) {
        List<Long> samples = new ArrayList<>(repeats);
        for (int i = 0; i < repeats; i++) {
            long start = System.nanoTime();
            op.run();
            samples.add(System.nanoTime() - start);
        }
        samples.sort(Long::compareTo);
        return samples.get(samples.size() / 2) / 1_000_000.0;
    }

    private static int[] parseCheckpoints(String csv) {
        String[] parts = csv.split(",");
        int[] checkpoints = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            checkpoints[i] = Integer.parseInt(parts[i].trim());
        }
        return checkpoints;
    }
}
