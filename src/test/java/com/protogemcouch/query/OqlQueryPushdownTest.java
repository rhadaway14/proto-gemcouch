package com.protogemcouch.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eligibility of {@link OqlQuery#pushdownStringEqualities()} — the conservative gate that decides which
 * queries may be pushed to the backend. Anything outside a plain AND of {@code field = 'string'} returns
 * empty so the shim scans (and the matcher stays authoritative), guaranteeing pushdown never changes
 * results. Projection and ORDER BY are intentionally ignored (the caller re-applies them).
 */
class OqlQueryPushdownTest {

    private static Optional<List<OqlQuery.FieldStringEquality>> eqs(String oql) {
        return OqlQuery.parse(oql).pushdownStringEqualities();
    }

    @Test
    void singleStringEqualityIsEligible() {
        Optional<List<OqlQuery.FieldStringEquality>> e = eqs("SELECT * FROM /r WHERE status = 'active'");
        assertTrue(e.isPresent());
        assertEquals(1, e.get().size());
        assertEquals("status", e.get().get(0).field());
        assertEquals("active", e.get().get(0).value());
    }

    @Test
    void andOfStringEqualitiesIsEligible() {
        Optional<List<OqlQuery.FieldStringEquality>> e =
                eqs("SELECT * FROM /r WHERE status = 'active' AND tier = 'gold'");
        assertTrue(e.isPresent());
        assertEquals(List.of("status", "tier"), e.get().stream().map(OqlQuery.FieldStringEquality::field).toList());
        assertEquals(List.of("active", "gold"), e.get().stream().map(OqlQuery.FieldStringEquality::value).toList());
    }

    @Test
    void projectionAndOrderByDoNotBlockPushdown() {
        // The caller still applies projection/ordering to the candidates; they must not gate pushdown.
        assertTrue(eqs("SELECT name FROM /r WHERE status = 'active'").isPresent());
        assertTrue(eqs("SELECT * FROM /r WHERE status = 'active' ORDER BY name").isPresent());
    }

    @Test
    void quotedNumericLiteralIsStillAStringEquality() {
        Optional<List<OqlQuery.FieldStringEquality>> e = eqs("SELECT * FROM /r WHERE code = '42'");
        assertTrue(e.isPresent());
        assertEquals("42", e.get().get(0).value());
    }

    @Test
    void orIsNotEligible() {
        assertFalse(eqs("SELECT * FROM /r WHERE status = 'active' OR tier = 'gold'").isPresent());
    }

    @Test
    void numericAndBooleanAndNullLiteralsAreNotEligible() {
        assertFalse(eqs("SELECT * FROM /r WHERE amount = 42").isPresent());
        assertFalse(eqs("SELECT * FROM /r WHERE active = true").isPresent());
        assertFalse(eqs("SELECT * FROM /r WHERE deleted = null").isPresent());
    }

    @Test
    void rangeAndInequalityOperatorsAreNotEligible() {
        assertFalse(eqs("SELECT * FROM /r WHERE amount > 10").isPresent());
        assertFalse(eqs("SELECT * FROM /r WHERE status <> 'x'").isPresent());
        assertFalse(eqs("SELECT * FROM /r WHERE status != 'x'").isPresent());
    }

    @Test
    void noWhereClauseIsNotEligible() {
        assertFalse(eqs("SELECT * FROM /r").isPresent());
    }
}
