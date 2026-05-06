package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@Tag("integration")
class ProtoGemCouchSerializationIntegrationTest {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_SHIM_PORT = 40405;
    private static final int DEFAULT_HEALTH_PORT = 8081;
    private static final String DEFAULT_REGION = "helloWorld";
    private static final String DEFAULT_SHIM_CONTAINER = "protogemcouch-shim";

    private ClientCache cache;
    private Region<String, Object> region;

    @BeforeEach
    void setUp() {
        String host = envOrDefault("IT_SHIM_HOST", DEFAULT_HOST);
        int shimPort = intEnv("IT_SHIM_PORT", DEFAULT_SHIM_PORT);
        int healthPort = intEnv("IT_HEALTH_PORT", DEFAULT_HEALTH_PORT);
        String regionName = envOrDefault("IT_REGION", DEFAULT_REGION);

        waitForShimReady(host, healthPort, Duration.ofSeconds(90));

        cache = new ClientCacheFactory()
                .addPoolServer(host, shimPort)
                .setPoolSubscriptionEnabled(false)
                .set("log-level", "warn")
                .create();

        region = cache
                .<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void integerValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-integer-value-" + suffix;
        Integer expected = 12345;

        try {
            region.put(key, expected);
            Object actual = region.get(key);
            assertInstanceOf(Integer.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("INTEGER round-trip");
            throw e;
        }
    }

    @Test
    void integerValueShouldBeOverwrittenByAnotherIntegerValue() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-integer-overwrite-" + suffix;
        Integer before = 100;
        Integer after = 200;

        try {
            region.put(key, before);
            assertEquals(before, region.get(key));
            region.put(key, after);
            Object actual = region.get(key);
            assertInstanceOf(Integer.class, actual);
            assertEquals(after, actual);
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("INTEGER overwrite");
            throw e;
        }
    }

    @Test
    void booleanValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-boolean-value-" + suffix;
        Boolean expected = Boolean.TRUE;

        try {
            region.put(key, expected);
            Object actual = region.get(key);
            assertInstanceOf(Boolean.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("BOOLEAN round-trip");
            throw e;
        }
    }

    @Test
    void longValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-long-value-" + suffix;
        Long expected = 9_876_543_210L;

        try {
            region.put(key, expected);
            Object actual = region.get(key);
            assertInstanceOf(Long.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("LONG round-trip");
            throw e;
        }
    }

    @Test
    void floatValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-float-value-" + suffix;
        Float expected = 7.25f;

        try {
            region.put(key, expected);
            Object actual = region.get(key);
            assertInstanceOf(Float.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("FLOAT round-trip");
            throw e;
        }
    }

    @Test
    void doubleValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-double-value-" + suffix;
        Double expected = 7.25d;

        try {
            region.put(key, expected);
            Object actual = region.get(key);
            assertInstanceOf(Double.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("DOUBLE round-trip");
            throw e;
        }
    }

    @Test
    void putAllWithIntegerValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();
        String key1 = "it-putall-int-1-" + suffix;
        String key2 = "it-putall-int-2-" + suffix;
        String key3 = "it-putall-int-3-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, Integer.valueOf(101));
        entries.put(key2, Integer.valueOf(202));
        entries.put(key3, Integer.valueOf(303));

