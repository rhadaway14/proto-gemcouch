package com.protogemcouch.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OqlQuery#pushdownOrGroups()} — OR-of-AND N1QL pushdown eligibility.
 */
class OqlQueryOrPushdownTest {

    // --- pushdownOrGroups not applicable ---

    @Test
    void noWhereReturnsEmpty() {
        assertTrue(OqlQuery.parse("SELECT * FROM /r").pushdownOrGroups().isEmpty());
    }

    @Test
    void singleAndGroupReturnsEmpty() {
        // Single group belongs to pushdownPredicates(), not pushdownOrGroups()
        assertTrue(OqlQuery.parse("SELECT * FROM /r WHERE status = 'active'")
                .pushdownOrGroups().isEmpty());
    }

    // --- basic OR eligibility ---

    @Test
    void twoOrGroupsBothEligible() {
        OqlQuery q = OqlQuery.parse(
                "SELECT * FROM /r WHERE status = 'active' OR status = 'pending'");
        var result = q.pushdownOrGroups();
        assertTrue(result.isPresent());
        List<List<OqlQuery.FieldPredicate>> groups = result.get();
        assertEquals(2, groups.size());
        assertEquals(1, groups.get(0).size());
        assertEquals("status", groups.get(0).get(0).field());
        assertEquals("active", groups.get(0).get(0).text());
        assertEquals("pending", groups.get(1).get(0).text());
    }

    @Test
    void threeOrGroupsAllEligible() {
        OqlQuery q = OqlQuery.parse(
                "SELECT * FROM /r WHERE status = 'a' OR status = 'b' OR status = 'c'");
        var result = q.pushdownOrGroups();
        assertTrue(result.isPresent());
        assertEquals(3, result.get().size());
    }

    @Test
    void orGroupWithAndConditions() {
        // (status='active' AND amount>10) OR status='pending'
        OqlQuery q = OqlQuery.parse(
                "SELECT * FROM /r WHERE status = 'active' AND amount > 10 OR status = 'pending'");
        var result = q.pushdownOrGroups();
        assertTrue(result.isPresent());
        List<List<OqlQuery.FieldPredicate>> groups = result.get();
        assertEquals(2, groups.size());
        assertEquals(2, groups.get(0).size()); // status=active + amount>10
        assertEquals(1, groups.get(1).size()); // status=pending
    }

    // --- ineligible OR groups cause empty ---

    @Test
    void anyGroupWithNoEligiblePredicatesCausesEmpty() {
        // NEQ is not pushable; the OR branch has only a NEQ → whole result is empty
        OqlQuery q = OqlQuery.parse(
                "SELECT * FROM /r WHERE status <> 'closed' OR category = 'A'");
        // status <> 'closed': NEQ is skipped → that group has 0 eligible predicates → empty
        assertTrue(q.pushdownOrGroups().isEmpty());
    }

    @Test
    void nestedFieldInGroupCausesEmptyForThatGroup() {
        // address.zip = '78701': nested path, not pushable → group has 0 eligible → empty
        OqlQuery q = OqlQuery.parse(
                "SELECT * FROM /r WHERE address.zip = '78701' OR status = 'active'");
        // group 0: nested field, skipped → 0 eligible → entire result empty
        assertTrue(q.pushdownOrGroups().isEmpty());
    }

    // --- numeric predicates in OR groups ---

    @Test
    void numericPredicatesAreEligible() {
        OqlQuery q = OqlQuery.parse(
                "SELECT * FROM /r WHERE amount > 100 OR amount < 10");
        var result = q.pushdownOrGroups();
        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertTrue(result.get().get(0).get(0).numeric());
        assertTrue(result.get().get(1).get(0).numeric());
    }

    // --- pushdownPredicates is unaffected ---

    @Test
    void pushdownPredicatesStillHandlesSingleGroup() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE status = 'active' AND amount > 5");
        assertTrue(q.pushdownPredicates().isPresent());
        assertTrue(q.pushdownOrGroups().isEmpty()); // size==1, not handled by pushdownOrGroups
    }
}
