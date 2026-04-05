package com.protogemcouch.probe;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

public class GetOnlyProbe {
    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 40405;
        String regionName = "helloWorld";
        String key = "proto::shim-put-test";

        try (ClientCache cache = new ClientCacheFactory()
                .addPoolServer(host, port)
                .set("log-level", "config")
                .create()) {

            Region<String, String> region = cache
                    .<String, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                    .create(regionName);

            String value = region.get(key);
            System.out.println("GET: " + key + "=" + value);
        }
    }
}