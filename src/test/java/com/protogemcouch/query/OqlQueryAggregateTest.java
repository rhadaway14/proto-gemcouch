package com.protogemcouch.query;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static com.protogemcouch.query.OqlQuery.AggregateFunction.*;
import static org.junit.jupiter.api.Assertions.*;

class OqlQueryAggregateTest {

    private static StoredValue map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return StoredValue.stringObjectHashMapValue(m);
    }

    private static List<StoredValue> rows() {
        return List.of(
                map("amount", 10, "status", "active"),
                map("amount", 20, "status", "closed"),
                map("amount", 30, "status", "active"),
                map("amount", 40, "status", "closed")
        );
    }

    // --- parsing ---

    @Test
    void parsesCountStar() {
        OqlQuery q = OqlQuery.parse("SELECT COUNT(*) FROM /orders");
        assertTrue(q.isAggregate());
        assertEquals(COUNT_STAR, q.aggregateFunction());
        assertEquals("/orders", q.regionPath());
    }

    @Test
    void parsesCountField() {
        OqlQuery q = OqlQuery.parse("SELECT COUNT(amount) FROM /orders");
        assertTrue(q.isAggregate());
        assertEquals(COUNT_FIELD, q.aggregateFunction());
    }

    @Test
    void parsesSum() {
        OqlQuery q = OqlQuery.parse("SELECT SUM(amount) FROM /orders");
        assertTrue(q.isAggregate());
        assertEquals(SUM, q.aggregateFunction());
    }

    @Test
    void parsesMin() {
        OqlQuery q = OqlQuery.parse("SELECT MIN(amount) FROM /orders");
        assertEquals(MIN, q.aggregateFunction());
    }

    @Test
    void parsesMax() {
        OqlQuery q = OqlQuery.parse("SELECT MAX(amount) FROM /orders");
        assertEquals(MAX, q.aggregateFunction());
    }

    @Test
    void parsesAvg() {
        OqlQuery q = OqlQuery.parse("SELECT AVG(amount) FROM /orders");
        assertEquals(AVG, q.aggregateFunction());
    }

    @Test
    void parsesAggregateWithAlias() {
        OqlQuery q = OqlQuery.parse("SELECT COUNT(*) FROM /orders o WHERE o.status = 'active'");
        assertTrue(q.isAggregate());
        assertEquals(COUNT_STAR, q.aggregateFunction());
        assertTrue(q.hasWhere());
    }

    @Test
    void parsesAggregateWithLimit() {
        OqlQuery q = OqlQuery.parse("SELECT COUNT(*) FROM /orders LIMIT 10");
        assertTrue(q.isAggregate());
        assertTrue(q.hasLimit());
    }

    @Test
    void aggregateWithOrderByIsUnsupported() {
        assertThrows(OqlQuery.UnsupportedQueryException.class,
                () -> OqlQuery.parse("SELECT COUNT(*) FROM /orders ORDER BY amount"));
    }

    @Test
    void nonAggregateIsNotAggregate() {
        assertFalse(OqlQuery.parse("SELECT * FROM /orders").isAggregate());
        assertFalse(OqlQuery.parse("SELECT amount FROM /orders").isAggregate());
    }

    // --- computation ---

    @Test
    void countStarReturnsRowCount() {
        OqlQuery q = OqlQuery.parse("SELECT COUNT(*) FROM /orders");
        assertEquals(4, q.computeAggregateRaw(rows(), OqlQuery.MAP_RESOLVER));
    }

    @Test
    void countStarOverEmptySetIsZero() {
        OqlQuery q = OqlQuery.parse("SELECT COUNT(*) FROM /orders");
        assertEquals(0, q.computeAggregateRaw(List.of(), OqlQuery.MAP_RESOLVER));
    }

    @Test
    void countFieldSkipsNulls() {
        OqlQuery q = OqlQuery.parse("SELECT COUNT(amount) FROM /orders");
        List<StoredValue> withNull = List.of(
                map("amount", 10),
                map("amount", null),
                map("other", "x"),  // amount absent
                map("amount", 30)
        );
        assertEquals(2, q.computeAggregateRaw(withNull, OqlQuery.MAP_RESOLVER));
    }

    @Test
    void sumReturnsDoubleSum() {
        OqlQuery q = OqlQuery.parse("SELECT SUM(amount) FROM /orders");
        Object result = q.computeAggregateRaw(rows(), OqlQuery.MAP_RESOLVER);
        assertEquals(100.0, ((Number) result).doubleValue(), 1e-9);
    }

    @Test
    void sumOverEmptySetIsZero() {
        OqlQuery q = OqlQuery.parse("SELECT SUM(amount) FROM /orders");
        Object result = q.computeAggregateRaw(List.of(), OqlQuery.MAP_RESOLVER);
        assertEquals(0.0, ((Number) result).doubleValue(), 1e-9);
    }

    @Test
    void sumSkipsNonNumeric() {
        OqlQuery q = OqlQuery.parse("SELECT SUM(amount) FROM /orders");
        List<StoredValue> mixed = List.of(map("amount", 10), map("amount", "not-a-number"), map("amount", 5));
        Object result = q.computeAggregateRaw(mixed, OqlQuery.MAP_RESOLVER);
        assertEquals(15.0, ((Number) result).doubleValue(), 1e-9);
    }

    @Test
    void avgReturnsDoubleMean() {
        OqlQuery q = OqlQuery.parse("SELECT AVG(amount) FROM /orders");
        Object result = q.computeAggregateRaw(rows(), OqlQuery.MAP_RESOLVER);
        assertEquals(25.0, ((Number) result).doubleValue(), 1e-9);
    }

    @Test
    void avgOverEmptySetIsNull() {
        OqlQuery q = OqlQuery.parse("SELECT AVG(amount) FROM /orders");
        assertNull(q.computeAggregateRaw(List.of(), OqlQuery.MAP_RESOLVER));
    }

    @Test
    void minReturnsMinimum() {
        OqlQuery q = OqlQuery.parse("SELECT MIN(amount) FROM /orders");
        Object result = q.computeAggregateRaw(rows(), OqlQuery.MAP_RESOLVER);
        assertEquals(10, ((Number) result).intValue());
    }

    @Test
    void maxReturnsMaximum() {
        OqlQuery q = OqlQuery.parse("SELECT MAX(amount) FROM /orders");
        Object result = q.computeAggregateRaw(rows(), OqlQuery.MAP_RESOLVER);
        assertEquals(40, ((Number) result).intValue());
    }

    @Test
    void minMaxOverEmptySetIsNull() {
        OqlQuery q = OqlQuery.parse("SELECT MIN(amount) FROM /orders");
        assertNull(q.computeAggregateRaw(List.of(), OqlQuery.MAP_RESOLVER));
        OqlQuery q2 = OqlQuery.parse("SELECT MAX(amount) FROM /orders");
        assertNull(q2.computeAggregateRaw(List.of(), OqlQuery.MAP_RESOLVER));
    }

    @Test
    void minMaxOnStrings() {
        OqlQuery minQ = OqlQuery.parse("SELECT MIN(status) FROM /orders");
        Object min = minQ.computeAggregateRaw(rows(), OqlQuery.MAP_RESOLVER);
        assertEquals("active", min);

        OqlQuery maxQ = OqlQuery.parse("SELECT MAX(status) FROM /orders");
        Object max = maxQ.computeAggregateRaw(rows(), OqlQuery.MAP_RESOLVER);
        assertEquals("closed", max);
    }

    @Test
    void aggregateRespectsWhereClause() {
        OqlQuery q = OqlQuery.parse("SELECT COUNT(*) FROM /orders WHERE status = 'active'");
        List<StoredValue> matched = rows().stream()
                .filter(v -> q.matches(v))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2, q.computeAggregateRaw(matched, OqlQuery.MAP_RESOLVER));
    }
}
