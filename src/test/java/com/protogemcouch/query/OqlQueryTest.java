package com.protogemcouch.query;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OqlQueryTest {

    private static StoredValue map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return StoredValue.stringObjectHashMapValue(m);
    }

    @Test
    void parsesSelectStarAndExtractsRegionPath() {
        assertEquals("/orders", OqlQuery.parse("SELECT * FROM /orders").regionPath());
        assertEquals("/orders", OqlQuery.parse("  select   *   from   /orders  ").regionPath());
        assertEquals("/orders", OqlQuery.parse("SELECT * FROM /orders;").regionPath());
        assertEquals("/orders", OqlQuery.parse("SELECT * FROM /orders o WHERE o.status = 'x'").regionPath(),
                "alias before WHERE is accepted");
    }

    @Test
    void rejectsUnsupportedQueries() {
        assertThrows(OqlQuery.UnsupportedQueryException.class, () -> OqlQuery.parse("SELECT DISTINCT * FROM /orders"));
        assertThrows(OqlQuery.UnsupportedQueryException.class,
                () -> OqlQuery.parse("SELECT * FROM /orders WHERE (status = 'a')"), "parentheses");
        assertThrows(OqlQuery.UnsupportedQueryException.class,
                () -> OqlQuery.parse("SELECT * FROM /orders WHERE status"), "malformed condition");
        assertThrows(OqlQuery.UnsupportedQueryException.class, () -> OqlQuery.parse(""));
    }

    @Test
    void orCombinesGroupsWithAndBindingTighter() {
        // (status='active' AND amount>50) OR status='vip'
        OqlQuery q = OqlQuery.parse(
                "SELECT * FROM /o WHERE status = 'active' AND amount > 50 OR status = 'vip'");
        assertTrue(q.matches(map("status", "active", "amount", 100)), "first group matches");
        assertFalse(q.matches(map("status", "active", "amount", 10)), "first group fails, not vip");
        assertTrue(q.matches(map("status", "vip", "amount", 1)), "second group (vip) matches");
    }

    @Test
    void singleFieldProjectionReturnsThatField() {
        OqlQuery q = OqlQuery.parse("SELECT status FROM /o");
        assertEquals(1, q.projectionFieldCount());
        assertEquals(StoredValue.stringValue("active"),
                q.projectRow(map("status", "active", "amount", 100)).get(0));

        OqlQuery aliased = OqlQuery.parse("SELECT e.amount FROM /o e WHERE e.amount > 50");
        assertEquals(StoredValue.integerValue(100),
                aliased.projectRow(map("status", "active", "amount", 100)).get(0));
    }

    @Test
    void multiFieldProjectionReturnsStructRow() {
        OqlQuery q = OqlQuery.parse("SELECT e.status, e.amount FROM /o e");
        assertEquals(2, q.projectionFieldCount());
        assertEquals(List.of(StoredValue.stringValue("active"), StoredValue.integerValue(100)),
                q.projectRow(map("status", "active", "amount", 100)));
    }

    @Test
    void selectStarProjectionReturnsWholeValue() {
        StoredValue v = map("status", "active");
        assertEquals(0, OqlQuery.parse("SELECT * FROM /o").projectionFieldCount());
        assertEquals(v, OqlQuery.parse("SELECT * FROM /o").projectRow(v).get(0));
    }

    @Test
    void noWhereMatchesEverything() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /orders");
        assertTrue(q.matches(map("status", "active")));
        assertTrue(q.matches(StoredValue.stringValue("anything")));
    }

    @Test
    void equalityAndInequalityOnStringField() {
        assertTrue(OqlQuery.parse("SELECT * FROM /o WHERE status = 'active'").matches(map("status", "active")));
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE status = 'active'").matches(map("status", "closed")));
        assertTrue(OqlQuery.parse("SELECT * FROM /o WHERE status <> 'closed'").matches(map("status", "active")));
        assertTrue(OqlQuery.parse("SELECT * FROM /o o WHERE o.status = 'active'").matches(map("status", "active")),
                "alias-qualified field resolves to the bare field");
    }

    @Test
    void numericComparisons() {
        StoredValue v = map("amount", 100);
        assertTrue(OqlQuery.parse("SELECT * FROM /o WHERE amount > 50").matches(v));
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE amount < 50").matches(v));
        assertTrue(OqlQuery.parse("SELECT * FROM /o WHERE amount >= 100").matches(v));
        assertTrue(OqlQuery.parse("SELECT * FROM /o WHERE amount = 100").matches(v));
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE amount = 101").matches(v));
    }

    @Test
    void multipleConditionsAreAnded() {
        OqlQuery q = OqlQuery.parse("SELECT * FROM /o WHERE status = 'active' AND amount > 50");
        assertTrue(q.matches(map("status", "active", "amount", 100)));
        assertFalse(q.matches(map("status", "active", "amount", 10)), "second condition fails");
        assertFalse(q.matches(map("status", "closed", "amount", 100)), "first condition fails");
    }

    @Test
    void missingFieldOrNonMapValueDoesNotMatch() {
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE missing = 'x'").matches(map("status", "active")));
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE status = 'active'").matches(StoredValue.stringValue("active")),
                "field predicate does not match a non-map (scalar) value");
    }
}
