package com.protogemcouch.samples;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

public class RemoveOnlyProbe {

    public static void main(String[] args) {
        String host = System.getenv().getOrDefault("APP_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("APP_PORT", "40404"));
        String regionName = System.getenv().getOrDefault("APP_REGION", "helloWorld");

        ClientCache cache = null;
        try {
            cache = new ClientCacheFactory()
                    .addPoolServer(host, port)
                    .setPoolSubscriptionEnabled(false)
                    .set("log-level", "warn")
                    .create();

            Region<String, Object> region = cache
                    .<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                    .create(regionName);

            String key = "remove-probe-key";
            String value = "remove-probe-value";

            System.out.println("[PUT] " + key);
            region.put(key, value);

            System.out.println("[REMOVE] " + key);
            region.remove(key);

            System.out.println("REMOVE completed");
        } finally {
            if (cache != null) {
                cache.close();
            }
        }
    }
}