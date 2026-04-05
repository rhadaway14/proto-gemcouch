package com.protogemcouch.backend;

public interface KeyValueStore {
    String get(String region, String key);
    void put(String region, String key, String value);
    boolean remove(String region, String key);
}