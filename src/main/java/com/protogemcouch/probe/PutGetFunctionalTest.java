package com.protogemcouch.probe;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class PutGetFunctionalTest {
    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 40405;

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

            Map<String, String> cases = new LinkedHashMap<>();
            cases.put("proto::k1", "hello");
            cases.put("proto::k2", "value-from-shim");
            cases.put("proto::k-special", "hello-world_123");
            cases.put("proto::k-remove", "remove-me");

            int passed = 0;
            int failed = 0;

            for (Map.Entry<String, String> entry : cases.entrySet()) {
                String key = entry.getKey();
                String expected = entry.getValue();

                region.put(key, expected);
                String actual = region.get(key);

                boolean ok = Objects.equals(expected, actual);
                if (ok) {
                    passed++;
                    System.out.println("[PASS] key=" + key + " value=" + actual);
                } else {
                    failed++;
                    System.out.println("[FAIL] key=" + key + " expected=" + expected + " actual=" + actual);
                }
            }

            region.remove("proto::k-remove");
            String removed = region.get("proto::k-remove");
            if (removed == null) {
                passed++;
                System.out.println("[PASS] remove returned null on follow-up get");
            } else {
                failed++;
                System.out.println("[FAIL] remove expected null actual=" + removed);
            }

            String missing = region.get("proto::does-not-exist");
            if (missing == null) {
                passed++;
                System.out.println("[PASS] missing key returned null");
            } else {
                failed++;
                System.out.println("[FAIL] missing key expected null actual=" + missing);
            }

            System.out.println();
            System.out.println("Passed: " + passed);
            System.out.println("Failed: " + failed);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cache != null) {
                cache.close();
            }
        }
    }
}