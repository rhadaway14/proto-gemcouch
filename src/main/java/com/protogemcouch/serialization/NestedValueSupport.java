package com.protogemcouch.serialization;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single source of truth for which values inside a {@code HashMap<String,Object>} region value the
 * shim decodes <em>structurally</em> (a queryable JSON envelope with an exact round-trip) versus
 * leaves <em>opaque</em> (preserved as Java-serialized bytes, but not queryable).
 *
 * <p>The supported set is recursive: nested {@code Map<String,Object>}, {@code Object[]}, and
 * {@code List} are supported when every nested value is itself supported, alongside the scalar
 * types, wrappers, {@link Date}, primitive / {@code String} arrays, the {@code java.time} scalars
 * ({@code Instant}, {@code LocalDate}, {@code LocalDateTime}), and the common JDK scalar extras
 * {@link UUID}, {@link BigInteger}, {@link BigDecimal}, and {@code enum} constants.
 *
 * <p><strong>Deliberate boundary:</strong> customer POJOs and PDX instances nested inside a map are
 * <em>not</em> supported here — the shim cannot load their classes to deserialize / query them, so
 * a map containing one falls back to the opaque Java-serialized path (it still round-trips; it is
 * just not queryable). This is the same boundary documented for OQL POJO field access.
 *
 * <p><strong>Fidelity contract:</strong> round-trip is <em>equals-level</em>, matching the existing
 * top-level behavior (a client {@code HashMap} already comes back as a {@code LinkedHashMap}).
 * Nested containers reconstruct as {@code Object[]} / {@link ArrayList} / {@link LinkedHashMap} with
 * every element value and scalar runtime type preserved, so {@code Arrays.equals} / {@code Map.equals}
 * / {@code List.equals} hold; the concrete container/array class is normalized.
 */
public final class NestedValueSupport {

    private NestedValueSupport() {
    }

    /** True when every key is a String (or null) and every value is {@link #isSupportedValue}. */
    public static boolean isSupportedStringObjectMap(Map<?, ?> value) {
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (entry.getKey() != null && !(entry.getKey() instanceof String)) {
                return false;
            }

            if (!isSupportedValue(entry.getValue())) {
                return false;
            }
        }

        return true;
    }

    /** True when {@code value} is in the structured/queryable set (recursively for containers). */
    public static boolean isSupportedValue(Object value) {
        return value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof Date
                || value instanceof java.time.Instant
                || value instanceof java.time.LocalDate
                || value instanceof java.time.LocalDateTime
                || value instanceof BigInteger
                || value instanceof BigDecimal
                || value instanceof UUID
                || value instanceof Enum<?>
                || value instanceof byte[]
                || value instanceof boolean[]
                || value instanceof char[]
                || value instanceof short[]
                || value instanceof int[]
                || value instanceof long[]
                || value instanceof float[]
                || value instanceof double[]
                || value instanceof String[]
                || isSupportedObjectArray(value)
                || isSupportedTypedObjectArray(value)
                || isSupportedList(value)
                || isSupportedNestedMap(value);
    }

    /**
     * Component types of a <em>typed</em> object array ({@code Integer[]}, {@code UUID[]}, …) that the
     * structured codec reconstructs with exact fidelity (1.3.0-M3). {@code String[]} and the primitive
     * arrays are handled by their own faithful branches; a generic {@code Object[]} by
     * {@link #isSupportedObjectArray}. Enum component types are accepted dynamically (see below).
     */
    private static final java.util.Set<Class<?>> SUPPORTED_ARRAY_COMPONENTS = java.util.Set.of(
            Integer.class, Long.class, Short.class, Byte.class, Double.class, Float.class,
            Boolean.class, Character.class, Date.class, UUID.class, BigInteger.class, BigDecimal.class,
            java.time.Instant.class, java.time.LocalDate.class, java.time.LocalDateTime.class);

    private static boolean isSupportedObjectArray(Object value) {
        // Only a *generic* Object[] is promoted here; it reconstructs as Object[]. Typed object arrays
        // (Integer[], UUID[], …) go through isSupportedTypedObjectArray, which records the component type
        // so they reconstruct exactly rather than being normalized to Object[].
        if (value == null || value.getClass() != Object[].class) {
            return false;
        }

        for (Object item : (Object[]) value) {
            if (!isSupportedValue(item)) {
                return false;
            }
        }

        return true;
    }

    /** True for a typed object array whose component type is reconstructible and whose elements are all supported. */
    static boolean isSupportedTypedObjectArray(Object value) {
        if (value == null) {
            return false;
        }
        Class<?> cls = value.getClass();
        if (!cls.isArray()) {
            return false;
        }
        Class<?> component = cls.getComponentType();
        // Primitive arrays, String[], and the generic Object[] are handled by their own branches.
        if (component == null || component.isPrimitive() || component == Object.class || component == String.class) {
            return false;
        }
        if (!SUPPORTED_ARRAY_COMPONENTS.contains(component) && !component.isEnum()) {
            return false;
        }
        for (Object item : (Object[]) value) {
            if (item != null && !isSupportedValue(item)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupportedList(Object value) {
        // Restricted to ArrayList, which reconstructs as ArrayList; other List implementations keep
        // their concrete type only on the opaque path.
        if (!(value instanceof ArrayList<?> list)) {
            return false;
        }

        for (Object item : list) {
            if (!isSupportedValue(item)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isSupportedNestedMap(Object value) {
        return value instanceof Map<?, ?> map && isSupportedStringObjectMap(map);
    }

    /**
     * Deep, defensive copy of a supported value so a {@link StoredValue} stays immutable. Scalars,
     * wrappers, the JDK extras, and {@code enum} constants are immutable and returned as-is; mutable
     * arrays / collections / maps are copied recursively. A value outside the supported set is
     * returned unchanged (callers gate with {@link #isSupportedValue} first).
     */
    @SuppressWarnings("unchecked")
    public static Object copyValue(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof boolean[] booleans) {
            return booleans.clone();
        }
        if (value instanceof char[] chars) {
            return chars.clone();
        }
        if (value instanceof short[] shorts) {
            return shorts.clone();
        }
        if (value instanceof int[] ints) {
            return ints.clone();
        }
        if (value instanceof long[] longs) {
            return longs.clone();
        }
        if (value instanceof float[] floats) {
            return floats.clone();
        }
        if (value instanceof double[] doubles) {
            return doubles.clone();
        }
        if (value instanceof String[] strings) {
            return strings.clone();
        }
        if (value instanceof Object[] array) {
            // Preserve the exact component type (Integer[] stays Integer[], generic Object[] stays Object[]),
            // copying elements defensively so a stored value stays immutable.
            Object[] copy = (Object[]) java.lang.reflect.Array.newInstance(
                    value.getClass().getComponentType(), array.length);
            for (int i = 0; i < array.length; i++) {
                copy[i] = copyValue(array[i]);
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(copyValue(item));
            }
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                copy.put(key == null ? null : String.valueOf(key), copyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof Date date) {
            return new Date(date.getTime());
        }

        // String, wrappers, BigInteger, BigDecimal, UUID, Enum, java.time (Instant/LocalDate/
        // LocalDateTime), and null are immutable.
        return value;
    }
}
