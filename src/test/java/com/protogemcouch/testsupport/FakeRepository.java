package com.protogemcouch.testsupport;

import com.protogemcouch.couchbase.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FakeRepository implements Repository {

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, Integer> sizes = new LinkedHashMap<>();
    private final Map<String, List<String>> keySets = new LinkedHashMap<>();

    public FakeRepository withValue(String docId, String value) {
        values.put(docId, value);
        return this;
    }

    public FakeRepository withSize(String region, int size) {
        sizes.put(region, size);
        return this;
    }

    public FakeRepository withKeySet(String region, List<String> keys) {
        keySets.put(region, keys);
        return this;
    }

    @Override
    public String get(String docId) {
        return values.get(docId);
    }

    @Override
    public Map<String, String> getAll(String region, List<String> keys) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : keys) {
            result.put(key, values.get(region + "::" + key));
        }
        return result;
    }

    @Override
    public void put(String docId, String value) {
        values.put(docId, value);
    }

    @Override
    public void remove(String docId) {
        values.remove(docId);
    }

    @Override
    public boolean containsKey(String docId) {
        return values.containsKey(docId);
    }

    @Override
    public boolean containsValueForKey(String docId) {
        return values.containsKey(docId) && values.get(docId) != null;
    }

    @Override
    public int size(String region) {
        return sizes.getOrDefault(region, 0);
    }

    @Override
    public List<String> keySet(String region) {
        return keySets.getOrDefault(region, List.of());
    }
}