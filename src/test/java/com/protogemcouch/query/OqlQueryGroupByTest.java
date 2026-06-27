package com.protogemcouch.query;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static com.protogemcouch.query.OqlQuery.AggregateFunction.*;
import static org.junit.jupiter.api.Assertions.*;

class OqlQueryGroupByTest {

    // Four PDX-like rows: status, category, amount
    // k1=active/A/10, k2=active/B/20, k3=closed/A/30, k4=closed/A/40
    private static StoredValue row(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return StoredValue.stringObjectHashMapValue(m);
    }

    private static final List<StoredValue> ROWS = List.of(
            row("status", "active", "category", "A", "amount", 10),
            row("status", "active", "category", "B", "amount", 20),
            row("status", "closed", "category", "A", "amount", 30),
            row("status", "closed", "category", "A", "amount", 40)
    );

    private static final OqlQuery.FieldResolver RESOLVER = OqlQuery.MAP_RESOLVER;

    // --- parse ---

    @Test
    void parsesGroupByCountStar() {
        OqlQuery q = OqlQuery.parse("SELECT status, COUNT(*) FROM /orders GROUP BY status");
        assertTrue(q.isGroupBy(), "should be a GROUP BY query");
        assertFalse(q.isAggregate(), "GROUP BY should not be classified as aggregate");
        List<OqlQuery.GroupByColumn> cols = q.groupByColumns();
        assertEquals(2, cols.size());
        assertTrue(cols.get(0).isGroupKey());
        assertEquals("status", cols.get(0).columnName());
        assertEquals(COUNT_STAR, cols.get(1).fn());
        assertEquals("0", cols.get(1).columnName());
    }

    @Test
    void parsesGroupBySum() {
        OqlQuery q = OqlQuery.parse("SELECT status, SUM(amount) FROM /orders GROUP BY status");
        assertTrue(q.isGroupBy());
        List<OqlQuery.GroupByColumn> cols = q.groupByColumns();
        assertEquals(SUM, cols.get(1).fn());
        assertEquals("amount", cols.get(1).columnName());
    }

    @Test
    void parsesGroupByAvg() {
        OqlQuery q = OqlQuery.parse("SELECT status, AVG(amount) FROM /orders GROUP BY status");
        assertEquals(AVG, q.groupByColumns().get(1).fn());
    }

    @Test
    void parsesGroupByMin() {
        OqlQuery q = OqlQuery.parse("SELECT status, MIN(amount) FROM /orders GROUP BY status");
        assertEquals(MIN, q.groupByColumns().get(1).fn());
    }

    @Test
    void parsesGroupByMax() {
        OqlQuery q = OqlQuery.parse("SELECT status, MAX(amount) FROM /orders GROUP BY status");
        assertEquals(MAX, q.groupByColumns().get(1).fn());
    }

    @Test
    void parsesGroupByCountField() {
        OqlQuery q = OqlQuery.parse("SELECT status, COUNT(amount) FROM /orders GROUP BY status");
        assertEquals(COUNT_FIELD, q.groupByColumns().get(1).fn());
        assertEquals("0", q.groupByColumns().get(1).columnName());
    }

    @Test
    void parsesRegionPath() {
        OqlQuery q = OqlQuery.parse("SELECT status, COUNT(*) FROM /myRegion GROUP BY status");
        assertEquals("/myRegion", q.regionPath());
    }

    @Test
    void parsesGroupByWithWhere() {
        OqlQuery q = OqlQuery.parse(
                "SELECT status, COUNT(*) FROM /orders WHERE category = 'A' GROUP BY status");
        assertTrue(q.isGroupBy());
        assertTrue(q.hasWhere());
    }

    // --- computeGroupBy ---

