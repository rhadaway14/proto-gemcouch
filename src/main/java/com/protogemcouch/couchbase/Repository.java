package com.protogemcouch.couchbase;

import java.util.List;
import java.util.Map;

public interface Repository {

    String get(String docId);

    Map<String, String> getAll(String region, List<String> keys);

    void put(String docId, String value);

    void remove(String docId);

    boolean containsKey(String docId);

    boolean containsValueForKey(String docId);

    int size(String region);

    List<String> keySet(String region);
}