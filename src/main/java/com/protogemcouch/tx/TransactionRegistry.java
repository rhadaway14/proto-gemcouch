package com.protogemcouch.tx;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds the {@link TxState} buffers for all in-flight client transactions, keyed by connection and
 * transaction id. A Geode client pins a transaction to a single server connection, so the
 * (channel, txId) pair uniquely identifies a transaction and lets concurrent clients reuse tx ids
 * without colliding.
 */
public final class TransactionRegistry {

    private final ConcurrentMap<String, TxState> states = new ConcurrentHashMap<>();

    private static String key(String channelId, int txId) {
        return channelId + "#" + txId;
    }

    /** Get the transaction's buffer, creating it on first transactional op. */
    public TxState getOrCreate(String channelId, int txId) {
        return states.computeIfAbsent(key(channelId, txId), k -> new TxState(txId));
    }

    /** The transaction's buffer if one exists, else {@code null} (no transactional op seen yet). */
    public TxState peek(String channelId, int txId) {
        return states.get(key(channelId, txId));
    }

    /** The buffered op for {@code docId} in this transaction, or {@code null} if none (read-your-writes). */
    public TxState.Op peekOp(String channelId, int txId, String docId) {
        TxState state = states.get(key(channelId, txId));
        return state == null ? null : state.lookup(docId);
    }

    /** Remove and return the transaction's buffer (on COMMIT or ROLLBACK). */
    public TxState remove(String channelId, int txId) {
        return states.remove(key(channelId, txId));
    }

    /** Drop every buffer for a connection (called when a channel closes mid-transaction). */
    public void removeChannel(String channelId) {
        states.keySet().removeIf(k -> k.startsWith(channelId + "#"));
    }

    public int activeCount() {
        return states.size();
    }
}
