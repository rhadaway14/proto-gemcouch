package com.protogemcouch.couchbase;

import com.protogemcouch.query.OqlQuery;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.DocumentKeyUtil;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** A single write to apply on transaction commit: a put of {@code value}, or a remove. */
    record WriteOp(String docId, StoredValue value, boolean remove) {
    }

    /**
     * Apply a committed transaction's buffered writes (backs Geode client {@code commit()}). The
     * default applies them sequentially (best-effort, not cross-key atomic); {@code CouchbaseRepository}
     * overrides it to apply all value documents and the affected per-region keyset metadata inside a
     * single Couchbase multi-document ACID transaction, so a failure leaves nothing applied.
     */
    default void commitAtomically(List<WriteOp> ops) {
        if (ops == null) {
            return;
        }
        for (WriteOp op : ops) {
            if (op.remove()) {
                remove(op.docId());
            } else if (op.value() != null) {
                put(op.docId(), op.value());
            }
        }
    }

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

    /**
     * Invalidate an entry (backs Geode {@code Region.invalidate}): keep the key present but drop its
     * value, so {@code containsKey} stays true while {@code get} returns null and
     * {@code containsValueForKey} is false. {@code CouchbaseRepository} overrides this to store a
     * value-less marker and retain the key in the keyset; the default falls back to a remove.
     */
    default void invalidate(String docId) {
        remove(docId);
    }

    /**
     * Remove every entry in a region (backs Geode {@code Region.clear}). The default iterates the
     * keyset and removes each entry; {@code CouchbaseRepository} overrides it to also clear the
     * region's keyset metadata in one shot.
     */
    default void clear(String region) {
        for (String key : keySet(region)) {
            remove(DocumentKeyUtil.docId(region, key));
        }
    }

    boolean containsKey(String docId);

    boolean containsValueForKey(String docId);

    int size(String region);

    List<String> keySet(String region);

    /**
     * Optional OQL query pushdown: when the backend can pre-filter, return the candidate values of
     * {@code region} that may satisfy the given string-equality conditions (AND-combined), so the caller
     * filters a small candidate set instead of scanning the whole region.
     *
     * <p>The returned list is a <strong>superset</strong> of the true matches — the caller's own OQL
     * matcher remains authoritative and re-filters it — so pushdown can only change performance, never
     * results. Returns {@link Optional#empty()} when pushdown is unavailable (backend has no support,
     * no usable index, or any error), in which case the caller falls back to a full-region scan. The
     * default is "no pushdown".
     */
    default Optional<List<StoredValue>> queryPushdownByStringEquality(
            String region, List<OqlQuery.FieldStringEquality> conditions) {
        return Optional.empty();
    }
}