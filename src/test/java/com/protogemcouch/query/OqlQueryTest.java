package com.protogemcouch.query;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    void parsesLimitClause() {
        OqlQuery limited = OqlQuery.parse("SELECT * FROM /orders LIMIT 10");
        assertTrue(limited.hasLimit());
        assertEquals(10, limited.limit());
        assertEquals("/orders", limited.regionPath());

        OqlQuery none = OqlQuery.parse("SELECT * FROM /orders");
        assertFalse(none.hasLimit());
        assertEquals(-1, none.limit());

        // LIMIT 0 is valid (no rows).
        assertEquals(0, OqlQuery.parse("SELECT * FROM /orders LIMIT 0").limit());

        // LIMIT composes with WHERE + ORDER BY.
        OqlQuery full = OqlQuery.parse("SELECT * FROM /orders WHERE status = 'a' ORDER BY amount DESC LIMIT 5");
        assertEquals(5, full.limit());
        assertTrue(full.hasOrderBy());
        assertEquals("/orders", full.regionPath());
    }

    @Test
    void limitInsideAStringLiteralIsNotTreatedAsAClause() {
        // A value that merely contains "LIMIT 5" must not be parsed as a LIMIT clause (the trailing
        // quote breaks the LIMIT-tail match), so the query has no row cap.
        OqlQuery q = OqlQuery.parse("SELECT * FROM /orders WHERE name = 'a LIMIT 5'");
        assertFalse(q.hasLimit());
        assertEquals("/orders", q.regionPath());
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
    void orderByNumericAscAndDesc() {
        List<StoredValue> asc = new ArrayList<>(List.of(map("amount", 30), map("amount", 10), map("amount", 20)));
        OqlQuery.parse("SELECT * FROM /o ORDER BY amount").sort(asc);
        assertEquals(List.of(10, 20, 30), field(asc, "amount"));

        List<StoredValue> desc = new ArrayList<>(List.of(map("amount", 30), map("amount", 10), map("amount", 20)));
        OqlQuery.parse("SELECT * FROM /o e ORDER BY e.amount DESC").sort(desc);
        assertEquals(List.of(30, 20, 10), field(desc, "amount"));
    }

    @Test
    void orderByStringField() {
        List<StoredValue> data = new ArrayList<>(List.of(map("s", "beta"), map("s", "alpha"), map("s", "gamma")));
        OqlQuery.parse("SELECT * FROM /o ORDER BY s").sort(data);
        assertEquals(List.of("alpha", "beta", "gamma"), field(data, "s"));
    }

    private static List<Object> field(List<StoredValue> values, String name) {
        return values.stream().map(v -> v.asStringObjectHashMap().get(name)).collect(Collectors.toList());
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
    void nestedMapPathInWhereNavigatesIntoNestedObject() {
        StoredValue v = map("status", "active", "address", Map.of("zip", "78701", "city", "Austin"));
        assertTrue(OqlQuery.parse("SELECT * FROM /o WHERE address.zip = '78701'").matches(v));
        assertTrue(OqlQuery.parse("SELECT * FROM /o o WHERE o.address.city = 'Austin'").matches(v),
                "alias-qualified nested path resolves");
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE address.zip = '00000'").matches(v),
                "wrong nested value does not match");
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE address.country = 'US'").matches(v),
                "missing nested key does not match");
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE status.zip = 'x'").matches(v),
                "descending into a scalar does not match");
    }

    @Test
    void nestedMapPathProjectionAndOrderBy() {
        StoredValue a = map("address", Map.of("zip", "30"));
        StoredValue b = map("address", Map.of("zip", "10"));
        assertEquals("30", OqlQuery.parse("SELECT e.address.zip FROM /o e").projectRow(a).get(0).value());

        List<StoredValue> values = new ArrayList<>(List.of(a, b));
        OqlQuery.parse("SELECT * FROM /o e ORDER BY e.address.zip").sort(values);
        assertEquals("10", OqlQuery.parse("SELECT e.address.zip FROM /o e").projectRow(values.get(0)).get(0).value(),
                "sorted ascending by the nested field");
    }

    @Test
    void arrayIndexAccessInWhere() {
        StoredValue v = map("tags", List.of("gold", "silver", "bronze"), "scores", List.of(10, 20, 30));
        assertTrue(OqlQuery.parse("SELECT * FROM /o WHERE tags[0] = 'gold'").matches(v));
        assertTrue(OqlQuery.parse("SELECT * FROM /o o WHERE o.tags[2] = 'bronze'").matches(v),
                "alias-qualified indexed access");
        assertTrue(OqlQuery.parse("SELECT * FROM /o WHERE scores[1] > 15").matches(v), "numeric element compare");
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE tags[1] = 'gold'").matches(v));
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE tags[9] = 'gold'").matches(v),
                "out-of-range index does not match");
    }

    @Test
    void inContainmentOnArray() {
        StoredValue v = map("tags", List.of("gold", "silver"), "scores", List.of(10, 20));
        assertTrue(OqlQuery.parse("SELECT * FROM /o WHERE 'silver' IN tags").matches(v));
        assertTrue(OqlQuery.parse("SELECT * FROM /o o WHERE 20 IN o.scores").matches(v), "numeric containment");
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE 'platinum' IN tags").matches(v));
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE 'gold' IN missing").matches(v),
                "IN on a missing field does not match");
    }

    @Test
    void missingFieldOrNonMapValueDoesNotMatch() {
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE missing = 'x'").matches(map("status", "active")));
        assertFalse(OqlQuery.parse("SELECT * FROM /o WHERE status = 'active'").matches(StoredValue.stringValue("active")),
                "field predicate does not match a non-map (scalar) value");
    }

    @Test
    void bindParametersRendersTypedLiteralsAndYieldsAWorkingPredicate() {
        String bound = OqlQuery.bindParameters(
                "SELECT * FROM /o r WHERE r.amount > $1 AND r.status = $2",
                List.of(15, "active"));
        assertEquals("SELECT * FROM /o r WHERE r.amount > 15 AND r.status = 'active'", bound);

        OqlQuery q = OqlQuery.parse(bound);
        assertTrue(q.matches(map("amount", 20, "status", "active")));
        assertFalse(q.matches(map("amount", 10, "status", "active")), "fails the amount bound");
        assertFalse(q.matches(map("amount", 20, "status", "closed")), "fails the status bound");
    }

    @Test
    void bindParametersHandlesBooleanNullAndMultiDigitIndexes() {
        List<Object> params = new ArrayList<>();
        params.add(true);   // $1
        params.add(null);   // $2
        for (int i = 3; i <= 10; i++) {
            params.add(i * 100); // $3..$10
        }
        String bound = OqlQuery.bindParameters("a = $1 AND b = $2 AND c = $10", params);
        assertEquals("a = true AND b = null AND c = 1000", bound,
                "$10 is substituted as a whole, not as $1 followed by 0");
    }

    @Test
    void bindParametersWithNoPlaceholdersOrNoParamsIsUnchanged() {
        assertEquals("SELECT * FROM /o", OqlQuery.bindParameters("SELECT * FROM /o", List.of(1, 2)));
        assertEquals("SELECT * FROM /o", OqlQuery.bindParameters("SELECT * FROM /o", List.of()));
    }

    @Test
    void bindParametersRejectsOutOfRangeReference() {
        assertThrows(OqlQuery.UnsupportedQueryException.class,
                () -> OqlQuery.bindParameters("SELECT * FROM /o WHERE x = $2", List.of(1)));
    }
}
