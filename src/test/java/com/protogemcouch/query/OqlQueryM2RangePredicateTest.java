package com.protogemcouch.query;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for 1.6.0-M2 predicate gaps: string range predicates ({@code < <= > >=} on string
 * literals) and numeric {@code <>}/{@code !=}. Covers OQL parsing, in-shim matching, and the pushdown
 * predicate extraction (`pushdownPredicates()`). End-to-end N1QL behavior is covered by
 * {@code ProtoGemCouchQueryPushdownIntegrationTest}.
 *
 * <p>Note: {@code BETWEEN} is intentionally not supported — it is not part of Geode OQL (the real
 * client's query compiler rejects {@code BETWEEN} as an unexpected token before it reaches the shim), so
 * a {@code field >= lo AND field <= hi} form is used instead.
 */
class OqlQueryM2RangePredicateTest {

    // -----------------------------------------------------------------------
    // String range predicates ( < <= > >= 'text' )
    // -----------------------------------------------------------------------

    @Test
    void stringGreaterThanMatchesInShim() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE name > 'M'");
        assertTrue(q.matches(mapValue("name", "N")));
        assertTrue(q.matches(mapValue("name", "Zoe")));
        assertFalse(q.matches(mapValue("name", "M")), "strict >");
        assertFalse(q.matches(mapValue("name", "A")));
    }

    @Test
    void stringRangeExtractedAsPushdownPredicate() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE name > 'M'");
        Optional<List<OqlQuery.FieldPredicate>> preds = q.pushdownPredicates();
        assertTrue(preds.isPresent());
        OqlQuery.FieldPredicate p = preds.get().get(0);
        assertEquals(OqlQuery.PushdownOp.GT, p.op());
        assertFalse(p.numeric(), "string range is non-numeric");
        assertEquals("name", p.field());
        assertEquals("M", p.text());
    }

    @Test
    void stringRangeAllOperatorsPush() {
        assertEquals(OqlQuery.PushdownOp.LT, onlyPred("SELECT * FROM /r WHERE name < 'M'").op());
        assertEquals(OqlQuery.PushdownOp.LTE, onlyPred("SELECT * FROM /r WHERE name <= 'M'").op());
        assertEquals(OqlQuery.PushdownOp.GT, onlyPred("SELECT * FROM /r WHERE name > 'M'").op());
        assertEquals(OqlQuery.PushdownOp.GTE, onlyPred("SELECT * FROM /r WHERE name >= 'M'").op());
    }

    // -----------------------------------------------------------------------
    // Numeric <> / !=
    // -----------------------------------------------------------------------

    @Test
    void numericNotEqualMatchesInShim() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE amount <> 5");
        assertTrue(q.matches(numValue("amount", 6)));
        assertFalse(q.matches(numValue("amount", 5)));
        assertFalse(q.matches(numValue("other", 6)), "absent field does not match <>");
    }

    @Test
    void numericNotEqualPushesAsNeq() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE amount <> 5");
        OqlQuery.FieldPredicate p = onlyPred("SELECT * FROM /r WHERE amount <> 5");
        assertEquals(OqlQuery.PushdownOp.NEQ, p.op());
        assertTrue(p.numeric());
        assertEquals(5d, p.number());
        // != is the alternate spelling and pushes identically.
        assertEquals(OqlQuery.PushdownOp.NEQ, onlyPred("SELECT * FROM /r WHERE amount != 5").op());
    }

    @Test
    void stringNotEqualIsNotPushed() {
        // Per the M2 scope, only numeric <> pushes; a string <> still matches in-shim and scans.
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE status <> 'closed'");
        assertTrue(q.pushdownPredicates().isEmpty(), "string <> is not pushdown-eligible");
        assertTrue(q.matches(mapValue("status", "active")));
        assertFalse(q.matches(mapValue("status", "closed")));
    }

    @Test
    void neqSymbolHasBangEquals() {
        assertEquals("!=", OqlQuery.PushdownOp.NEQ.symbol());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static OqlQuery.FieldPredicate onlyPred(String oql) {
        Optional<List<OqlQuery.FieldPredicate>> preds = OqlQuery.parse(oql).pushdownPredicates();
        assertTrue(preds.isPresent(), "expected a pushdown predicate for: " + oql);
        assertEquals(1, preds.get().size());
        return preds.get().get(0);
    }

    private static StoredValue mapValue(String key, String value) {
        return StoredValue.stringObjectHashMapValue(Map.of(key, value));
    }

    private static StoredValue numValue(String key, int value) {
        return StoredValue.stringObjectHashMapValue(Map.of(key, value));
    }
}
