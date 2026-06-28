package com.protogemcouch.query;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for parenthesized AND/OR in WHERE clauses: DNF expansion and in-shim matching.
 */
class OqlQueryParenWhereTest {

    private static StoredValue doc(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return StoredValue.stringObjectHashMapValue(m);
    }

    private static boolean matches(String oql, StoredValue value) {
        return OqlQuery.parse(oql).matches(value, OqlQuery.MAP_RESOLVER);
    }

    // --- trivial parens (no semantic change) ---

    @Test
    void trivialParensAroundSingleCondition() {
        // (status = 'active') is the same as status = 'active'
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE (status = 'active')");
        assertTrue(q.hasWhere());
        assertTrue(matches("SELECT * FROM /r WHERE (status = 'active')", doc("status", "active")));
        assertFalse(matches("SELECT * FROM /r WHERE (status = 'active')", doc("status", "closed")));
    }

    @Test
    void trivialParensAroundAndGroup() {
        // (a = 1 AND b = 2) → one AND-group
        assertTrue(matches("SELECT * FROM /r WHERE (status = 'active' AND category = 'A')",
                doc("status", "active", "category", "A")));
        assertFalse(matches("SELECT * FROM /r WHERE (status = 'active' AND category = 'A')",
                doc("status", "active", "category", "B")));
    }

    @Test
    void doubleNestedParens() {
        assertTrue(matches("SELECT * FROM /r WHERE ((status = 'active'))",
                doc("status", "active")));
        assertFalse(matches("SELECT * FROM /r WHERE ((status = 'active'))",
                doc("status", "closed")));
    }

    // --- AND with parenthesized OR clause ---

    @Test
    void andWithParenthesizedOrClause() {
        // status = 'active' AND (category = 'A' OR category = 'B')
        // DNF: [[status=active,category=A], [status=active,category=B]]
        String oql = "SELECT * FROM /r WHERE status = 'active' AND (category = 'A' OR category = 'B')";
        assertTrue(matches(oql, doc("status", "active",  "category", "A")));
        assertTrue(matches(oql, doc("status", "active",  "category", "B")));
        assertFalse(matches(oql, doc("status", "active",  "category", "C")));
        assertFalse(matches(oql, doc("status", "closed",  "category", "A")));
    }

    // --- parenthesized AND clause before OR ---

    @Test
    void parenthesizedAndGroupOrPlainCondition() {
        // (status = 'active' AND category = 'A') OR status = 'pending'
        // DNF: [[status=active,category=A], [status=pending]]
        String oql = "SELECT * FROM /r WHERE (status = 'active' AND category = 'A') OR status = 'pending'";
        assertTrue(matches(oql, doc("status", "active",  "category", "A")));
        assertFalse(matches(oql, doc("status", "active",  "category", "B"))); // fails both groups
        assertTrue(matches(oql, doc("status", "pending", "category", "Z")));
        assertFalse(matches(oql, doc("status", "closed",  "category", "A")));
    }

    // --- cross-product distribution ---

    @Test
    void crossProductOfTwoParenthesizedOrGroups() {
        // (a = 1 OR b = 2) AND (c = 3 OR d = 4)
        // DNF: [[a=1,c=3], [a=1,d=4], [b=2,c=3], [b=2,d=4]]
        String oql = "SELECT * FROM /r WHERE (status = 'a' OR status = 'b') AND (category = 'X' OR category = 'Y')";
        assertTrue(matches(oql, doc("status", "a", "category", "X")));
        assertTrue(matches(oql, doc("status", "a", "category", "Y")));
        assertTrue(matches(oql, doc("status", "b", "category", "X")));
        assertTrue(matches(oql, doc("status", "b", "category", "Y")));
        assertFalse(matches(oql, doc("status", "a", "category", "Z")));
        assertFalse(matches(oql, doc("status", "c", "category", "X")));
    }

    // --- existing (non-paren) queries are unaffected ---

    @Test
    void plainAndQueryUnchanged() {
        String oql = "SELECT * FROM /r WHERE status = 'active' AND category = 'A'";
        assertTrue(matches(oql, doc("status", "active", "category", "A")));
        assertFalse(matches(oql, doc("status", "closed", "category", "A")));
    }

    @Test
    void plainOrQueryUnchanged() {
        String oql = "SELECT * FROM /r WHERE status = 'active' OR status = 'pending'";
        assertTrue(matches(oql, doc("status", "active")));
        assertTrue(matches(oql, doc("status", "pending")));
        assertFalse(matches(oql, doc("status", "closed")));
    }

    // --- projection + DISTINCT with parenthesized WHERE ---

    @Test
    void distinctWithParenthesizedWhere() {
        OqlQuery q = OqlQuery.parse(
                "SELECT DISTINCT status FROM /r WHERE (status = 'active' OR status = 'pending')");
        assertTrue(q.isDistinct());
        assertTrue(q.hasWhere());
        assertTrue(matches("SELECT DISTINCT status FROM /r WHERE (status = 'active' OR status = 'pending')",
                doc("status", "active")));
        assertFalse(matches("SELECT DISTINCT status FROM /r WHERE (status = 'active' OR status = 'pending')",
                doc("status", "closed")));
    }

    // --- pushdown is suppressed when DNF produces multiple OR-groups ---

    @Test
    void pushdownNotEligibleForExpandedOrGroups() {
        OqlQuery q = OqlQuery.parse(
                "SELECT * FROM /r WHERE status = 'active' AND (category = 'A' OR category = 'B')");
        // DNF has 2 groups → pushdown not eligible
        assertTrue(q.pushdownPredicates().isEmpty());
    }

    @Test
    void pushdownEligibleForTrivialParens() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /r WHERE (status = 'active' AND category = 'A')");
        // DNF has 1 group → pushdown eligible
        assertTrue(q.pushdownPredicates().isPresent());
    }
}
