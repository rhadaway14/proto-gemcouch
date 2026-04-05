package com.protogemcouch.probe;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

import java.util.UUID;

public class App {

    public static void main(String[] args) {

        String host = "127.0.0.1";
        int port = 40405;
        String regionName = "helloWorld";

        try (ClientCache cache = new ClientCacheFactory()
                .addPoolServer(host, port)
                .set("log-level", "config")
                .create()) {

            Region<String, String> region = cache
                    .<String, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                    .create(regionName);

            System.out.println("CONNECTED");

            String key = "proto::shim-put-test";
            String value = "value-from-shim";

            // PUT
            region.put(key, value);
            System.out.println("PUT: " + key + "=" + value);

//            // GET
//            String fetched = region.get(key);
//            System.out.println("GET: " + key + "=" + fetched);
//
//            // REMOVE
//            region.remove(key);
//            System.out.println("REMOVE: " + key);
//
//            // GET AFTER REMOVE
//            String afterRemove = region.get(key);
//            System.out.println("GET AFTER REMOVE: " + afterRemove);

            System.out.println("DONE");
        }
    }
}