package com.protogemcouch.samples;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

public class PutOnlyProbe {
    public static void main(String[] args) {
        ClientCache cache = new ClientCacheFactory()
                .addPoolServer("127.0.0.1", 40404)
                .setPoolSubscriptionEnabled(false)
                .set("log-level", "warn")
                .create();

        try {
            Region<String, String> region = cache
                    .<String, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                    .create("helloWorld");

            region.put("probe-key-1", "probe-value-1");
            System.out.println("PUT completed");
        } finally {
            cache.close();
        }
    }
}