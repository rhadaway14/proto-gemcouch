package com.protogemcouch.probe;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

public class SizeOnServerOnlyProbe {
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

            System.out.println("SIZE ON SERVER START");
            int size = region.sizeOnServer();
            System.out.println("SIZE ON SERVER DONE: " + size);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cache != null) {
                cache.close();
            }
        }
    }
}