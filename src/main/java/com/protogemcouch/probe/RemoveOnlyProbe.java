package com.protogemcouch.probe;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

public class RemoveOnlyProbe {
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

            String key = "proto::remove-test";

            System.out.println("REMOVE START");
            Object removed = region.remove(key);
            System.out.println("REMOVE DONE: " + removed);

            System.out.println("DONE");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cache != null) {
                cache.close();
            }
        }
    }
}