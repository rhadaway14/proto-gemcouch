package com.protogemcouch.couchbase;

import com.protogemcouch.serialization.StoredValue;

import java.util.List;
import java.util.Map;

public interface Repository {

    StoredValue get(String docId);

    Map<String, StoredValue> getAll(String region, List<String> keys);

    void put(String docId, StoredValue value);

    void remove(String docId);

    boolean containsKey(String docId);

    boolean containsValueForKey(String docId);

    int size(String region);

    List<String> keySet(String region);
}