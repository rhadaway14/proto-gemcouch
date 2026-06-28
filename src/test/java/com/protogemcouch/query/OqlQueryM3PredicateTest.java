package com.protogemcouch.query;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for M3 predicate types: IN (list), LIKE, IS NULL, IS NOT NULL.
 * Covers OQL parsing, in-shim matching, and pushdown predicate extraction.
 */
class OqlQueryM3PredicateTest {

    // -----------------------------------------------------------------------
    // IN (list) — in-shim matching
    // -----------------------------------------------------------------------

    @Test
    void inListMatchesOneOfTheValues() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE status IN ('active', 'pending')");
        assertTrue(q.matches(mapValue("status", "active")));
        assertTrue(q.matches(mapValue("status", "pending")));
        assertFalse(q.matches(mapValue("status", "closed")));
    }

    @Test
    void inListSingleValue() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE status IN ('active')");
        assertTrue(q.matches(mapValue("status", "active")));
        assertFalse(q.matches(mapValue("status", "inactive")));
    }

    @Test
    void inListSetKeyword() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE status IN SET ('active', 'closed')");
        assertTrue(q.matches(mapValue("status", "closed")));
        assertFalse(q.matches(mapValue("status", "pending")));
    }

    @Test
    void inListAbsentFieldDoesNotMatch() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE status IN ('active', 'pending')");
        assertFalse(q.matches(mapValue("other", "active")));
    }

    // -----------------------------------------------------------------------
    // LIKE — in-shim matching
    // -----------------------------------------------------------------------

    @Test
    void likePercentSuffix() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE name LIKE 'John%'");
        assertTrue(q.matches(mapValue("name", "John")));
        assertTrue(q.matches(mapValue("name", "Johnson")));
        assertFalse(q.matches(mapValue("name", "jane")));
    }

    @Test
    void likePercentPrefix() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE name LIKE '%son'");
        assertTrue(q.matches(mapValue("name", "Johnson")));
        assertTrue(q.matches(mapValue("name", "son")));
        assertFalse(q.matches(mapValue("name", "John")));
    }

    @Test
    void likeBothEndsPercent() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE name LIKE '%oh%'");
        assertTrue(q.matches(mapValue("name", "Johnson")));
        assertTrue(q.matches(mapValue("name", "oh")));
        assertFalse(q.matches(mapValue("name", "Jane")));
    }

    @Test
    void likeUnderscoreSingleChar() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE code LIKE 'A_C'");
        assertTrue(q.matches(mapValue("code", "ABC")));
        assertTrue(q.matches(mapValue("code", "AXC")));
        assertFalse(q.matches(mapValue("code", "AC")));
        assertFalse(q.matches(mapValue("code", "ABBC")));
    }

    @Test
    void likeExactMatch() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE status LIKE 'active'");
        assertTrue(q.matches(mapValue("status", "active")));
        assertFalse(q.matches(mapValue("status", "Active")));
    }

    @Test
    void likeAbsentFieldDoesNotMatch() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE name LIKE 'John%'");
        assertFalse(q.matches(mapValue("other", "John")));
    }

    // -----------------------------------------------------------------------
    // IS NULL / IS NOT NULL — in-shim matching
    // -----------------------------------------------------------------------

    @Test
    void isNullMatchesAbsentField() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE deletedAt IS NULL");
        // field absent → IS NULL true
        assertTrue(q.matches(mapValue("name", "Alice")));
    }

    @Test
    void isNullMatchesNullValue() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE deletedAt IS NULL");
        assertTrue(q.matches(mapNullValue("deletedAt")));
    }

    @Test
    void isNullDoesNotMatchPresentNonNullField() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE deletedAt IS NULL");
        assertFalse(q.matches(mapValue("deletedAt", "2026-01-01")));
    }

    @Test
    void isNotNullMatchesPresentNonNullField() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE deletedAt IS NOT NULL");
        assertTrue(q.matches(mapValue("deletedAt", "2026-01-01")));
    }

    @Test
    void isNotNullDoesNotMatchAbsentField() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE deletedAt IS NOT NULL");
        assertFalse(q.matches(mapValue("name", "Alice")));
    }

    @Test
    void isNotNullDoesNotMatchNullValue() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE deletedAt IS NOT NULL");
        assertFalse(q.matches(mapNullValue("deletedAt")));
    }

    // -----------------------------------------------------------------------
    // Pushdown extraction — new predicate types appear in pushdownPredicates()
    // -----------------------------------------------------------------------

    @Test
    void inListExtractedAsPushdownPredicate() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE status IN ('active', 'pending')");
        Optional<List<OqlQuery.FieldPredicate>> preds = q.pushdownPredicates();
        assertTrue(preds.isPresent());
        assertEquals(1, preds.get().size());
        OqlQuery.FieldPredicate p = preds.get().get(0);
        assertEquals(OqlQuery.PushdownOp.IN_LIST, p.op());
        assertEquals("status", p.field());
        assertEquals(List.of("active", "pending"), p.inList());
    }

    @Test
    void likeExtractedAsPushdownPredicate() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE name LIKE 'John%'");
        Optional<List<OqlQuery.FieldPredicate>> preds = q.pushdownPredicates();
        assertTrue(preds.isPresent());
        assertEquals(1, preds.get().size());
        OqlQuery.FieldPredicate p = preds.get().get(0);
        assertEquals(OqlQuery.PushdownOp.LIKE, p.op());
        assertEquals("name", p.field());
        assertEquals("John%", p.text());
    }

    @Test
    void isNullExtractedAsPushdownPredicate() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE deletedAt IS NULL");
        Optional<List<OqlQuery.FieldPredicate>> preds = q.pushdownPredicates();
        assertTrue(preds.isPresent());
        OqlQuery.FieldPredicate p = preds.get().get(0);
        assertEquals(OqlQuery.PushdownOp.IS_NULL, p.op());
        assertEquals("deletedAt", p.field());
    }

    @Test
    void isNotNullExtractedAsPushdownPredicate() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE deletedAt IS NOT NULL");
        Optional<List<OqlQuery.FieldPredicate>> preds = q.pushdownPredicates();
        assertTrue(preds.isPresent());
        OqlQuery.FieldPredicate p = preds.get().get(0);
        assertEquals(OqlQuery.PushdownOp.IS_NOT_NULL, p.op());
        assertEquals("deletedAt", p.field());
    }

    @Test
    void mixedAndGroupWithInListAndEquality() {
        OqlQuery q = OqlQuery.parse(
                "SELECT * FROM /r WHERE type = 'order' AND status IN ('active', 'pending')");
        Optional<List<OqlQuery.FieldPredicate>> preds = q.pushdownPredicates();
        assertTrue(preds.isPresent());
        assertEquals(2, preds.get().size());
        assertEquals(OqlQuery.PushdownOp.EQ, preds.get().get(0).op());
        assertEquals(OqlQuery.PushdownOp.IN_LIST, preds.get().get(1).op());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static StoredValue mapValue(String key, String value) {
        return StoredValue.stringObjectHashMapValue(Map.of(key, value));
    }

    private static StoredValue mapNullValue(String key) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put(key, null);
        return StoredValue.stringObjectHashMapValue(m);
    }
}
