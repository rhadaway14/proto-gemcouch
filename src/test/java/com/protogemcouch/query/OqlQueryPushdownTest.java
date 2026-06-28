package com.protogemcouch.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eligibility of {@link OqlQuery#pushdownPredicates()} — the conservative gate that decides which
 * queries may be pushed to the backend. Eligible: a single AND-group of string-equality, numeric
 * comparison ({@code = < <= > >= <> !=} a numeric literal), string range ({@code < <= > >=} a string
 * literal), {@code IN (list)}, {@code LIKE}, and {@code IS [NOT] NULL} conditions on simple top-level
 * fields ({@code BETWEEN} desugars to two range conditions). Ineligible (string {@code <>}/{@code !=},
 * boolean/null literals, nested paths) returns empty so the shim scans (the matcher stays authoritative),
 * guaranteeing pushdown never changes results. Projection and ORDER BY are intentionally ignored.
 */
class OqlQueryPushdownTest {

    private static Optional<List<OqlQuery.FieldPredicate>> preds(String oql) {
        return OqlQuery.parse(oql).pushdownPredicates();
    }

    @Test
    void singleStringEqualityIsEligible() {
        Optional<List<OqlQuery.FieldPredicate>> e = preds("SELECT * FROM /r WHERE status = 'active'");
        assertTrue(e.isPresent());
        assertEquals(1, e.get().size());
        OqlQuery.FieldPredicate p = e.get().get(0);
        assertEquals("status", p.field());
        assertEquals(OqlQuery.PushdownOp.EQ, p.op());
        assertFalse(p.numeric(), "string equality is not numeric");
        assertEquals("active", p.text());
    }

    @Test
    void numericEqualityIsEligible() {
        Optional<List<OqlQuery.FieldPredicate>> e = preds("SELECT * FROM /r WHERE amount = 42");
        assertTrue(e.isPresent());
        OqlQuery.FieldPredicate p = e.get().get(0);
        assertTrue(p.numeric(), "unquoted number is a numeric predicate");
        assertEquals(OqlQuery.PushdownOp.EQ, p.op());
        assertEquals(42d, p.number());
    }

    @Test
    void numericRangeOperatorsAreEligible() {
        assertEquals(OqlQuery.PushdownOp.GT, preds("SELECT * FROM /r WHERE amount > 50").get().get(0).op());
        assertEquals(OqlQuery.PushdownOp.GTE, preds("SELECT * FROM /r WHERE amount >= 50").get().get(0).op());
        assertEquals(OqlQuery.PushdownOp.LT, preds("SELECT * FROM /r WHERE amount < 50").get().get(0).op());
        assertEquals(OqlQuery.PushdownOp.LTE, preds("SELECT * FROM /r WHERE amount <= 50").get().get(0).op());
    }

    @Test
    void mixedStringEqualityAndNumericRangeIsEligible() {
        Optional<List<OqlQuery.FieldPredicate>> e =
                preds("SELECT * FROM /r WHERE status = 'active' AND amount > 50");
        assertTrue(e.isPresent());
        assertEquals(2, e.get().size());
        assertFalse(e.get().get(0).numeric());
        assertTrue(e.get().get(1).numeric());
        assertEquals(OqlQuery.PushdownOp.GT, e.get().get(1).op());
    }

    @Test
    void projectionAndOrderByDoNotBlockPushdown() {
        assertTrue(preds("SELECT name FROM /r WHERE status = 'active'").isPresent());
        assertTrue(preds("SELECT * FROM /r WHERE amount > 50 ORDER BY name").isPresent());
    }

    @Test
    void quotedNumericLiteralIsAStringEqualityNotNumeric() {
        OqlQuery.FieldPredicate p = preds("SELECT * FROM /r WHERE code = '42'").get().get(0);
        assertFalse(p.numeric(), "a quoted '42' is a string equality");
        assertEquals("42", p.text());
    }

    @Test
    void orIsNotEligible() {
        assertFalse(preds("SELECT * FROM /r WHERE status = 'active' OR tier = 'gold'").isPresent());
    }

    @Test
    void partialPushReturnsOnlyTheEligibleSubsetOfAnAndGroup() {
        // The ineligible boolean equality is skipped; only the eligible string equality is pushed (the
        // matcher still applies the full WHERE), so a mixed AND is selective instead of scanning.
        Optional<List<OqlQuery.FieldPredicate>> e =
                preds("SELECT * FROM /r WHERE status = 'active' AND active = true");
        assertTrue(e.isPresent());
        assertEquals(1, e.get().size());
        assertEquals("status", e.get().get(0).field());
    }

    @Test
    void andGroupWithNoEligibleConditionIsNotPushed() {
        // Boolean equalities are ineligible → the whole group has nothing to push.
        assertFalse(preds("SELECT * FROM /r WHERE active = true AND deleted = false").isPresent());
    }

    @Test
    void hasWhereReflectsThePresenceOfAWhereClause() {
        assertFalse(OqlQuery.parse("SELECT * FROM /r").hasWhere());
        assertFalse(OqlQuery.parse("SELECT * FROM /r LIMIT 5").hasWhere());
        assertTrue(OqlQuery.parse("SELECT * FROM /r WHERE status = 'active'").hasWhere());
    }

    @Test
    void numericInequalityAndStringRangeAreEligible() {
        // 1.6.0-M2: numeric <>/!= and string ranges now push (string <> still scans — see below).
        assertEquals(OqlQuery.PushdownOp.NEQ, preds("SELECT * FROM /r WHERE amount <> 42").get().get(0).op());
        assertEquals(OqlQuery.PushdownOp.NEQ, preds("SELECT * FROM /r WHERE amount != 42").get().get(0).op());
        OqlQuery.FieldPredicate range = preds("SELECT * FROM /r WHERE name > 'm'").get().get(0);
        assertEquals(OqlQuery.PushdownOp.GT, range.op());
        assertFalse(range.numeric(), "string range is non-numeric");
    }

    @Test
    void stringInequalityIsNotEligible() {
        assertFalse(preds("SELECT * FROM /r WHERE status <> 'closed'").isPresent(), "string <> not pushed");
    }

    @Test
    void booleanAndNullLiteralsAreNotEligible() {
        assertFalse(preds("SELECT * FROM /r WHERE active = true").isPresent());
        assertFalse(preds("SELECT * FROM /r WHERE deleted = null").isPresent());
    }

    @Test
    void noWhereClauseIsNotEligible() {
        assertFalse(preds("SELECT * FROM /r").isPresent());
    }

    @Test
    void aliasStrippedSingleFieldStillPushesButNestedPathDoesNot() {
        // `r.status` strips the alias to a single segment -> pushable; `r.address.zip` is a nested path
        // -> not pushed (the matcher applies it).
        assertTrue(preds("SELECT * FROM /r r WHERE r.status = 'active'").isPresent());
        assertFalse(preds("SELECT * FROM /r r WHERE r.address.zip = '78701'").isPresent());
        // A mixed AND pushes only the single-segment condition.
        Optional<List<OqlQuery.FieldPredicate>> mixed =
                preds("SELECT * FROM /r r WHERE r.status = 'active' AND r.address.zip = '78701'");
        assertTrue(mixed.isPresent());
        assertEquals(1, mixed.get().size());
        assertEquals("status", mixed.get().get(0).field());
    }
}
