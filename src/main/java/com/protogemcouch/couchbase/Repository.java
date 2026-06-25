package com.protogemcouch.couchbase;

import com.protogemcouch.query.OqlQuery;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.DocumentKeyUtil;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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
     * {@code region} that may satisfy the given scalar predicates (AND-combined — string equality and/or
     * numeric comparison), so the caller filters a small candidate set instead of scanning the region.
     *
     * <p>The returned list is a <strong>superset</strong> of the true matches — the caller's own OQL
     * matcher remains authoritative and re-filters it — so pushdown can only change performance, never
     * results. {@code limit > 0} caps the candidate rows at the backend (the caller only does this when
     * it is safe — no {@code ORDER BY} — and guards the loose-predicate case by refetching unbounded if
     * the capped page does not yield enough matches); {@code limit <= 0} means unbounded. Returns
     * {@link Optional#empty()} when pushdown is unavailable (backend has no support, no usable index, or
     * any error), in which case the caller falls back to a full-region scan. The default is "no pushdown".
     */
    default Optional<List<StoredValue>> queryPushdownByPredicates(
            String region, List<OqlQuery.FieldPredicate> predicates, int limit) {
        return Optional.empty();
    }

    /**
     * Install an extractor that, given a stored PDX instance's wire bytes, returns its scalar fields
     * (name→value). When set, the backend writes those fields as a queryable sidecar next to the opaque
     * PDX bytes so {@link #queryPushdownByPredicates} can filter PDX documents by field (instead of
     * sweeping them all in via the non-map escape). Wired only when pushdown is enabled; the default is
     * a no-op (no sidecar). The opaque bytes are unchanged, so value round-trips are unaffected.
     */
    default void setPdxScalarExtractor(Function<byte[], Map<String, Object>> extractor) {
        // no-op by default
    }

    // --- Durable-subscription persistence primitive (1.2.0-M1, behind DURABLE_PERSISTENCE) ----------
    // A durable client's retained registry record + disconnect-time event queue live in a single
    // Couchbase doc (`__protogemcouch::durable::<id>`), so they survive a replica failing and can be
    // replayed on a reconnect to any replica. These defaults are no-ops (single-instance, in-memory
    // behavior unchanged); CouchbaseRepository overrides them with the real, flag-gated implementation.
    // Slice 1 delivers only this primitive; SubscriptionRegistry wiring is Slice 2.

    /**
     * Persist (create or update) a durable client's registry record — its interests, CQs, timeout, and
     * away flag — without touching its event queue. Disabled (no-op) unless durable persistence is on.
     */
    default void saveDurable(DurableRecord record) {
        // no-op by default
    }

    /**
     * Load a durable client's persisted registry record, or {@link Optional#empty()} when none exists
     * (or persistence is off). The event queue is drained separately via {@link #drainDurableQueue}.
     */
    default Optional<DurableRecord> loadDurable(String durableId) {
        return Optional.empty();
    }

    /**
     * Append one already-serialized event frame to a durable client's persisted queue (an atomic,
     * contention-free sub-document array append, bounded by {@code DURABLE_MAX_QUEUE}: oldest dropped on
     * overflow). Creates the queue doc if absent. No-op unless durable persistence is on.
     */
    default void enqueueDurableEvent(String durableId, byte[] event) {
        // no-op by default
    }

    /**
     * Atomically remove and return all queued event frames for a durable client, in order (oldest
     * first), leaving the registry record intact. Empty when there is nothing queued or persistence is
     * off. Concurrent enqueues are not lost (the clear is CAS-guarded).
     */
    default List<byte[]> drainDurableQueue(String durableId) {
        return List.of();
    }

    /** Delete a durable client's persisted doc entirely (record + queue). No-op unless persistence is on. */
    default void dropDurable(String durableId) {
        // no-op by default
    }

    /**
     * Flip a durable client's away flag (and the repository-managed {@code awaySince} timestamp) without
     * rewriting its interests/CQs/queue — used when a client reconnects (mark not-away so the timeout
     * sweep won't reclaim it). Does nothing if no persisted doc exists yet. No-op unless persistence is on.
     */
    default void markDurableAway(String durableId, boolean away) {
        // no-op by default
    }

    /**
     * Drop every persisted durable record whose away-timeout has elapsed ({@code awaySince + timeout <
     * now}), reclaiming durable clients that never reconnect — even if the replica that owned them is
     * gone (a cross-replica sweep, not tied to any one instance's expiry timer). Returns the number of
     * records dropped. No-op (returns 0) unless durable persistence is on.
     */
    default int sweepExpiredDurable() {
        return 0;
    }

    /**
     * List every away durable client's persisted record (away flag set), so the replica that processes a
     * mutation — its <em>origin</em> — can enqueue matching events for all away clients, not just the
     * ones it owned locally. This is the cross-replica registry read that makes durable delivery
     * independent of the client's former owner replica. Returns an empty list unless persistence is on.
     */
    default List<DurableRecord> listAwayDurable() {
        return List.of();
    }

    // --- PDX registry persistence primitive (1.3.0-M2, behind PDX_PERSISTENCE) -----------------------
    // The PDX type/enum registry is otherwise in-memory per shim instance with a local id counter, so
    // ids are lost on restart and assigned inconsistently across replicas (a PDX instance written via
    // one replica may resolve to the wrong type — or not at all — via another). When persistence is on,
    // ids are allocated from a Couchbase atomic counter and the serialized type/enum is persisted, so
    // the id space is durable and consistent cluster-wide. These defaults are no-ops / empty (in-memory
    // behavior unchanged); CouchbaseRepository overrides them with the real, flag-gated implementation.

    /**
     * Allocate (or fetch the already-allocated) cluster-wide PDX type id for a content {@code fingerprint},
     * persisting {@code serializedType} under both a fingerprint→id doc and an id→type doc. Concurrent
     * allocation of the same fingerprint resolves to a single id (insert-if-absent; the race loser adopts
     * the winner's id). Returns {@link OptionalInt#empty()} when persistence is off, so the caller falls
     * back to its in-memory counter.
     */
    default java.util.OptionalInt allocatePdxTypeId(String fingerprint, byte[] serializedType) {
        return java.util.OptionalInt.empty();
    }

    /** The persisted serialized {@code PdxType} for {@code typeId}, or {@code null} when unknown / off. */
    default byte[] loadPdxType(int typeId) {
        return null;
    }

    /** Every persisted PDX type id → serialized {@code PdxType} (for bulk discovery). Empty when off. */
    default Map<Integer, byte[]> loadAllPdxTypes() {
        return Map.of();
    }

    /** PDX enum analogue of {@link #allocatePdxTypeId}. Empty when persistence is off. */
    default java.util.OptionalInt allocatePdxEnumId(String fingerprint, byte[] serializedEnum) {
        return java.util.OptionalInt.empty();
    }

    /** The persisted serialized {@code EnumInfo} for {@code enumId}, or {@code null} when unknown / off. */
    default byte[] loadPdxEnum(int enumId) {
        return null;
    }

    /** Every persisted PDX enum id → serialized {@code EnumInfo} (for bulk discovery). Empty when off. */
    default Map<Integer, byte[]> loadAllPdxEnums() {
        return Map.of();
    }
}