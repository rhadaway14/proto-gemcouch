package com.protogemcouch.testsupport;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic, seeded generator of random {@code HashMap<String,Object>} value graphs drawn from
 * the shim's <em>structured</em> supported-value set (see
 * {@code com.protogemcouch.serialization.NestedValueSupport}): scalars, wrappers, {@link Date},
 * {@link BigInteger}/{@link BigDecimal}/{@link UUID}, JDK enum constants, primitive arrays,
 * {@code String[]}, a generic {@code Object[]}, {@link ArrayList}, and nested
 * {@code Map<String,Object>} — recursively, to a bounded depth.
 *
 * <p>Used by the round-trip property tests. Everything it emits is intended to round-trip exactly
 * through the shim's persistence + wire layers, so the generator deliberately stays inside the
 * structured set and avoids values that are out of scope or representation-unstable:
 * <ul>
 *   <li><b>JDK enums only</b> ({@link DayOfWeek}/{@link TimeUnit}) — a real shim must be able to load
 *       the enum class to decode it; a test-only enum would force the opaque path on a remote shim.</li>
 *   <li><b>Typed object arrays</b> (e.g. {@code Integer[]}, {@code UUID[]}, {@code Instant[]},
 *       {@code DayOfWeek[]}) — promoted to the structured path with exact component-type fidelity as of
 *       1.3.0-M3 (component types stay within the supported set), alongside the generic {@code Object[]}.</li>
 *   <li><b>Exactly-representable floats/doubles</b> (whole numbers + simple binary fractions) — this
 *       test targets structural/nesting combinatorics, not IEEE-754 / JSON float-precision edges.</li>
 *   <li><b>JSON-safe chars/strings</b> — no lone surrogates or control characters.</li>
 *   <li><b>Non-null, unique map keys.</b></li>
 * </ul>
 */
public final class RandomValueGraphs {

    private static final Enum<?>[] ENUMS = {
            DayOfWeek.MONDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY,
            TimeUnit.SECONDS, TimeUnit.DAYS, TimeUnit.MILLISECONDS
    };

    // BMP, no surrogates, no control chars; a mix of ASCII and a little Unicode.
    private static final char[] SAFE_CHARS =
            "abcXYZ0189 _-.,:/áéíñ中日".toCharArray();

    private static final int[] STRING_CODE_POINTS = {
            'a', 'Z', '0', ' ', '_', '-', '/', '.', 0x00E9 /* é */, 0x00F1 /* ñ */,
            0x4E2D /* 中 */, 0x65E5 /* 日 */, 0x1F600 /* 😀 (surrogate pair) */
    };

    // Whole numbers plus simple binary fractions: all exactly representable, so they survive a
    // JSON text round-trip without IEEE-754 noise.
    private static final float[] FLOAT_FRACTIONS = {0f, 0.5f, 0.25f, 0.75f, 0.125f};
    private static final double[] DOUBLE_FRACTIONS = {0d, 0.5d, 0.25d, 0.75d, 0.125d};

    private RandomValueGraphs() {
    }

    /** A random top-level {@code Map<String,Object>} with the default depth/size budget. */
    public static LinkedHashMap<String, Object> randomMap(Random r) {
        return randomMap(r, 3);
    }