    @Test
    void groupByStatusCountStar() {
        OqlQuery q = OqlQuery.parse("SELECT status, COUNT(*) FROM /orders GROUP BY status");
        List<List<Object>> result = q.computeGroupBy(ROWS, RESOLVER);
        assertEquals(2, result.size());
        List<Object> active = findGroup(result, "active");
        List<Object> closed = findGroup(result, "closed");
        assertNotNull(active);
        assertNotNull(closed);
        assertEquals(2, ((Number) active.get(1)).intValue());
        assertEquals(2, ((Number) closed.get(1)).intValue());
    }

    @Test
    void groupByStatusSum() {
        OqlQuery q = OqlQuery.parse("SELECT status, SUM(amount) FROM /orders GROUP BY status");
        List<List<Object>> result = q.computeGroupBy(ROWS, RESOLVER);
        List<Object> active = findGroup(result, "active");
        List<Object> closed = findGroup(result, "closed");
        assertNotNull(active);
        assertNotNull(closed);
        assertEquals(30, ((Number) active.get(1)).intValue(), "active: 10+20=30");
        assertEquals(70, ((Number) closed.get(1)).intValue(), "closed: 30+40=70");
    }

    @Test
    void groupByStatusAvgIntegerResult() {
        OqlQuery q = OqlQuery.parse("SELECT status, AVG(amount) FROM /orders GROUP BY status");
        List<List<Object>> result = q.computeGroupBy(ROWS, RESOLVER);
        List<Object> active = findGroup(result, "active");
        List<Object> closed = findGroup(result, "closed");
        assertNotNull(active);
        assertNotNull(closed);
        assertEquals(15, ((Number) active.get(1)).intValue(), "active: (10+20)/2=15 (integer avg)");
        assertEquals(35, ((Number) closed.get(1)).intValue(), "closed: (30+40)/2=35 (integer avg)");
    }

    @Test
    void groupByStatusMin() {
        OqlQuery q = OqlQuery.parse("SELECT status, MIN(amount) FROM /orders GROUP BY status");
        List<List<Object>> result = q.computeGroupBy(ROWS, RESOLVER);
        assertEquals(10, ((Number) findGroup(result, "active").get(1)).intValue());
        assertEquals(30, ((Number) findGroup(result, "closed").get(1)).intValue());
    }

    @Test
    void groupByStatusMax() {
        OqlQuery q = OqlQuery.parse("SELECT status, MAX(amount) FROM /orders GROUP BY status");
        List<List<Object>> result = q.computeGroupBy(ROWS, RESOLVER);
        assertEquals(20, ((Number) findGroup(result, "active").get(1)).intValue());
        assertEquals(40, ((Number) findGroup(result, "closed").get(1)).intValue());
    }

    @Test
    void groupByWithWhereFiltersFirst() {
        OqlQuery q = OqlQuery.parse(
                "SELECT status, COUNT(*) FROM /orders WHERE category = 'A' GROUP BY status");
        // After WHERE, only k1(active/A) and k3,k4(closed/A) remain
        List<StoredValue> matched = ROWS.stream()
                .filter(sv -> q.matches(sv, RESOLVER))
                .toList();
        assertEquals(3, matched.size());
        List<List<Object>> result = q.computeGroupBy(matched, RESOLVER);
        List<Object> active = findGroup(result, "active");
        List<Object> closed = findGroup(result, "closed");
        assertEquals(1, ((Number) active.get(1)).intValue());
        assertEquals(2, ((Number) closed.get(1)).intValue());
    }

    @Test
    void groupByEmptyInputProducesEmptyResult() {
        OqlQuery q = OqlQuery.parse("SELECT status, COUNT(*) FROM /orders GROUP BY status");
        List<List<Object>> result = q.computeGroupBy(List.of(), RESOLVER);
        assertTrue(result.isEmpty());
    }

    // --- helper ---

    private static List<Object> findGroup(List<List<Object>> result, String key) {
        for (List<Object> row : result) {
            if (key.equals(row.get(0))) return row;
        }
        return null;
    }
}
