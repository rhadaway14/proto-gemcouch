package com.protogemcouch.query;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OqlQueryDistinctTest {

    private static StoredValue row(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return StoredValue.stringObjectHashMapValue(m);
    }

    // 5 rows: k1=active/A, k2=active/B, k3=closed/A, k4=active/A (dup), k5=pending/C
    private static final List<StoredValue> ROWS = List.of(
            row("status", "active",  "category", "A"),
            row("status", "active",  "category", "B"),
            row("status", "closed",  "category", "A"),
            row("status", "active",  "category", "A"),
            row("status", "pending", "category", "C")
    );

    // --- parse ---

    @Test
    void parsesDistinctSingleField() {
        OqlQuery q = OqlQuery.parse("SELECT DISTINCT status FROM /orders");
        assertTrue(q.isDistinct());
        assertFalse(q.isAggregate());
        assertFalse(q.isGroupBy());
        assertEquals(1, q.projectionFieldCount());
        assertEquals("/orders", q.regionPath());
    }

    @Test
    void parsesDistinctMultiField() {
        OqlQuery q = OqlQuery.parse("SELECT DISTINCT status, category FROM /orders");
        assertTrue(q.isDistinct());
        assertEquals(2, q.projectionFieldCount());
        assertEquals(List.of("status", "category"), q.projectionFieldNames());
    }

    @Test
    void parsesDistinctWithAlias() {
        OqlQuery q = OqlQuery.parse("SELECT DISTINCT e.status FROM /orders e");
        assertTrue(q.isDistinct());
        assertEquals(1, q.projectionFieldCount());
        assertEquals(List.of("status"), q.projectionFieldNames());
    }

    @Test
    void parsesDistinctWithWhere() {
        OqlQuery q = OqlQuery.parse("SELECT DISTINCT status FROM /orders WHERE category = 'A'");
        assertTrue(q.isDistinct());
        assertTrue(q.hasWhere());
    }

    @Test
    void parsesDistinctWithLimit() {
        OqlQuery q = OqlQuery.parse("SELECT DISTINCT status FROM /orders LIMIT 5");
        assertTrue(q.isDistinct());
        assertTrue(q.hasLimit());
        assertEquals(5, q.limit());
    }

    @Test
    void distinctStarIsUnsupported() {
        assertThrows(OqlQuery.UnsupportedQueryException.class,
                () -> OqlQuery.parse("SELECT DISTINCT * FROM /orders"));
    }

    @Test
    void nonDistinctIsNotDistinct() {
        assertFalse(OqlQuery.parse("SELECT status FROM /orders").isDistinct());
        assertFalse(OqlQuery.parse("SELECT * FROM /orders").isDistinct());
    }

    @Test
    void distinctIsCaseInsensitive() {
        OqlQuery q = OqlQuery.parse("SELECT distinct status FROM /orders");
        assertTrue(q.isDistinct());
    }

    // --- projectionFieldNames ---

    @Test
    void projectionFieldNamesReturnLastSegment() {
        OqlQuery q = OqlQuery.parse("SELECT DISTINCT e.status, e.category FROM /orders e");
        assertEquals(List.of("status", "category"), q.projectionFieldNames());
    }

    // --- deduplicateRows ---

    @Test
    void deduplicateSingleFieldRemovesDups() {
        OqlQuery q = OqlQuery.parse("SELECT DISTINCT status FROM /orders");
        List<List<StoredValue>> allRows = ROWS.stream()
                .map(sv -> q.projectRow(sv, OqlQuery.MAP_RESOLVER))
                .toList();
        List<List<StoredValue>> distinct = OqlQuery.deduplicateRows(allRows);
        // k1(active), k2(active→dup skip), k3(closed), k4(active→dup skip), k5(pending)
        // First seen: active, closed, pending (k2 and k4 are dups of k1)
        // Wait: k1=active, k2=active (dup), k3=closed, k4=active (dup), k5=pending → 3 distinct
        assertEquals(3, distinct.size());
        assertEquals("active",  distinct.get(0).get(0).value());
        assertEquals("closed",  distinct.get(1).get(0).value());
        assertEquals("pending", distinct.get(2).get(0).value());
    }

    @Test
    void deduplicateMultiFieldRemovesDups() {
        OqlQuery q = OqlQuery.parse("SELECT DISTINCT status, category FROM /orders");
        List<List<StoredValue>> allRows = ROWS.stream()
                .map(sv -> q.projectRow(sv, OqlQuery.MAP_RESOLVER))
                .toList();
        List<List<StoredValue>> distinct = OqlQuery.deduplicateRows(allRows);
        // (active,A), (active,B), (closed,A), (active,A)→dup, (pending,C) → 4 distinct
        assertEquals(4, distinct.size());
    }

    @Test
    void deduplicateEmptyIsEmpty() {
        List<List<StoredValue>> distinct = OqlQuery.deduplicateRows(List.of());
        assertTrue(distinct.isEmpty());
    }

    @Test
    void deduplicateAllUniqueRetainsAll() {
        OqlQuery q = OqlQuery.parse("SELECT DISTINCT category FROM /orders");
        // k1=A, k2=B, k3=A (dup), k4=A (dup), k5=C → 3 distinct
        List<List<StoredValue>> allRows = ROWS.stream()
                .map(sv -> q.projectRow(sv, OqlQuery.MAP_RESOLVER))
                .toList();
        List<List<StoredValue>> distinct = OqlQuery.deduplicateRows(allRows);
        assertEquals(3, distinct.size());
    }

    @Test
    void deduplicatePreservesFirstSeenOrder() {
        OqlQuery q = OqlQuery.parse("SELECT DISTINCT status FROM /orders");
        List<List<StoredValue>> allRows = ROWS.stream()
                .map(sv -> q.projectRow(sv, OqlQuery.MAP_RESOLVER))
                .toList();
        List<List<StoredValue>> distinct = OqlQuery.deduplicateRows(allRows);
        // First seen in ROWS order: active(k1), closed(k3), pending(k5)
        assertEquals("active",  distinct.get(0).get(0).value());
        assertEquals("closed",  distinct.get(1).get(0).value());
        assertEquals("pending", distinct.get(2).get(0).value());
    }
}
