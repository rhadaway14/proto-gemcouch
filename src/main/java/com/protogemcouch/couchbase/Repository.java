package com.protogemcouch.couchbase;

import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.DocumentKeyUtil;

import java.util.List;
import java.util.Map;

public interface Repository {

    StoredValue get(String docId);

    Map<String, StoredValue> getAll(String region, List<String> keys);

    void put(String docId, StoredValue value);

    /**
     * Store multiple key/value pairs in one region. Null values are skipped.
     *
     * <p>The default implementation simply calls {@link #put} per entry. {@code CouchbaseRepository}
     * overrides it to issue the value writes concurrently and update the region's keyset metadata
     * once for the whole batch, instead of paying a value upsert plus a keyset get+upsert per entry.
     */
    default void putAll(String region, Map<String, StoredValue> values) {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, StoredValue> entry : values.entrySet()) {
            if (entry.getValue() != null) {
                put(DocumentKeyUtil.docId(region, entry.getKey()), entry.getValue());
            }
        }
    }

    void remove(String docId);

    /**
     * Atomic insert-if-absent (backs Geode {@code Region.putIfAbsent}). Stores {@code value} only if
     * the key is currently absent. Returns the existing value when the key was already present (so
     * nothing was stored), or {@code null} when the key was absent and {@code value} was stored.
     *
     * <p>The default implementation is a non-atomic get-then-put, correct for single-threaded use;
     * {@code CouchbaseRepository} overrides it with a true atomic insert so concurrent writers
     * (and multiple shim replicas) cannot both observe "absent".
     */
    default StoredValue putIfAbsent(String docId, StoredValue value) {
        StoredValue existing = get(docId);
        if (existing == null && value != null) {
            put(docId, value);
        }
        return existing;
    }

    /**
     * Atomic replace-if-present (backs Geode {@code Region.replace(key, value)}). Replaces the value
     * only if the key is currently present. Returns the previous value when it existed and was
     * replaced, or {@code null} when the key was absent (nothing stored).
     *
     * <p>{@code CouchbaseRepository} overrides this with a compare-and-swap retry loop.
     */
    default StoredValue replace(String docId, StoredValue value) {
        StoredValue existing = get(docId);
        if (existing != null && value != null) {
            put(docId, value);
        }
        return existing;
    }

    /**
     * Atomic compare-and-replace (backs Geode {@code Region.replace(key, oldValue, newValue)}).
     * Replaces only if the current value equals {@code expected}. Returns {@code true} iff the
     * replace happened.
     *
     * <p>{@code CouchbaseRepository} overrides this with a compare-and-swap retry loop.
     */
    default boolean replace(String docId, StoredValue expected, StoredValue newValue) {
        StoredValue existing = get(docId);
        if (existing != null && existing.equals(expected) && newValue != null) {
            put(docId, newValue);
            return true;
        }
        return false;
    }

    /**
     * Atomic compare-and-remove (backs Geode {@code Region.remove(key, value)}). Removes only if the
     * current value equals {@code expected}. Returns {@code true} iff the remove happened.
     *
     * <p>{@code CouchbaseRepository} overrides this with a CAS-guarded remove.
     */
    default boolean removeIfValue(String docId, StoredValue expected) {
        StoredValue existing = get(docId);
        if (existing != null && existing.equals(expected)) {
            remove(docId);
            return true;
        }
        return false;
    }

    boolean containsKey(String docId);

    boolean containsValueForKey(String docId);

    int size(String region);

    List<String> keySet(String region);
}