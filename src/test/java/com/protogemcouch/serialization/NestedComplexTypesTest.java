package com.protogemcouch.serialization;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the in-process layers of nested-complex-type support inside a
 * {@code HashMap<String,Object>} region value: the structured-decode gate (which decides structured
 * vs. opaque), the shared deep copy, and {@link StoredValue} deep equality/hashCode. The full
 * JSON-persistence + wire round-trip against a real Geode client is exercised separately by the
 * Docker-backed integration suite.
 */
class NestedComplexTypesTest {

    /** A Geode {@code LinkedHashMap<String,Object>} value on the wire: {@code 0x2c} + Java bytes. */
    private static byte[] geodeMapBytes(Map<String, Object> map) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(raw)) {
            out.writeObject(map);
        }
        byte[] serialized = raw.toByteArray();
        byte[] framed = new byte[serialized.length + 1];
        framed[0] = 0x2c;
        System.arraycopy(serialized, 0, framed, 1, serialized.length);
        return framed;
    }

    @Test
    void nestedContainersAndScalarExtrasDecodeStructurally() throws IOException {
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("name", "widget");
        source.put("count", 5);
        source.put("tags", new Object[] {"a", 2, true});
        source.put("scores", new ArrayList<>(List.of(3L, 4L)));

        LinkedHashMap<String, Object> child = new LinkedHashMap<>();
        child.put("city", "Austin");
        child.put("zip", 78701);
        source.put("address", child);

        source.put("id", UUID.fromString("12345678-1234-1234-1234-123456789abc"));
        source.put("big", new BigInteger("123456789012345678901234567890"));
        source.put("price", new BigDecimal("19.99"));
        source.put("unit", TimeUnit.SECONDS);
        source.put("ts", Instant.parse("2026-06-22T15:30:00Z"));
        source.put("day", LocalDate.parse("2026-06-22"));
        source.put("dt", LocalDateTime.parse("2026-06-22T15:30:00"));

        LinkedHashMap<String, Object> decoded =
                ValueDecoding.decodeStringObjectHashMapValue(geodeMapBytes(source));

        // The whole map stays structured (queryable) rather than collapsing to opaque bytes.
        assertNotNull(decoded, "map with nested complex values should decode structurally");
        assertEquals("widget", decoded.get("name"));
        assertEquals(5, decoded.get("count"));

        Object[] tags = assertInstanceOf(Object[].class, decoded.get("tags"));
        assertArrayEquals(new Object[] {"a", 2, true}, tags);

        List<?> scores = assertInstanceOf(List.class, decoded.get("scores"));
        assertEquals(List.of(3L, 4L), scores);

        Map<?, ?> address = assertInstanceOf(Map.class, decoded.get("address"));
        assertEquals("Austin", address.get("city"));
        assertEquals(78701, address.get("zip"));

        assertEquals(UUID.fromString("12345678-1234-1234-1234-123456789abc"), decoded.get("id"));
        assertEquals(new BigInteger("123456789012345678901234567890"), decoded.get("big"));
        assertEquals(new BigDecimal("19.99"), decoded.get("price"));
        assertEquals(TimeUnit.SECONDS, decoded.get("unit"));
        assertEquals(Instant.parse("2026-06-22T15:30:00Z"), decoded.get("ts"));
        assertEquals(LocalDate.parse("2026-06-22"), decoded.get("day"));
        assertEquals(LocalDateTime.parse("2026-06-22T15:30:00"), decoded.get("dt"));
    }

    @Test
    void mapContainingUnsupportedPojoStaysOpaque() throws IOException {
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("ok", "value");
        source.put("widget", new Widget("w1"));

        // Even though Widget is loadable in this JVM, it is outside the structured/queryable set, so
        // the structured decoder declines (returns null) and the caller falls back to the opaque
        // Java-serialized path.
        assertNull(ValueDecoding.decodeStringObjectHashMapValue(geodeMapBytes(source)));
    }

    @Test
    void supportPredicateAcceptsNestedAndExtrasButNotPojo() {
        assertTrue(NestedValueSupport.isSupportedValue(new Object[] {"a", 1, new int[] {2}}));
        assertTrue(NestedValueSupport.isSupportedValue(new ArrayList<>(List.of("x", 9))));
        assertTrue(NestedValueSupport.isSupportedValue(Map.of("k", 1)));
        assertTrue(NestedValueSupport.isSupportedValue(UUID.randomUUID()));
        assertTrue(NestedValueSupport.isSupportedValue(BigInteger.ONE));
        assertTrue(NestedValueSupport.isSupportedValue(BigDecimal.TEN));
        assertTrue(NestedValueSupport.isSupportedValue(TimeUnit.DAYS));
        assertTrue(NestedValueSupport.isSupportedValue(Instant.now()));
        assertTrue(NestedValueSupport.isSupportedValue(LocalDate.now()));
        assertTrue(NestedValueSupport.isSupportedValue(LocalDateTime.now()));

        assertFalse(NestedValueSupport.isSupportedValue(new Widget("w")));
        assertFalse(NestedValueSupport.isSupportedValue(new Object[] {new Widget("w")}));
        assertFalse(NestedValueSupport.isSupportedValue(Map.of("k", new Widget("w"))));
    }

    @Test
    void copyValueDeepCopiesNestedMutableState() {
        int[] inner = {1, 2, 3};
        Object[] array = {inner, "x"};
        ArrayList<Object> list = new ArrayList<>(List.of("a"));
        LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
        nested.put("arr", array);
        nested.put("list", list);

        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) NestedValueSupport.copyValue(nested);

        assertNotSame(nested, copy);
        Object[] copiedArray = (Object[]) copy.get("arr");
        assertNotSame(array, copiedArray);
        assertNotSame(inner, copiedArray[0]);

        // Mutating the source leaves the copy untouched.
        inner[0] = 99;
        list.add("b");
        assertArrayEquals(new int[] {1, 2, 3}, (int[]) ((Object[]) copy.get("arr"))[0]);
        assertEquals(List.of("a"), copy.get("list"));
    }

    @Test
    void copyValuePreservesTypedArrayComponentType() {
        // Regression guard (1.3.0-M3): copyValue once normalized ANY Object[] to a generic Object[],
        // which — once typed object arrays were promoted to the structured path — would have silently
        // downgraded e.g. an Integer[] to Object[] and broken its exact-type round-trip. It must keep
        // the component type.
        Integer[] ints = {1, 2, 3};
        Object copiedInts = NestedValueSupport.copyValue(ints);
        assertInstanceOf(Integer[].class, copiedInts, "Integer[] stays Integer[]");
        assertNotSame(ints, copiedInts);
        assertArrayEquals(ints, (Integer[]) copiedInts);

        assertInstanceOf(UUID[].class, NestedValueSupport.copyValue(new UUID[] {UUID.randomUUID()}),
                "UUID[] stays UUID[]");
        // A generic Object[] still stays a generic Object[].
        assertEquals(Object[].class, NestedValueSupport.copyValue(new Object[] {"a", 1}).getClass());

        // And inside a map, the typed array survives the deep copy with its component type intact.
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("scores", new Integer[] {10, 20});
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) NestedValueSupport.copyValue(map);
        assertInstanceOf(Integer[].class, copy.get("scores"), "nested Integer[] stays Integer[] after deep copy");
        assertArrayEquals(new Integer[] {10, 20}, (Integer[]) copy.get("scores"));
    }

    @Test
    void supportedSetAcceptsTypedArraysListsAndSets() {
        assertTrue(NestedValueSupport.isSupportedValue(new Integer[] {1, 2}), "typed object array (Integer[])");
        assertTrue(NestedValueSupport.isSupportedValue(new UUID[] {UUID.randomUUID()}), "typed object array (UUID[])");
        assertTrue(NestedValueSupport.isSupportedValue(new java.util.LinkedList<>(List.of("a"))), "any List");
        assertTrue(NestedValueSupport.isSupportedValue(new java.util.TreeSet<>(List.of("x", "y"))), "any Set");
        // A typed array of an unsupported component (a customer class) stays out → opaque path.
        assertFalse(NestedValueSupport.isSupportedValue(new Widget[] {new Widget("w")}));
    }

    @Test
    void storedValueDeepEqualsAndHashCodeForNestedGraphs() {
        StoredValue a = StoredValue.stringObjectHashMapValue(sampleNestedMap());
        StoredValue b = StoredValue.stringObjectHashMapValue(sampleNestedMap());

        // Distinct array/list/map instances with equal contents must compare equal and hash equal.
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        LinkedHashMap<String, Object> different = sampleNestedMap();
        ((Object[]) different.get("tags"))[0] = "zzz";
        assertNotEquals(a, StoredValue.stringObjectHashMapValue(different));
    }

    @Test
    void oqlWhereMatchesTopLevelFieldOfStructuredMapWithNestedFields() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("status", "active");
        map.put("amount", 100);
        map.put("tags", new Object[] {"a", 1, Boolean.TRUE});
        LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
        nested.put("city", "Austin");
        nested.put("zip", 78701);
        map.put("nested", nested);

        StoredValue value = StoredValue.stringObjectHashMapValue(map);
        com.protogemcouch.query.OqlQuery query =
                com.protogemcouch.query.OqlQuery.parse("SELECT * FROM /r WHERE status = 'active'");

        assertTrue(query.matches(value),
                "WHERE on a top-level field should match a structured map that also has nested fields");
    }

    private static LinkedHashMap<String, Object> sampleNestedMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("tags", new Object[] {"a", "b"});
        map.put("scores", new ArrayList<>(List.of(1, 2)));
        LinkedHashMap<String, Object> child = new LinkedHashMap<>();
        child.put("nums", new int[] {7, 8});
        map.put("child", child);
        return map;
    }

    private static void assertNotEquals(Object a, Object b) {
        assertFalse(java.util.Objects.equals(a, b), "expected values to differ");
    }

    private static final class Widget implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private final String id;

        private Widget(String id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Widget other && java.util.Objects.equals(id, other.id);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hashCode(id);
        }
    }
}
