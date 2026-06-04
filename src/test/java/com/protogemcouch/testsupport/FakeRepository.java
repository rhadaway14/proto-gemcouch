package com.protogemcouch.testsupport;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.DocumentKeyUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FakeRepository implements Repository {

    private final Map<String, StoredValue> documents = new LinkedHashMap<>();

    @Override
    public StoredValue get(String docId) {
        return documents.get(docId);
    }

    @Override
    public Map<String, StoredValue> getAll(String region, List<String> keys) {
        Map<String, StoredValue> results = new LinkedHashMap<>();

        for (String key : keys) {
            String docId = DocumentKeyUtil.docId(region, key);
            results.put(key, documents.get(docId));
        }

        return results;
    }

    @Override
    public void put(String docId, StoredValue value) {
        documents.put(docId, value);
    }

    @Override
    public void remove(String docId) {
        documents.remove(docId);
    }

    @Override
    public void invalidate(String docId) {
        // Keep the key present with a null value: containsKey stays true, get/containsValueForKey are
        // null/false, and keySet/size still count it.
        documents.put(docId, null);
    }

    @Override
    public boolean containsKey(String docId) {
        return documents.containsKey(docId);
    }

    @Override
    public boolean containsValueForKey(String docId) {
        StoredValue value = documents.get(docId);
        return value != null && value.value() != null;
    }

    @Override
    public int size(String region) {
        String prefix = region + "::";
        int count = 0;

        for (String docId : documents.keySet()) {
            if (docId.startsWith(prefix)) {
                count++;
            }
        }

        return count;
    }

    @Override
    public List<String> keySet(String region) {
        String prefix = region + "::";
        List<String> keys = new ArrayList<>();

        for (String docId : documents.keySet()) {
            if (docId.startsWith(prefix)) {
                keys.add(docId.substring(prefix.length()));
            }
        }

        return keys;
    }

    public void putString(String docId, String value) {
        put(docId, StoredValue.stringValue(value));
    }

    public void putInteger(String docId, Integer value) {
        put(docId, StoredValue.integerValue(value));
    }

    public Map<String, StoredValue> documents() {
        return documents;
    }
}