        try {
            region.putAll(entries);
            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);
            assertInstanceOf(Integer.class, actual1);
            assertInstanceOf(Integer.class, actual2);
            assertInstanceOf(Integer.class, actual3);
            assertEquals(Integer.valueOf(101), actual1);
            assertEquals(Integer.valueOf(202), actual2);
            assertEquals(Integer.valueOf(303), actual3);
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("INTEGER PUT_ALL");
            throw e;
        }
    }

    @Test
    void putAllWithBooleanValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();
        String key1 = "it-putall-bool-1-" + suffix;
        String key2 = "it-putall-bool-2-" + suffix;
        String key3 = "it-putall-bool-3-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, Boolean.TRUE);
        entries.put(key2, Boolean.FALSE);
        entries.put(key3, Boolean.TRUE);

        try {
            region.putAll(entries);
            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);
            assertInstanceOf(Boolean.class, actual1);
            assertInstanceOf(Boolean.class, actual2);
            assertInstanceOf(Boolean.class, actual3);
            assertEquals(Boolean.TRUE, actual1);
            assertEquals(Boolean.FALSE, actual2);
            assertEquals(Boolean.TRUE, actual3);
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("BOOLEAN PUT_ALL");
            throw e;
        }
    }

    @Test
    void putAllWithLongValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();
        String key1 = "it-putall-long-1-" + suffix;
        String key2 = "it-putall-long-2-" + suffix;
        String key3 = "it-putall-long-3-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, Long.valueOf(101L));
        entries.put(key2, Long.valueOf(-202L));
        entries.put(key3, Long.valueOf(9_876_543_210L));

        try {
            region.putAll(entries);
            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);
            assertInstanceOf(Long.class, actual1);
            assertInstanceOf(Long.class, actual2);
            assertInstanceOf(Long.class, actual3);
            assertEquals(Long.valueOf(101L), actual1);
            assertEquals(Long.valueOf(-202L), actual2);
            assertEquals(Long.valueOf(9_876_543_210L), actual3);
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("LONG PUT_ALL");
            throw e;
        }
    }

    @Test
    void putAllWithFloatValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();
        String key1 = "it-putall-float-1-" + suffix;
        String key2 = "it-putall-float-2-" + suffix;
        String key3 = "it-putall-float-3-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, Float.valueOf(7.25f));
        entries.put(key2, Float.valueOf(-7.25f));
        entries.put(key3, Float.valueOf(987_654.25f));

        try {
            region.putAll(entries);
            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);
            assertInstanceOf(Float.class, actual1);
            assertInstanceOf(Float.class, actual2);
            assertInstanceOf(Float.class, actual3);
            assertEquals(Float.valueOf(7.25f), actual1);
            assertEquals(Float.valueOf(-7.25f), actual2);
            assertEquals(Float.valueOf(987_654.25f), actual3);
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("FLOAT PUT_ALL");
            throw e;
        }
    }

    @Test
    void putAllWithDoubleValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();
        String key1 = "it-putall-double-1-" + suffix;
        String key2 = "it-putall-double-2-" + suffix;
        String key3 = "it-putall-double-3-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, Double.valueOf(7.25d));
        entries.put(key2, Double.valueOf(-7.25d));
        entries.put(key3, Double.valueOf(9_876_543.210d));

        try {
            region.putAll(entries);
            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);
            assertInstanceOf(Double.class, actual1);
            assertInstanceOf(Double.class, actual2);
            assertInstanceOf(Double.class, actual3);
            assertEquals(Double.valueOf(7.25d), actual1);
            assertEquals(Double.valueOf(-7.25d), actual2);
            assertEquals(Double.valueOf(9_876_543.210d), actual3);
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("DOUBLE PUT_ALL");
            throw e;
        }
    }

    @Test
    void getAllWithIntegerValuesShouldReturnIntegers() {
        String suffix = UUID.randomUUID().toString();
        String key1 = "it-getall-int-1-" + suffix;
        String key2 = "it-getall-int-2-" + suffix;
        String key3 = "it-getall-int-3-" + suffix;

        try {
            region.put(key1, Integer.valueOf(111));
            region.put(key2, Integer.valueOf(222));
            region.put(key3, Integer.valueOf(333));
            Set<String> keys = orderedSet(key1, key2, key3);
            Map<String, Object> results = region.getAll(keys);
            assertTypedTriple(results, key1, Integer.class, Integer.valueOf(111), key2, Integer.class, Integer.valueOf(222), key3, Integer.class, Integer.valueOf(333));
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("INTEGER GET_ALL");
            throw e;
        }
    }

    @Test
    void getAllWithBooleanValuesShouldReturnBooleans() {
        String suffix = UUID.randomUUID().toString();
        String key1 = "it-getall-bool-1-" + suffix;
        String key2 = "it-getall-bool-2-" + suffix;
        String key3 = "it-getall-bool-3-" + suffix;

        try {
            region.put(key1, Boolean.TRUE);
            region.put(key2, Boolean.FALSE);
            region.put(key3, Boolean.TRUE);
            Set<String> keys = orderedSet(key1, key2, key3);
            Map<String, Object> results = region.getAll(keys);
            assertTypedTriple(results, key1, Boolean.class, Boolean.TRUE, key2, Boolean.class, Boolean.FALSE, key3, Boolean.class, Boolean.TRUE);
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("BOOLEAN GET_ALL");
            throw e;
        }
    }

    @Test
    void getAllWithLongValuesShouldReturnLongs() {
        String suffix = UUID.randomUUID().toString();
        String key1 = "it-getall-long-1-" + suffix;
        String key2 = "it-getall-long-2-" + suffix;
        String key3 = "it-getall-long-3-" + suffix;

        try {
            region.put(key1, Long.valueOf(111L));
            region.put(key2, Long.valueOf(-222L));
            region.put(key3, Long.valueOf(9_876_543_210L));
            Set<String> keys = orderedSet(key1, key2, key3);
            Map<String, Object> results = region.getAll(keys);
            assertTypedTriple(results, key1, Long.class, Long.valueOf(111L), key2, Long.class, Long.valueOf(-222L), key3, Long.class, Long.valueOf(9_876_543_210L));
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("LONG GET_ALL");
            throw e;
        }
    }

    @Test
    void getAllWithFloatValuesShouldReturnFloats() {
        String suffix = UUID.randomUUID().toString();
        String key1 = "it-getall-float-1-" + suffix;
        String key2 = "it-getall-float-2-" + suffix;
        String key3 = "it-getall-float-3-" + suffix;

        try {
            region.put(key1, Float.valueOf(7.25f));
            region.put(key2, Float.valueOf(-7.25f));
            region.put(key3, Float.valueOf(987_654.25f));
            Set<String> keys = orderedSet(key1, key2, key3);
            Map<String, Object> results = region.getAll(keys);
            assertTypedTriple(results, key1, Float.class, Float.valueOf(7.25f), key2, Float.class, Float.valueOf(-7.25f), key3, Float.class, Float.valueOf(987_654.25f));
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("FLOAT GET_ALL");
            throw e;
        }
    }

    @Test
    void getAllWithDoubleValuesShouldReturnDoubles() {
        String suffix = UUID.randomUUID().toString();
        String key1 = "it-getall-double-1-" + suffix;
        String key2 = "it-getall-double-2-" + suffix;
        String key3 = "it-getall-double-3-" + suffix;

        try {
            region.put(key1, Double.valueOf(7.25d));
            region.put(key2, Double.valueOf(-7.25d));
            region.put(key3, Double.valueOf(9_876_543.210d));
            Set<String> keys = orderedSet(key1, key2, key3);
            Map<String, Object> results = region.getAll(keys);
            assertTypedTriple(results, key1, Double.class, Double.valueOf(7.25d), key2, Double.class, Double.valueOf(-7.25d), key3, Double.class, Double.valueOf(9_876_543.210d));
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("DOUBLE GET_ALL");
            throw e;
        }
    }

    @Test
    void mixedStringAndIntegerPutAllAndGetAllShouldPreserveTypes() {
        String suffix = UUID.randomUUID().toString();
        String stringKey1 = "it-mixed-string-1-" + suffix;
        String integerKey1 = "it-mixed-integer-1-" + suffix;
        String stringKey2 = "it-mixed-string-2-" + suffix;
        String integerKey2 = "it-mixed-integer-2-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(stringKey1, "string-value-1-" + suffix);
        entries.put(integerKey1, Integer.valueOf(1001));
        entries.put(stringKey2, "string-value-2-" + suffix);
        entries.put(integerKey2, Integer.valueOf(2002));

        try {
            region.putAll(entries);
            Map<String, Object> results = region.getAll(orderedSet(stringKey1, integerKey1, stringKey2, integerKey2));
            assertInstanceOf(String.class, results.get(stringKey1));
            assertInstanceOf(Integer.class, results.get(integerKey1));
            assertInstanceOf(String.class, results.get(stringKey2));
            assertInstanceOf(Integer.class, results.get(integerKey2));
            assertEquals("string-value-1-" + suffix, results.get(stringKey1));
            assertEquals(Integer.valueOf(1001), results.get(integerKey1));
            assertEquals("string-value-2-" + suffix, results.get(stringKey2));
            assertEquals(Integer.valueOf(2002), results.get(integerKey2));
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("MIXED PUT_ALL/GET_ALL");
            throw e;
        }
    }

    @Test
    void mixedStringIntegerAndBooleanPutAllAndGetAllShouldPreserveTypes() {
        String suffix = UUID.randomUUID().toString();
        String stringKey = "it-mixed3-string-" + suffix;
        String integerKey = "it-mixed3-integer-" + suffix;
        String booleanTrueKey = "it-mixed3-bool-true-" + suffix;
        String booleanFalseKey = "it-mixed3-bool-false-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(stringKey, "string-value-" + suffix);
        entries.put(integerKey, Integer.valueOf(3003));
        entries.put(booleanTrueKey, Boolean.TRUE);
        entries.put(booleanFalseKey, Boolean.FALSE);

        try {
            region.putAll(entries);
            Map<String, Object> results = region.getAll(orderedSet(stringKey, integerKey, booleanTrueKey, booleanFalseKey));
            assertInstanceOf(String.class, results.get(stringKey));
            assertInstanceOf(Integer.class, results.get(integerKey));
            assertInstanceOf(Boolean.class, results.get(booleanTrueKey));
            assertInstanceOf(Boolean.class, results.get(booleanFalseKey));
            assertEquals("string-value-" + suffix, results.get(stringKey));
            assertEquals(Integer.valueOf(3003), results.get(integerKey));
            assertEquals(Boolean.TRUE, results.get(booleanTrueKey));
            assertEquals(Boolean.FALSE, results.get(booleanFalseKey));
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("MIXED STRING/INTEGER/BOOLEAN PUT_ALL/GET_ALL");
            throw e;
        }
    }

    @Test
    void mixedStringIntegerBooleanAndLongPutAllAndGetAllShouldPreserveTypes() {
        String suffix = UUID.randomUUID().toString();
        String stringKey = "it-mixed4-string-" + suffix;
        String integerKey = "it-mixed4-integer-" + suffix;
        String booleanTrueKey = "it-mixed4-bool-true-" + suffix;
        String booleanFalseKey = "it-mixed4-bool-false-" + suffix;
        String longPositiveKey = "it-mixed4-long-positive-" + suffix;
        String longNegativeKey = "it-mixed4-long-negative-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(stringKey, "string-value-" + suffix);
        entries.put(integerKey, Integer.valueOf(4004));
        entries.put(booleanTrueKey, Boolean.TRUE);
        entries.put(booleanFalseKey, Boolean.FALSE);
        entries.put(longPositiveKey, Long.valueOf(9_876_543_210L));
        entries.put(longNegativeKey, Long.valueOf(-9_876_543_210L));

        try {
            region.putAll(entries);
            Map<String, Object> results = region.getAll(orderedSet(stringKey, integerKey, booleanTrueKey, booleanFalseKey, longPositiveKey, longNegativeKey));
            assertInstanceOf(String.class, results.get(stringKey));
            assertInstanceOf(Integer.class, results.get(integerKey));
            assertInstanceOf(Boolean.class, results.get(booleanTrueKey));
            assertInstanceOf(Boolean.class, results.get(booleanFalseKey));
            assertInstanceOf(Long.class, results.get(longPositiveKey));
            assertInstanceOf(Long.class, results.get(longNegativeKey));
            assertEquals("string-value-" + suffix, results.get(stringKey));
            assertEquals(Integer.valueOf(4004), results.get(integerKey));
            assertEquals(Boolean.TRUE, results.get(booleanTrueKey));
            assertEquals(Boolean.FALSE, results.get(booleanFalseKey));
            assertEquals(Long.valueOf(9_876_543_210L), results.get(longPositiveKey));
            assertEquals(Long.valueOf(-9_876_543_210L), results.get(longNegativeKey));
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("MIXED STRING/INTEGER/BOOLEAN/LONG PUT_ALL/GET_ALL");
            throw e;
        }
    }

    @Test
    void mixedStringIntegerBooleanLongFloatAndDoublePutAllAndGetAllShouldPreserveTypes() {
        String suffix = UUID.randomUUID().toString();
        String stringKey = "it-mixed6-string-" + suffix;
        String integerKey = "it-mixed6-integer-" + suffix;
        String booleanTrueKey = "it-mixed6-bool-true-" + suffix;
        String booleanFalseKey = "it-mixed6-bool-false-" + suffix;
        String longPositiveKey = "it-mixed6-long-positive-" + suffix;
        String longNegativeKey = "it-mixed6-long-negative-" + suffix;
        String floatPositiveKey = "it-mixed6-float-positive-" + suffix;
        String floatNegativeKey = "it-mixed6-float-negative-" + suffix;
        String doublePositiveKey = "it-mixed6-double-positive-" + suffix;
        String doubleNegativeKey = "it-mixed6-double-negative-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(stringKey, "string-value-" + suffix);
        entries.put(integerKey, Integer.valueOf(6006));
        entries.put(booleanTrueKey, Boolean.TRUE);
        entries.put(booleanFalseKey, Boolean.FALSE);
        entries.put(longPositiveKey, Long.valueOf(9_876_543_210L));
        entries.put(longNegativeKey, Long.valueOf(-9_876_543_210L));
        entries.put(floatPositiveKey, Float.valueOf(7.25f));
        entries.put(floatNegativeKey, Float.valueOf(-7.25f));
        entries.put(doublePositiveKey, Double.valueOf(7.25d));
        entries.put(doubleNegativeKey, Double.valueOf(-7.25d));

        try {
            region.putAll(entries);
            Map<String, Object> results = region.getAll(orderedSet(
                    stringKey,
                    integerKey,
                    booleanTrueKey,
                    booleanFalseKey,
                    longPositiveKey,
                    longNegativeKey,
                    floatPositiveKey,
                    floatNegativeKey,
                    doublePositiveKey,
                    doubleNegativeKey
            ));

            assertInstanceOf(String.class, results.get(stringKey));
            assertInstanceOf(Integer.class, results.get(integerKey));
            assertInstanceOf(Boolean.class, results.get(booleanTrueKey));
            assertInstanceOf(Boolean.class, results.get(booleanFalseKey));
            assertInstanceOf(Long.class, results.get(longPositiveKey));
            assertInstanceOf(Long.class, results.get(longNegativeKey));
            assertInstanceOf(Float.class, results.get(floatPositiveKey));
            assertInstanceOf(Float.class, results.get(floatNegativeKey));
            assertInstanceOf(Double.class, results.get(doublePositiveKey));
            assertInstanceOf(Double.class, results.get(doubleNegativeKey));

            assertEquals("string-value-" + suffix, results.get(stringKey));
            assertEquals(Integer.valueOf(6006), results.get(integerKey));
            assertEquals(Boolean.TRUE, results.get(booleanTrueKey));
            assertEquals(Boolean.FALSE, results.get(booleanFalseKey));
            assertEquals(Long.valueOf(9_876_543_210L), results.get(longPositiveKey));
            assertEquals(Long.valueOf(-9_876_543_210L), results.get(longNegativeKey));
            assertEquals(Float.valueOf(7.25f), results.get(floatPositiveKey));
            assertEquals(Float.valueOf(-7.25f), results.get(floatNegativeKey));
            assertEquals(Double.valueOf(7.25d), results.get(doublePositiveKey));
            assertEquals(Double.valueOf(-7.25d), results.get(doubleNegativeKey));
        } catch (RuntimeException | AssertionError e) {
            dumpLogsFor("MIXED STRING/INTEGER/BOOLEAN/LONG/FLOAT/DOUBLE PUT_ALL/GET_ALL");
            throw e;
        }
    }

    @SafeVarargs
    private static Set<String> orderedSet(String... keys) {
        Set<String> out = new LinkedHashSet<>();
        for (String key : keys) {
            out.add(key);
        }
        return out;
    }

    private static void assertTypedTriple(
            Map<String, Object> results,
            String key1,
            Class<?> type1,
            Object expected1,
            String key2,
            Class<?> type2,
            Object expected2,
            String key3,
            Class<?> type3,
            Object expected3
    ) {
        Object actual1 = results.get(key1);
        Object actual2 = results.get(key2);
        Object actual3 = results.get(key3);

        assertInstanceOf(type1, actual1);
        assertInstanceOf(type2, actual2);
        assertInstanceOf(type3, actual3);

        assertEquals(expected1, actual1);
        assertEquals(expected2, actual2);
        assertEquals(expected3, actual3);
    }

    private static void dumpLogsFor(String label) {
        System.err.println();
        System.err.println("========== protogemcouch-shim logs after " + label + " failure ==========");
        dumpShimLogs();
        System.err.println("========== end protogemcouch-shim logs ==========");
        System.err.println();
    }

    private static void dumpShimLogs() {
        try {
            String containerName = envOrDefault("IT_SHIM_CONTAINER", DEFAULT_SHIM_CONTAINER);
            System.err.println(dockerLogs(containerName));
        } catch (RuntimeException logError) {
            System.err.println("Failed to read shim logs: " + logError.getMessage());
        }
    }

    private static String dockerLogs(String containerName) {
        try {
            Process process = new ProcessBuilder(
                    "docker",
                    "logs",
                    "--tail",
                    "500",
                    containerName
            )
                    .redirectErrorStream(true)
                    .start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Timed out while reading docker logs for " + containerName);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "docker logs failed for container [" + containerName + "] with exit code "
                                + exitCode + ". Output:\n" + output
                );
            }

            return output;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to execute docker logs. Ensure Docker is installed and available on PATH.",
                    e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading docker logs", e);
        }
    }

    private static void waitForShimReady(String host, int healthPort, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String url = "http://" + host + ":" + healthPort + "/ready";

        Exception lastFailure = null;

        while (System.nanoTime() < deadline) {
            try {
                int status = httpStatus(url);
                if (status >= 200 && status < 300) {
                    return;
                }
            } catch (Exception e) {
                lastFailure = e;
            }

            sleep(Duration.ofMillis(500));
        }

        throw new IllegalStateException(
                "Shim did not become ready before timeout. URL=" + url,
                lastFailure
        );
    }

    private static int httpStatus(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        connection.setRequestMethod("GET");

        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for shim readiness", e);
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
