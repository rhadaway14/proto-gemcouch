package com.protogemcouch.probe;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

public class GetBenchmark {
    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 40405;
        int iterations = 1000;

        ClientCache cache = null;
        try {
            cache = new ClientCacheFactory()
                    .addPoolServer(host, port)
                    .setPoolSubscriptionEnabled(false)
                    .set("log-level", "warn")
                    .create();

            Region<String, String> region = cache
                    .<String, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                    .create("helloWorld");

            for (int i = 0; i < iterations; i++) {
                region.put("proto::bench-get-" + i, "value-" + i);
            }

            long start = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                String value = region.get("proto::bench-get-" + i);
                if (value == null) {
                    throw new RuntimeException("Missing value for proto::bench-get-" + i);
                }
            }

            long end = System.nanoTime();
            double seconds = (end - start) / 1_000_000_000.0;
            double opsPerSec = iterations / seconds;
            double avgMs = ((end - start) / 1_000_000.0) / iterations;

            System.out.println("GET benchmark results");
            System.out.println("Iterations: " + iterations);
            System.out.println("Total seconds: " + seconds);
            System.out.println("Ops/sec: " + opsPerSec);
            System.out.println("Avg ms/op: " + avgMs);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cache != null) {
                cache.close();
            }
        }
    }
}