package com.protogemcouch.probe;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

import java.util.LinkedHashMap;
import java.util.Map;

public class PutAllOnlyProbe {
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

            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("proto::putall-1", "value-1");
            entries.put("proto::putall-2", "value-2");
            entries.put("proto::putall-3", "value-3");

            System.out.println("PUT ALL START");
            region.putAll(entries);
            System.out.println("PUT ALL DONE");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cache != null) {
                cache.close();
            }
        }
    }
}