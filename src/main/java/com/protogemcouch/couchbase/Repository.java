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

    boolean containsKey(String docId);

    boolean containsValueForKey(String docId);

    int size(String region);

    List<String> keySet(String region);
}