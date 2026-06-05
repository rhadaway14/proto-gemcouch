package com.protogemcouch.tx;

import com.protogemcouch.serialization.StoredValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buffered server-side state for one in-flight client transaction. Transactional write ops
 * (PUT/REMOVE) are recorded here instead of being applied immediately; reads within the same
 * transaction consult the buffer first (read-your-writes), and the whole buffer is applied to the
 * repository atomically-ish on COMMIT or discarded on ROLLBACK.
 *
 * <p>Ops are keyed by document id so the latest op for a key wins, while first-seen insertion order
 * is preserved for a deterministic apply order. Instances are guarded by their own monitor.
 */
public final class TxState {

    /** A buffered transactional operation. {@code value} is null for a {@link Kind#REMOVE}. */
    public enum Kind { PUT, REMOVE }

    public record Op(Kind kind, String docId, StoredValue value) {
    }

    private final int txId;
    private final Map<String, Op> ops = new LinkedHashMap<>();

    public TxState(int txId) {
        this.txId = txId;
    }

    public int txId() {
        return txId;
    }

    public synchronized void put(String docId, StoredValue value) {
        ops.put(docId, new Op(Kind.PUT, docId, value));
    }

    public synchronized void remove(String docId) {
        ops.put(docId, new Op(Kind.REMOVE, docId, null));
    }

    /**
     * Read-your-writes lookup. Returns the buffered op for {@code docId} (a {@link Kind#PUT} carrying
     * the value, or a {@link Kind#REMOVE}), or {@code null} when this transaction has not touched the
     * key — in which case the caller should fall through to the committed repository state.
     */
    public synchronized Op lookup(String docId) {
        return ops.get(docId);
    }

    /** Buffered ops in first-seen apply order. */
    public synchronized List<Op> ops() {
        return new ArrayList<>(ops.values());
    }

    public synchronized int size() {
        return ops.size();
    }

    /**
     * Region-scoped overlay derived from the buffered ops whose docId starts with {@code prefix}
     * (use {@code DocumentKeyUtil.regionPrefix(region)}): the net buffered puts (key &rarr; value)
     * and the set of buffered-removed keys. Lets {@code size}/{@code keySet} apply this transaction's
     * own writes on top of committed state.
     */
    public synchronized RegionOverlay regionOverlay(String prefix) {
        Map<String, StoredValue> puts = new LinkedHashMap<>();
        java.util.Set<String> removed = new java.util.LinkedHashSet<>();
        for (Op op : ops.values()) {
            if (!op.docId().startsWith(prefix)) {
                continue;
            }
            String key = op.docId().substring(prefix.length());
            if (op.kind() == Kind.REMOVE) {
                puts.remove(key);
                removed.add(key);
            } else {
                puts.put(key, op.value());
                removed.remove(key);
            }
        }
        return new RegionOverlay(puts, removed);
    }

    /** Net buffered puts (key &rarr; value) and removed keys for one region. */
    public record RegionOverlay(Map<String, StoredValue> puts, java.util.Set<String> removed) {
        public boolean isEmpty() {
            return puts.isEmpty() && removed.isEmpty();
        }
    }
}
