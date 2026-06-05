package com.protogemcouch.tx;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionStateTest {

    @Test
    void bufferedOpsPreserveFirstSeenOrderAndLastWriteWins() {
        TxState state = new TxState(1);
        state.put("r/a", StoredValue.stringValue("a1"));
        state.put("r/b", StoredValue.stringValue("b1"));
        state.put("r/a", StoredValue.stringValue("a2")); // overwrite keeps a's original position

        List<TxState.Op> ops = state.ops();
        assertEquals(2, ops.size());
        assertEquals("r/a", ops.get(0).docId());
        assertEquals("a2", ops.get(0).value().value());
        assertEquals("r/b", ops.get(1).docId());
    }

    @Test
    void removeAfterPutIsRecordedAsRemove() {
        TxState state = new TxState(1);
        state.put("r/a", StoredValue.stringValue("a1"));
        state.remove("r/a");

        TxState.Op op = state.lookup("r/a");
        assertEquals(TxState.Kind.REMOVE, op.kind());
        assertNull(op.value());
    }

    @Test
    void lookupReturnsNullForUntouchedKey() {
        TxState state = new TxState(1);
        assertNull(state.lookup("r/missing"));
    }

    @Test
    void registryIsolatesTransactionsByChannelAndTxId() {
        TransactionRegistry registry = new TransactionRegistry();
        TxState a = registry.getOrCreate("chan-A", 5);
        TxState b = registry.getOrCreate("chan-B", 5); // same txId, different connection
        a.put("r/k", StoredValue.stringValue("fromA"));

        assertSame(a, registry.getOrCreate("chan-A", 5));
        assertNull(b.lookup("r/k"));
        assertEquals("fromA", registry.peekOp("chan-A", 5, "r/k").value().value());
        assertNull(registry.peekOp("chan-B", 5, "r/k"));
    }

    @Test
    void regionOverlayReportsNetPutsAndRemovesForTheRegionPrefixOnly() {
        TxState state = new TxState(1);
        state.put("r1::a", StoredValue.stringValue("a"));
        state.put("r1::b", StoredValue.stringValue("b"));
        state.remove("r1::a");                              // a ends up removed
        state.put("r1::c", StoredValue.stringValue("c"));
        state.remove("r1::gone");                           // remove of an untouched key
        state.put("other::x", StoredValue.stringValue("x")); // different region, must be excluded

        TxState.RegionOverlay overlay = state.regionOverlay("r1::");
        assertEquals(2, overlay.puts().size());
        assertEquals("b", overlay.puts().get("b").value());
        assertEquals("c", overlay.puts().get("c").value());
        assertTrue(overlay.removed().contains("a"));
        assertTrue(overlay.removed().contains("gone"));
        // The other-region key never appears.
        assertNull(overlay.puts().get("x"));
    }

    @Test
    void regionOverlayIsEmptyWhenNoOpsTouchTheRegion() {
        TxState state = new TxState(1);
        state.put("other::x", StoredValue.stringValue("x"));
        assertTrue(state.regionOverlay("r1::").isEmpty());
    }

    @Test
    void removeReturnsAndDiscardsTheBuffer() {
        TransactionRegistry registry = new TransactionRegistry();
        registry.getOrCreate("chan", 9).put("r/k", StoredValue.stringValue("v"));

        TxState removed = registry.remove("chan", 9);
        assertEquals(1, removed.size());
        assertNull(registry.peek("chan", 9));
    }

    @Test
    void removeChannelDropsAllItsTransactions() {
        TransactionRegistry registry = new TransactionRegistry();
        registry.getOrCreate("chan", 1).put("r/a", StoredValue.stringValue("a"));
        registry.getOrCreate("chan", 2).put("r/b", StoredValue.stringValue("b"));
        registry.getOrCreate("other", 1).put("r/c", StoredValue.stringValue("c"));

        registry.removeChannel("chan");
        assertNull(registry.peek("chan", 1));
        assertNull(registry.peek("chan", 2));
        assertEquals(1, registry.activeCount());
    }
}
