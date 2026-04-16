package com.protogemcouch.probe;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

public class GetOnlyProbe {
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

            String key = "proto::shim-put-test";
            String value = region.get(key);

            System.out.println("GET: " + key + "=" + value);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cache != null) {
                cache.close();
            }
        }
    }
}