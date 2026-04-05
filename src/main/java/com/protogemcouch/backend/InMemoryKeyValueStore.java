package com.protogemcouch.backend;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryKeyValueStore implements KeyValueStore {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    private String docId(String region, String key) {
        return region + "::" + key;
    }

    @Override
    public String get(String region, String key) {
        return store.get(docId(region, key));
    }

    @Override
    public void put(String region, String key, String value) {
        store.put(docId(region, key), value);
    }

    @Override
    public boolean remove(String region, String key) {
        return store.remove(docId(region, key)) != null;
    }
}