    private static LinkedHashMap<String, Object> randomMap(Random r, int depth) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        int size = r.nextInt(7); // 0..6
        for (int i = 0; i < size; i++) {
            map.put("f" + i + "_" + randomKeySuffix(r), randomValue(r, depth - 1));
        }
        return map;
    }

    /** A random supported value; nested containers only while {@code depth > 0}. */
    public static Object randomValue(Random r, int depth) {
        int leaves = 29;
        int choices = depth > 0 ? leaves + 4 : leaves;

        switch (r.nextInt(choices)) {
            case 0: return null;
            case 1: return randomString(r);
            case 2: return r.nextBoolean();
            case 3: return randomSafeChar(r);
            case 4: return (byte) r.nextInt();
            case 5: return (short) r.nextInt();
            case 6: return r.nextInt();
            case 7: return r.nextLong();
            case 8: return randomFloat(r);
            case 9: return randomDouble(r);
            case 10: return new Date(Math.floorMod(r.nextLong(), 4_000_000_000_000L));
            case 11: return BigInteger.valueOf(r.nextLong()).multiply(BigInteger.valueOf(r.nextInt(1_000_000) + 1));
            case 12: return new BigDecimal(BigInteger.valueOf(r.nextLong()), r.nextInt(7));
            case 13: return new UUID(r.nextLong(), r.nextLong());
            case 14: return ENUMS[r.nextInt(ENUMS.length)];
            case 15: return randomBytes(r);
            case 16: return randomBooleanArray(r);
            case 17: return randomCharArray(r);
            case 18: return randomShortArray(r);
            case 19: return randomIntArray(r);
            case 20: return randomLongArray(r);
            case 21: return randomFloatArray(r);
            case 22: return randomDoubleArray(r);
            case 23: return randomStringArray(r);
            case 24: return Instant.ofEpochMilli(Math.floorMod(r.nextLong(), 4_000_000_000_000L));
            case 25: return LocalDate.ofEpochDay(r.nextInt(40_000)); // ~1970..2079
            case 26: return LocalDateTime.ofEpochSecond(
                    Math.floorMod(r.nextLong(), 4_000_000_000L), 0, ZoneOffset.UTC);
            case 27: return randomTypedObjectArray(r);
            case 28: return randomScalarSet(r);
            case 29: return randomObjectArray(r, depth);
            case 30: return randomArrayList(r, depth);
            case 31: return randomLinkedList(r, depth);
            default: return randomMap(r, depth);
        }
    }

    /**
     * A JDK Set (1.3.0-M3) of distinct scalars — HashSet / LinkedHashSet (mixed scalars) or a homogeneous
     * TreeSet — reconstructed as a LinkedHashSet (equals-level — Set.equals is implementation-agnostic).
     * Elements are scalars only: arrays/collections have no value-equals, so they are ill-defined inside a
     * Set regardless of storage path.
     */
    private static Object randomScalarSet(Random r) {
        int n = r.nextInt(4); // 0..3
        switch (r.nextInt(3)) {
            case 0: {
                java.util.Set<Object> set = new java.util.HashSet<>();
                for (int i = 0; i < n; i++) set.add(randomSetScalar(r));
                return set;
            }
            case 1: {
                java.util.Set<Object> set = new java.util.LinkedHashSet<>();
                for (int i = 0; i < n; i++) set.add(randomSetScalar(r));
                return set;
            }
            default: { // TreeSet needs homogeneous, mutually-comparable elements
                java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
                for (int i = 0; i < n; i++) set.add(r.nextInt(1000));
                return set;
            }
        }
    }

    /** A distinct, value-equal, JSON/type-stable scalar for a Set element. */
    private static Object randomSetScalar(Random r) {
        switch (r.nextInt(5)) {
            case 0: return r.nextInt();
            case 1: return randomString(r);
            case 2: return r.nextLong();
            case 3: return new UUID(r.nextLong(), r.nextLong());
            default: return ENUMS[r.nextInt(ENUMS.length)];
        }
    }

    /** A LinkedList (1.3.0-M3) — exercises the non-ArrayList path; reconstructs as an ArrayList (equals-level). */
    private static Object randomLinkedList(Random r, int depth) {
        int n = r.nextInt(5);
        java.util.LinkedList<Object> list = new java.util.LinkedList<>();
        for (int i = 0; i < n; i++) {
            list.add(randomValue(r, depth - 1));
        }
        return list;
    }

    /**
     * A typed object array (1.3.0-M3) of one supported component type, occasionally with null elements —
     * reconstructed to its exact type (e.g. {@code Integer[]} stays {@code Integer[]}, so
     * {@code Arrays.equals} holds), unlike a generic {@code Object[]}.
     */
    private static Object randomTypedObjectArray(Random r) {
        int n = r.nextInt(4); // 0..3
        switch (r.nextInt(12)) {
            case 0: { Integer[] a = new Integer[n]; for (int i = 0; i < n; i++) a[i] = nul(r) ? null : r.nextInt(); return a; }
            case 1: { Long[] a = new Long[n]; for (int i = 0; i < n; i++) a[i] = nul(r) ? null : r.nextLong(); return a; }
            case 2: { Short[] a = new Short[n]; for (int i = 0; i < n; i++) a[i] = nul(r) ? null : (short) r.nextInt(); return a; }
            case 3: { Boolean[] a = new Boolean[n]; for (int i = 0; i < n; i++) a[i] = nul(r) ? null : r.nextBoolean(); return a; }
            case 4: { Double[] a = new Double[n]; for (int i = 0; i < n; i++) a[i] = nul(r) ? null : (double) DOUBLE_FRACTIONS[r.nextInt(DOUBLE_FRACTIONS.length)]; return a; }
            case 5: { Character[] a = new Character[n]; for (int i = 0; i < n; i++) a[i] = nul(r) ? null : SAFE_CHARS[r.nextInt(SAFE_CHARS.length)]; return a; }
            case 6: { UUID[] a = new UUID[n]; for (int i = 0; i < n; i++) a[i] = nul(r) ? null : new UUID(r.nextLong(), r.nextLong()); return a; }
            case 7: { BigInteger[] a = new BigInteger[n]; for (int i = 0; i < n; i++) a[i] = nul(r) ? null : BigInteger.valueOf(r.nextLong()); return a; }
            case 8: { BigDecimal[] a = new BigDecimal[n]; for (int i = 0; i < n; i++) a[i] = nul(r) ? null : new BigDecimal(BigInteger.valueOf(r.nextLong()), r.nextInt(7)); return a; }
            case 9: { Instant[] a = new Instant[n]; for (int i = 0; i < n; i++) a[i] = nul(r) ? null : Instant.ofEpochMilli(Math.floorMod(r.nextLong(), 4_000_000_000_000L)); return a; }
            case 10: { Date[] a = new Date[n]; for (int i = 0; i < n; i++) a[i] = nul(r) ? null : new Date(Math.floorMod(r.nextLong(), 4_000_000_000_000L)); return a; }
            default: { DayOfWeek[] a = new DayOfWeek[n]; for (int i = 0; i < n; i++) a[i] = nul(r) ? null : DayOfWeek.of(1 + r.nextInt(7)); return a; }
        }
    }

    /** Occasionally (~1 in 5) emit a null element, to exercise nulls inside typed arrays. */
    private static boolean nul(Random r) {
        return r.nextInt(5) == 0;
    }

    private static Object[] randomObjectArray(Random r, int depth) {
        Object[] array = new Object[r.nextInt(5)];
        for (int i = 0; i < array.length; i++) {
            array[i] = randomValue(r, depth - 1);
        }
        return array;
    }

    private static ArrayList<Object> randomArrayList(Random r, int depth) {
        int n = r.nextInt(5);
        ArrayList<Object> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(randomValue(r, depth - 1));
        }
        return list;
    }

    private static String randomString(Random r) {
        int len = r.nextInt(9); // 0..8, includes empty
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.appendCodePoint(STRING_CODE_POINTS[r.nextInt(STRING_CODE_POINTS.length)]);
        }
        return sb.toString();
    }

    private static final char[] KEY_CHARS = "abcXYZ0189".toCharArray();

    private static String randomKeySuffix(Random r) {
        int len = 1 + r.nextInt(4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(KEY_CHARS[r.nextInt(KEY_CHARS.length)]);
        }
        return sb.toString();
    }

    private static char randomSafeChar(Random r) {
        return SAFE_CHARS[r.nextInt(SAFE_CHARS.length)];
    }

    private static float randomFloat(Random r) {
        float whole = r.nextInt(2_000_001) - 1_000_000;
        float frac = FLOAT_FRACTIONS[r.nextInt(FLOAT_FRACTIONS.length)];
        return r.nextBoolean() ? whole + frac : -(Math.abs(whole) + frac);
    }

    private static double randomDouble(Random r) {
        double whole = r.nextInt(2_000_001) - 1_000_000;
        double frac = DOUBLE_FRACTIONS[r.nextInt(DOUBLE_FRACTIONS.length)];
        return r.nextBoolean() ? whole + frac : -(Math.abs(whole) + frac);
    }

    private static byte[] randomBytes(Random r) {
        byte[] a = new byte[r.nextInt(6)];
        r.nextBytes(a);
        return a;
    }

    private static boolean[] randomBooleanArray(Random r) {
        boolean[] a = new boolean[r.nextInt(6)];
        for (int i = 0; i < a.length; i++) {
            a[i] = r.nextBoolean();
        }
        return a;
    }

    private static char[] randomCharArray(Random r) {
        char[] a = new char[r.nextInt(6)];
        for (int i = 0; i < a.length; i++) {
            a[i] = randomSafeChar(r);
        }
        return a;
    }

    private static short[] randomShortArray(Random r) {
        short[] a = new short[r.nextInt(6)];
        for (int i = 0; i < a.length; i++) {
            a[i] = (short) r.nextInt();
        }
        return a;
    }

    private static int[] randomIntArray(Random r) {
        int[] a = new int[r.nextInt(6)];
        for (int i = 0; i < a.length; i++) {
            a[i] = r.nextInt();
        }
        return a;
    }

    private static long[] randomLongArray(Random r) {
        long[] a = new long[r.nextInt(6)];
        for (int i = 0; i < a.length; i++) {
            a[i] = r.nextLong();
        }
        return a;
    }

    private static float[] randomFloatArray(Random r) {
        float[] a = new float[r.nextInt(6)];
        for (int i = 0; i < a.length; i++) {
            a[i] = randomFloat(r);
        }
        return a;
    }

    private static double[] randomDoubleArray(Random r) {
        double[] a = new double[r.nextInt(6)];
        for (int i = 0; i < a.length; i++) {
            a[i] = randomDouble(r);
        }
        return a;
    }

    private static String[] randomStringArray(Random r) {
        String[] a = new String[r.nextInt(6)];
        for (int i = 0; i < a.length; i++) {
            // include occasional nulls; String[] preserves them
            a[i] = r.nextInt(6) == 0 ? null : randomString(r);
        }
        return a;
    }
}
