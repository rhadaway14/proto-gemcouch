package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.pdx.PdxInstance;
import org.apache.geode.pdx.PdxInstanceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.Instant;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    private void recreateClientCacheWithPdxReadSerialized() {
        if (cache != null) {
            cache.close();
        }

        String host = envOrDefault("IT_SHIM_HOST", DEFAULT_HOST);
        int shimPort = intEnv("IT_SHIM_PORT", DEFAULT_SHIM_PORT);
        String regionName = envOrDefault("IT_REGION", DEFAULT_REGION);

        cache = new ClientCacheFactory()
                .addPoolServer(host, shimPort)
                .setPoolSubscriptionEnabled(false)
                .setPdxReadSerialized(true)
                .set("log-level", "warn")
                .create();

        region = cache
                .<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    private PdxInstanceFactory pdxFactory(String className) {
        return cache.createPdxInstanceFactory(className);
    }

    @Test
    void simplePdxInstanceShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-simple-value-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            PdxInstance expected = pdxFactory("com.example.integration.SimplePdx")
                    .writeString("id", "customer-1")
                    .writeString("name", "Rob")
                    .writeInt("age", 42)
                    .writeBoolean("active", true)
                    .create();

            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(PdxInstance.class, actual);
            PdxInstance actualPdx = (PdxInstance) actual;

            assertEquals("customer-1", actualPdx.getField("id"));
            assertEquals("Rob", actualPdx.getField("name"));
            assertEquals(42, actualPdx.getField("age"));
            assertEquals(Boolean.TRUE, actualPdx.getField("active"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after SIMPLE PDX round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void pdxInstanceWithPrimitiveAndStringArrayFieldsShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-arrays-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            PdxInstance expected = pdxFactory("com.example.integration.PdxWithArrays")
                    .writeString("id", "array-doc-1")
                    .writeIntArray("scores", new int[] {
                            1,
                            42,
                            -7,
                            Integer.MAX_VALUE,
                            Integer.MIN_VALUE
                    })
                    .writeLongArray("longValues", new long[] {
                            1L,
                            42L,
                            -7L,
                            9_876_543_210L
                    })
                    .writeBooleanArray("flags", new boolean[] {
                            true,
                            false,
                            true
                    })
                    .writeDoubleArray("measurements", new double[] {
                            1.0d,
                            7.25d,
                            -7.25d
                    })
                    .writeStringArray("tags", new String[] {
                            "one",
                            null,
                            "three"
                    })
                    .create();

            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(PdxInstance.class, actual);

            PdxInstance actualPdx = (PdxInstance) actual;

            assertEquals("array-doc-1", actualPdx.getField("id"));
            assertArrayEquals(
                    new int[] {1, 42, -7, Integer.MAX_VALUE, Integer.MIN_VALUE},
                    (int[]) actualPdx.getField("scores")
            );
            assertArrayEquals(
                    new long[] {1L, 42L, -7L, 9_876_543_210L},
                    (long[]) actualPdx.getField("longValues")
            );
            assertArrayEquals(
                    new boolean[] {true, false, true},
                    (boolean[]) actualPdx.getField("flags")
            );
            assertArrayEquals(
                    new double[] {1.0d, 7.25d, -7.25d},
                    (double[]) actualPdx.getField("measurements")
            );
            assertArrayEquals(
                    new String[] {"one", null, "three"},
                    (String[]) actualPdx.getField("tags")
            );
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX array fields round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void pdxInstanceWithObjectArrayFieldShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-object-array-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            Object[] expectedItems = new Object[] {
                    "one",
                    Integer.valueOf(42),
                    Boolean.TRUE,
                    "three"
            };

            PdxInstance expected = pdxFactory("com.example.integration.PdxWithObjectArray")
                    .writeString("id", "object-array-doc-1")
                    .writeObjectArray("items", expectedItems)
                    .create();

            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(PdxInstance.class, actual);

            PdxInstance actualPdx = (PdxInstance) actual;

            assertEquals("object-array-doc-1", actualPdx.getField("id"));

            Object actualItemsRaw = actualPdx.getField("items");
            assertInstanceOf(Object[].class, actualItemsRaw);

            Object[] actualItems = (Object[]) actualItemsRaw;

            assertArrayEquals(expectedItems, actualItems);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX Object[] field round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void pdxInstanceWithArrayListObjectFieldShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-array-list-object-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            ArrayList<Object> expectedItems = new ArrayList<>();
            expectedItems.add("one");
            expectedItems.add(Integer.valueOf(42));
            expectedItems.add(Boolean.TRUE);
            expectedItems.add("three");

            PdxInstance expected = pdxFactory("com.example.integration.PdxWithArrayListObject")
                    .writeString("id", "array-list-object-doc-1")
                    .writeObject("items", expectedItems)
                    .create();

            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(PdxInstance.class, actual);

            PdxInstance actualPdx = (PdxInstance) actual;

            assertEquals("array-list-object-doc-1", actualPdx.getField("id"));

            Object actualItemsRaw = actualPdx.getField("items");
            assertInstanceOf(ArrayList.class, actualItemsRaw);

            ArrayList<?> actualItems = (ArrayList<?>) actualItemsRaw;

            assertEquals(expectedItems.size(), actualItems.size());
            assertEquals("one", actualItems.get(0));
            assertEquals(Integer.valueOf(42), actualItems.get(1));
            assertEquals(Boolean.TRUE, actualItems.get(2));
            assertEquals("three", actualItems.get(3));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX ArrayList<Object> field round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void pdxInstanceWithNestedMapFieldShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-nested-map-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            LinkedHashMap<String, Object> expectedAttributes = new LinkedHashMap<>();
            expectedAttributes.put("tier", "gold");
            expectedAttributes.put("score", Integer.valueOf(9001));
            expectedAttributes.put("active", Boolean.TRUE);
            expectedAttributes.put("label", "priority");

            PdxInstance expected = pdxFactory("com.example.integration.PdxWithNestedMap")
                    .writeString("id", "nested-map-doc-1")
                    .writeObject("attributes", expectedAttributes)
                    .create();

            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(PdxInstance.class, actual);

            PdxInstance actualPdx = (PdxInstance) actual;

            assertEquals("nested-map-doc-1", actualPdx.getField("id"));

            Object actualAttributesRaw = actualPdx.getField("attributes");
            assertInstanceOf(Map.class, actualAttributesRaw);

            Map<?, ?> actualAttributes = (Map<?, ?>) actualAttributesRaw;

            assertEquals("gold", actualAttributes.get("tier"));
            assertEquals(Integer.valueOf(9001), actualAttributes.get("score"));
            assertEquals(Boolean.TRUE, actualAttributes.get("active"));
            assertEquals("priority", actualAttributes.get("label"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX nested Map<String,Object> field round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void pdxInstanceWithUuidFieldShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-uuid-field-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            UUID expectedUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

            PdxInstance expected = pdxFactory("com.example.integration.PdxWithUuidField")
                    .writeString("id", "uuid-doc-1")
                    .writeObject("uuid", expectedUuid)
                    .create();

            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(PdxInstance.class, actual);

            PdxInstance actualPdx = (PdxInstance) actual;

            assertEquals("uuid-doc-1", actualPdx.getField("id"));
            assertEquals(expectedUuid, actualPdx.getField("uuid"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX UUID field round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void pdxInstanceWithBigIntegerFieldShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-big-integer-field-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            BigInteger expectedBigInteger = new BigInteger("123456789012345678901234567890");

            PdxInstance expected = pdxFactory("com.example.integration.PdxWithBigIntegerField")
                    .writeString("id", "big-integer-doc-1")
                    .writeObject("bigInteger", expectedBigInteger)
                    .create();

            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(PdxInstance.class, actual);

            PdxInstance actualPdx = (PdxInstance) actual;

            assertEquals("big-integer-doc-1", actualPdx.getField("id"));
            assertEquals(expectedBigInteger, actualPdx.getField("bigInteger"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX BigInteger field round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void pdxInstanceWithBigDecimalFieldShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-big-decimal-field-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            BigDecimal expectedBigDecimal = new BigDecimal("1234567890.123456789");

            PdxInstance expected = pdxFactory("com.example.integration.PdxWithBigDecimalField")
                    .writeString("id", "big-decimal-doc-1")
                    .writeObject("bigDecimal", expectedBigDecimal)
                    .create();

            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(PdxInstance.class, actual);

            PdxInstance actualPdx = (PdxInstance) actual;

            assertEquals("big-decimal-doc-1", actualPdx.getField("id"));
            assertEquals(expectedBigDecimal, actualPdx.getField("bigDecimal"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX BigDecimal field round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void pdxInstanceWithInstantFieldShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-instant-field-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            Instant expectedInstant = Instant.parse("2026-05-13T20:37:37Z");

            PdxInstance expected = pdxFactory("com.example.integration.PdxWithInstantField")
                    .writeString("id", "instant-doc-1")
                    .writeObject("instant", expectedInstant)
                    .create();

            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(PdxInstance.class, actual);

            PdxInstance actualPdx = (PdxInstance) actual;

            assertEquals("instant-doc-1", actualPdx.getField("id"));
            assertEquals(expectedInstant, actualPdx.getField("instant"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX Instant field round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void pdxInstanceWithLocalDateFieldShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-local-date-field-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            LocalDate expectedLocalDate = LocalDate.of(2026, 5, 13);

            PdxInstance expected = pdxFactory("com.example.integration.PdxWithLocalDateField")
                    .writeString("id", "local-date-doc-1")
                    .writeObject("localDate", expectedLocalDate)
                    .create();

            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(PdxInstance.class, actual);

            PdxInstance actualPdx = (PdxInstance) actual;

            assertEquals("local-date-doc-1", actualPdx.getField("id"));
            assertEquals(expectedLocalDate, actualPdx.getField("localDate"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX LocalDate field round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void pdxInstanceWithLocalDateTimeFieldShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-local-date-time-field-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            LocalDateTime expectedLocalDateTime = LocalDateTime.of(2026, 5, 13, 20, 37, 37);

            PdxInstance expected = pdxFactory("com.example.integration.PdxWithLocalDateTimeField")
                    .writeString("id", "local-date-time-doc-1")
                    .writeObject("localDateTime", expectedLocalDateTime)
                    .create();

            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(PdxInstance.class, actual);

            PdxInstance actualPdx = (PdxInstance) actual;

            assertEquals("local-date-time-doc-1", actualPdx.getField("id"));
            assertEquals(expectedLocalDateTime, actualPdx.getField("localDateTime"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX LocalDateTime field round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void pdxInstanceWithEnumFieldShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-enum-field-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            DemoStatus expectedStatus = DemoStatus.ACTIVE;

            PdxInstance expected = pdxFactory("com.example.integration.PdxWithEnumField")
                    .writeString("id", "enum-doc-1")
                    .writeObject("status", expectedStatus)
                    .create();

            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(PdxInstance.class, actual);

            PdxInstance actualPdx = (PdxInstance) actual;

            assertEquals("enum-doc-1", actualPdx.getField("id"));

            Object actualStatus = actualPdx.getField("status");

            assertEquals(
                    "org.apache.geode.pdx.internal.EnumInfo$PdxInstanceEnumInfo",
                    actualStatus.getClass().getName()
            );
            assertEquals("ACTIVE", String.valueOf(actualStatus));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX enum field round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void putAllAndGetAllWithPdxInstancesShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-pdx-putall-simple-" + suffix;
        String key2 = "it-pdx-putall-array-" + suffix;
        String key3 = "it-pdx-putall-map-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            PdxInstance simple = pdxFactory("com.example.integration.PutAllSimplePdx")
                    .writeString("id", "simple-pdx-1")
                    .writeString("name", "Rob")
                    .writeInt("age", 42)
                    .writeBoolean("active", true)
                    .create();

            PdxInstance withArray = pdxFactory("com.example.integration.PutAllPdxWithArray")
                    .writeString("id", "array-pdx-1")
                    .writeStringArray("tags", new String[] {"one", "two", "three"})
                    .writeIntArray("scores", new int[] {1, 2, 3})
                    .create();

            LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("tier", "gold");
            attributes.put("score", Integer.valueOf(9001));
            attributes.put("active", Boolean.TRUE);

            PdxInstance withMap = pdxFactory("com.example.integration.PutAllPdxWithMap")
                    .writeString("id", "map-pdx-1")
                    .writeObject("attributes", attributes)
                    .create();

            Map<String, Object> entries = new LinkedHashMap<>();
            entries.put(key1, simple);
            entries.put(key2, withArray);
            entries.put(key3, withMap);

            region.putAll(entries);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            assertInstanceOf(PdxInstance.class, results.get(key1));
            assertInstanceOf(PdxInstance.class, results.get(key2));
            assertInstanceOf(PdxInstance.class, results.get(key3));

            PdxInstance actualSimple = (PdxInstance) results.get(key1);
            assertEquals("simple-pdx-1", actualSimple.getField("id"));
            assertEquals("Rob", actualSimple.getField("name"));
            assertEquals(42, actualSimple.getField("age"));
            assertEquals(Boolean.TRUE, actualSimple.getField("active"));

            PdxInstance actualWithArray = (PdxInstance) results.get(key2);
            assertEquals("array-pdx-1", actualWithArray.getField("id"));
            assertArrayEquals(
                    new String[] {"one", "two", "three"},
                    (String[]) actualWithArray.getField("tags")
            );
            assertArrayEquals(
                    new int[] {1, 2, 3},
                    (int[]) actualWithArray.getField("scores")
            );

            PdxInstance actualWithMap = (PdxInstance) results.get(key3);
            assertEquals("map-pdx-1", actualWithMap.getField("id"));

            Object actualAttributesRaw = actualWithMap.getField("attributes");
            assertInstanceOf(Map.class, actualAttributesRaw);

            Map<?, ?> actualAttributes = (Map<?, ?>) actualAttributesRaw;
            assertEquals("gold", actualAttributes.get("tier"));
            assertEquals(Integer.valueOf(9001), actualAttributes.get("score"));
            assertEquals(Boolean.TRUE, actualAttributes.get("active"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX PUT_ALL/GET_ALL round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void mixedPutAllAndGetAllWithPrimitiveAndPdxValuesShouldPreserveTypes() {
        String suffix = UUID.randomUUID().toString();

        String stringKey = "it-mixed-pdx-string-" + suffix;
        String integerKey = "it-mixed-pdx-integer-" + suffix;
        String booleanKey = "it-mixed-pdx-boolean-" + suffix;
        String simplePdxKey = "it-mixed-pdx-simple-" + suffix;
        String arrayPdxKey = "it-mixed-pdx-array-" + suffix;
        String mapPdxKey = "it-mixed-pdx-map-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            PdxInstance simplePdx = pdxFactory("com.example.integration.MixedBatchSimplePdx")
                    .writeString("id", "mixed-simple-pdx-1")
                    .writeString("name", "Rob")
                    .writeInt("age", 42)
                    .writeBoolean("active", true)
                    .create();

            PdxInstance arrayPdx = pdxFactory("com.example.integration.MixedBatchPdxWithArray")
                    .writeString("id", "mixed-array-pdx-1")
                    .writeStringArray("tags", new String[] {"alpha", "beta", "gamma"})
                    .writeIntArray("scores", new int[] {10, 20, 30})
                    .create();

            LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("tier", "platinum");
            attributes.put("score", Integer.valueOf(12345));
            attributes.put("active", Boolean.TRUE);

            PdxInstance mapPdx = pdxFactory("com.example.integration.MixedBatchPdxWithMap")
                    .writeString("id", "mixed-map-pdx-1")
                    .writeObject("attributes", attributes)
                    .create();

            Map<String, Object> entries = new LinkedHashMap<>();
            entries.put(stringKey, "string-value-" + suffix);
            entries.put(integerKey, Integer.valueOf(4242));
            entries.put(booleanKey, Boolean.TRUE);
            entries.put(simplePdxKey, simplePdx);
            entries.put(arrayPdxKey, arrayPdx);
            entries.put(mapPdxKey, mapPdx);

            region.putAll(entries);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(stringKey);
            keys.add(integerKey);
            keys.add(booleanKey);
            keys.add(simplePdxKey);
            keys.add(arrayPdxKey);
            keys.add(mapPdxKey);

            Map<String, Object> results = region.getAll(keys);

            assertInstanceOf(String.class, results.get(stringKey));
            assertInstanceOf(Integer.class, results.get(integerKey));
            assertInstanceOf(Boolean.class, results.get(booleanKey));
            assertInstanceOf(PdxInstance.class, results.get(simplePdxKey));
            assertInstanceOf(PdxInstance.class, results.get(arrayPdxKey));
            assertInstanceOf(PdxInstance.class, results.get(mapPdxKey));

            assertEquals("string-value-" + suffix, results.get(stringKey));
            assertEquals(Integer.valueOf(4242), results.get(integerKey));
            assertEquals(Boolean.TRUE, results.get(booleanKey));

            PdxInstance actualSimplePdx = (PdxInstance) results.get(simplePdxKey);
            assertEquals("mixed-simple-pdx-1", actualSimplePdx.getField("id"));
            assertEquals("Rob", actualSimplePdx.getField("name"));
            assertEquals(42, actualSimplePdx.getField("age"));
            assertEquals(Boolean.TRUE, actualSimplePdx.getField("active"));

            PdxInstance actualArrayPdx = (PdxInstance) results.get(arrayPdxKey);
            assertEquals("mixed-array-pdx-1", actualArrayPdx.getField("id"));
            assertArrayEquals(
                    new String[] {"alpha", "beta", "gamma"},
                    (String[]) actualArrayPdx.getField("tags")
            );
            assertArrayEquals(
                    new int[] {10, 20, 30},
                    (int[]) actualArrayPdx.getField("scores")
            );

            PdxInstance actualMapPdx = (PdxInstance) results.get(mapPdxKey);
            assertEquals("mixed-map-pdx-1", actualMapPdx.getField("id"));

            Object actualAttributesRaw = actualMapPdx.getField("attributes");
            assertInstanceOf(Map.class, actualAttributesRaw);

            Map<?, ?> actualAttributes = (Map<?, ?>) actualAttributesRaw;
            assertEquals("platinum", actualAttributes.get("tier"));
            assertEquals(Integer.valueOf(12345), actualAttributes.get("score"));
            assertEquals(Boolean.TRUE, actualAttributes.get("active"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after MIXED primitive/PDX PUT_ALL/GET_ALL round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void removeWithPdxInstanceShouldDeleteValueFromShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-remove-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            PdxInstance expected = pdxFactory("com.example.integration.RemoveSimplePdx")
                    .writeString("id", "remove-pdx-1")
                    .writeString("name", "Rob")
                    .writeInt("age", 42)
                    .writeBoolean("active", true)
                    .create();

            region.put(key, expected);

            Object beforeRemove = region.get(key);

            assertInstanceOf(PdxInstance.class, beforeRemove);

            PdxInstance beforeRemovePdx = (PdxInstance) beforeRemove;
            assertEquals("remove-pdx-1", beforeRemovePdx.getField("id"));
            assertEquals("Rob", beforeRemovePdx.getField("name"));
            assertEquals(42, beforeRemovePdx.getField("age"));
            assertEquals(Boolean.TRUE, beforeRemovePdx.getField("active"));

            Object removed = region.remove(key);

            // The shim currently acknowledges remove but does not return the removed value.
            // This validates delete semantics rather than old-value return semantics.
            assertEquals(null, removed);

            Object afterRemove = region.get(key);

            assertEquals(null, afterRemove);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX REMOVE round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void overwriteWithPdxInstanceShouldReplaceExistingValueInShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-overwrite-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            PdxInstance version1 = pdxFactory("com.example.integration.OverwriteSimplePdx")
                    .writeString("id", "overwrite-pdx-1")
                    .writeString("name", "Rob")
                    .writeInt("age", 42)
                    .writeBoolean("active", true)
                    .create();

            PdxInstance version2 = pdxFactory("com.example.integration.OverwriteSimplePdx")
                    .writeString("id", "overwrite-pdx-1")
                    .writeString("name", "Robert")
                    .writeInt("age", 43)
                    .writeBoolean("active", false)
                    .create();

            region.put(key, version1);

            Object actualVersion1 = region.get(key);

            assertInstanceOf(PdxInstance.class, actualVersion1);

            PdxInstance actualVersion1Pdx = (PdxInstance) actualVersion1;
            assertEquals("overwrite-pdx-1", actualVersion1Pdx.getField("id"));
            assertEquals("Rob", actualVersion1Pdx.getField("name"));
            assertEquals(42, actualVersion1Pdx.getField("age"));
            assertEquals(Boolean.TRUE, actualVersion1Pdx.getField("active"));

            region.put(key, version2);

            Object actualVersion2 = region.get(key);

            assertInstanceOf(PdxInstance.class, actualVersion2);

            PdxInstance actualVersion2Pdx = (PdxInstance) actualVersion2;
            assertEquals("overwrite-pdx-1", actualVersion2Pdx.getField("id"));
            assertEquals("Robert", actualVersion2Pdx.getField("name"));
            assertEquals(43, actualVersion2Pdx.getField("age"));
            assertEquals(Boolean.FALSE, actualVersion2Pdx.getField("active"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX overwrite round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void missingPdxKeysShouldReturnNullAndNotBreakGetAllResults() {
        String suffix = UUID.randomUUID().toString();

        String existingKey = "it-pdx-missing-existing-" + suffix;
        String missingKey = "it-pdx-missing-absent-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            PdxInstance expected = pdxFactory("com.example.integration.MissingKeySimplePdx")
                    .writeString("id", "missing-key-pdx-1")
                    .writeString("name", "Rob")
                    .writeInt("age", 42)
                    .writeBoolean("active", true)
                    .create();

            Object missingBeforePut = region.get(missingKey);

            assertEquals(null, missingBeforePut);

            region.put(existingKey, expected);

            Object existingActual = region.get(existingKey);

            assertInstanceOf(PdxInstance.class, existingActual);

            PdxInstance existingActualPdx = (PdxInstance) existingActual;
            assertEquals("missing-key-pdx-1", existingActualPdx.getField("id"));
            assertEquals("Rob", existingActualPdx.getField("name"));
            assertEquals(42, existingActualPdx.getField("age"));
            assertEquals(Boolean.TRUE, existingActualPdx.getField("active"));

            Set<String> keys = new LinkedHashSet<>();
            keys.add(existingKey);
            keys.add(missingKey);

            Map<String, Object> results = region.getAll(keys);

            assertInstanceOf(PdxInstance.class, results.get(existingKey));

            PdxInstance getAllExistingPdx = (PdxInstance) results.get(existingKey);
            assertEquals("missing-key-pdx-1", getAllExistingPdx.getField("id"));
            assertEquals("Rob", getAllExistingPdx.getField("name"));
            assertEquals(42, getAllExistingPdx.getField("age"));
            assertEquals(Boolean.TRUE, getAllExistingPdx.getField("active"));

            assertEquals(null, results.get(missingKey));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX missing-key GET/GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void containsKeyAndContainsValueForKeyShouldWorkWithPdxValues() {
        String suffix = UUID.randomUUID().toString();

        String existingKey = "it-pdx-contains-existing-" + suffix;
        String missingKey = "it-pdx-contains-missing-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            PdxInstance expected = pdxFactory("com.example.integration.ContainsSimplePdx")
                    .writeString("id", "contains-pdx-1")
                    .writeString("name", "Rob")
                    .writeInt("age", 42)
                    .writeBoolean("active", true)
                    .create();

            assertEquals(false, region.containsKeyOnServer(existingKey));
            assertEquals(false, region.containsKeyOnServer(missingKey));

            region.put(existingKey, expected);

            assertEquals(true, region.containsKeyOnServer(existingKey));

            assertEquals(false, region.containsKeyOnServer(missingKey));

            Object actual = region.get(existingKey);

            assertInstanceOf(PdxInstance.class, actual);

            PdxInstance actualPdx = (PdxInstance) actual;
            assertEquals("contains-pdx-1", actualPdx.getField("id"));
            assertEquals("Rob", actualPdx.getField("name"));
            assertEquals(42, actualPdx.getField("age"));
            assertEquals(Boolean.TRUE, actualPdx.getField("active"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX containsKeyOnServer failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void keySetOnServerShouldIncludePdxBackedKeys() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-pdx-keyset-1-" + suffix;
        String key2 = "it-pdx-keyset-2-" + suffix;

        try {
            recreateClientCacheWithPdxReadSerialized();

            PdxInstance value1 = pdxFactory("com.example.integration.KeySetSimplePdx")
                    .writeString("id", "keyset-pdx-1")
                    .writeString("name", "Rob")
                    .writeInt("age", 42)
                    .writeBoolean("active", true)
                    .create();

            PdxInstance value2 = pdxFactory("com.example.integration.KeySetSimplePdx")
                    .writeString("id", "keyset-pdx-2")
                    .writeString("name", "Robert")
                    .writeInt("age", 43)
                    .writeBoolean("active", false)
                    .create();

            region.put(key1, value1);
            region.put(key2, value2);

            Set<String> keys = region.keySetOnServer();

            assertEquals(
                    true,
                    keys.contains(key1),
                    "Expected keySetOnServer to contain key1=" + key1 + ", but actual keys were: " + keys
            );
            assertEquals(
                    true,
                    keys.contains(key2),
                    "Expected keySetOnServer to contain key2=" + key2 + ", but actual keys were: " + keys
            );

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);

            assertInstanceOf(PdxInstance.class, actual1);
            assertInstanceOf(PdxInstance.class, actual2);

            PdxInstance actualPdx1 = (PdxInstance) actual1;
            PdxInstance actualPdx2 = (PdxInstance) actual2;

            assertEquals("keyset-pdx-1", actualPdx1.getField("id"));
            assertEquals("Rob", actualPdx1.getField("name"));
            assertEquals(42, actualPdx1.getField("age"));
            assertEquals(Boolean.TRUE, actualPdx1.getField("active"));

            assertEquals("keyset-pdx-2", actualPdx2.getField("id"));
            assertEquals("Robert", actualPdx2.getField("name"));
            assertEquals(43, actualPdx2.getField("age"));
            assertEquals(Boolean.FALSE, actualPdx2.getField("active"));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PDX keySetOnServer failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void keySetOnServerShouldHandleMoreThan127Keys() {
        String suffix = UUID.randomUUID().toString();

        try {
            int keyCount = 150;

            for (int i = 0; i < keyCount; i++) {
                String key = "it-keyset-many-" + suffix + "-" + i;
                region.put(key, "value-" + i);
            }

            Set<String> keys = region.keySetOnServer();

            for (int i = 0; i < keyCount; i++) {
                String expectedKey = "it-keyset-many-" + suffix + "-" + i;

                assertEquals(
                        true,
                        keys.contains(expectedKey),
                        "Expected keySetOnServer to contain key=" + expectedKey
                                + ", but actual keys were: " + keys
                );
            }
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after keySetOnServer >127 keys failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }


    @Test
    void getAllShouldHandleMoreThan127Keys() {
        String suffix = UUID.randomUUID().toString();

        try {
            int keyCount = 150;

            Set<String> requestedKeys = new LinkedHashSet<>();

            for (int i = 0; i < keyCount; i++) {
                String key = "it-getall-many-" + suffix + "-" + i;
                String value = "value-" + i;

                region.put(key, value);
                requestedKeys.add(key);
            }

            Map<String, Object> results = region.getAll(requestedKeys);

            assertEquals(keyCount, results.size());

            for (int i = 0; i < keyCount; i++) {
                String expectedKey = "it-getall-many-" + suffix + "-" + i;
                String expectedValue = "value-" + i;

                assertEquals(
                        expectedValue,
                        results.get(expectedKey),
                        "Expected getAll result for key=" + expectedKey
                );
            }
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after getAll >127 keys failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllShouldHandleMoreThan127Entries() {
        String suffix = UUID.randomUUID().toString();

        try {
            int entryCount = 150;

            Map<String, Object> entries = new LinkedHashMap<>();
            Set<String> requestedKeys = new LinkedHashSet<>();

            for (int i = 0; i < entryCount; i++) {
                String key = "it-putall-many-" + suffix + "-" + i;
                String value = "value-" + i;

                entries.put(key, value);
                requestedKeys.add(key);
            }

            region.putAll(entries);

            Map<String, Object> results = region.getAll(requestedKeys);

            assertEquals(entryCount, results.size());

            for (int i = 0; i < entryCount; i++) {
                String expectedKey = "it-putall-many-" + suffix + "-" + i;
                String expectedValue = "value-" + i;

                assertEquals(
                        expectedValue,
                        results.get(expectedKey),
                        "Expected putAll/getAll result for key=" + expectedKey
                );
            }
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after putAll >127 entries failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }


    @Test
    void keySetOnServerShouldHandleMoreThan252Keys() {
        String suffix = UUID.randomUUID().toString();

        try {
            int keyCount = 253;

            for (int i = 0; i < keyCount; i++) {
                String key = "it-keyset-253-" + suffix + "-" + i;
                region.put(key, "value-" + i);
            }

            Set<String> keys = region.keySetOnServer();

            for (int i = 0; i < keyCount; i++) {
                String expectedKey = "it-keyset-253-" + suffix + "-" + i;

                assertEquals(
                        true,
                        keys.contains(expectedKey),
                        "Expected keySetOnServer to contain key=" + expectedKey
                                + ", but actual keys were: " + keys
                );
            }
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after keySetOnServer >252 keys failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllShouldHandleMoreThan252Keys() {
        String suffix = UUID.randomUUID().toString();

        try {
            int keyCount = 253;

            Set<String> requestedKeys = new LinkedHashSet<>();

            for (int i = 0; i < keyCount; i++) {
                String key = "it-getall-253-" + suffix + "-" + i;
                String value = "value-" + i;

                region.put(key, value);
                requestedKeys.add(key);
            }

            Map<String, Object> results = region.getAll(requestedKeys);

            assertEquals(keyCount, results.size());

            for (int i = 0; i < keyCount; i++) {
                String expectedKey = "it-getall-253-" + suffix + "-" + i;
                String expectedValue = "value-" + i;

                assertEquals(
                        expectedValue,
                        results.get(expectedKey),
                        "Expected getAll result for key=" + expectedKey
                );
            }
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after getAll >252 keys failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllShouldHandleMoreThan252Entries() {
        String suffix = UUID.randomUUID().toString();

        try {
            int entryCount = 253;

            Map<String, Object> entries = new LinkedHashMap<>();
            Set<String> requestedKeys = new LinkedHashSet<>();

            for (int i = 0; i < entryCount; i++) {
                String key = "it-putall-253-" + suffix + "-" + i;
                String value = "value-" + i;

                entries.put(key, value);
                requestedKeys.add(key);
            }

            region.putAll(entries);

            Map<String, Object> results = region.getAll(requestedKeys);

            assertEquals(entryCount, results.size());

            for (int i = 0; i < entryCount; i++) {
                String expectedKey = "it-putall-253-" + suffix + "-" + i;
                String expectedValue = "value-" + i;

                assertEquals(
                        expectedValue,
                        results.get(expectedKey),
                        "Expected putAll/getAll result for key=" + expectedKey
                );
            }
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after putAll >252 entries failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
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
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after INTEGER round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after INTEGER overwrite failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BOOLEAN round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void characterValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-character-value-" + suffix;

        Character expected = Character.valueOf('A');

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Character.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after CHARACTER round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void byteValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-byte-value-" + suffix;

        Byte expected = Byte.valueOf((byte) 7);

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Byte.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BYTE round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void shortValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-short-value-" + suffix;

        Short expected = Short.valueOf((short) 7);

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Short.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after SHORT round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after LONG round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after FLOAT round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after DOUBLE round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after INTEGER PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BOOLEAN PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithCharacterValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-character-1-" + suffix;
        String key2 = "it-putall-character-2-" + suffix;
        String key3 = "it-putall-character-3-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, Character.valueOf('A'));
        entries.put(key2, Character.valueOf('Z'));
        entries.put(key3, Character.valueOf('0'));

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(Character.class, actual1);
            assertInstanceOf(Character.class, actual2);
            assertInstanceOf(Character.class, actual3);

            assertEquals(Character.valueOf('A'), actual1);
            assertEquals(Character.valueOf('Z'), actual2);
            assertEquals(Character.valueOf('0'), actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after CHARACTER PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void putAllWithByteValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-byte-1-" + suffix;
        String key2 = "it-putall-byte-2-" + suffix;
        String key3 = "it-putall-byte-3-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, Byte.valueOf((byte) 7));
        entries.put(key2, Byte.valueOf((byte) -7));
        entries.put(key3, Byte.valueOf(Byte.MAX_VALUE));

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(Byte.class, actual1);
            assertInstanceOf(Byte.class, actual2);
            assertInstanceOf(Byte.class, actual3);

            assertEquals(Byte.valueOf((byte) 7), actual1);
            assertEquals(Byte.valueOf((byte) -7), actual2);
            assertEquals(Byte.valueOf(Byte.MAX_VALUE), actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BYTE PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithShortValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-short-1-" + suffix;
        String key2 = "it-putall-short-2-" + suffix;
        String key3 = "it-putall-short-3-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, Short.valueOf((short) 7));
        entries.put(key2, Short.valueOf((short) -7));
        entries.put(key3, Short.valueOf(Short.MAX_VALUE));

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(Short.class, actual1);
            assertInstanceOf(Short.class, actual2);
            assertInstanceOf(Short.class, actual3);

            assertEquals(Short.valueOf((short) 7), actual1);
            assertEquals(Short.valueOf((short) -7), actual2);
            assertEquals(Short.valueOf(Short.MAX_VALUE), actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after SHORT PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after LONG PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after FLOAT PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after DOUBLE PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(Integer.class, actual1);
            assertInstanceOf(Integer.class, actual2);
            assertInstanceOf(Integer.class, actual3);

            assertEquals(Integer.valueOf(111), actual1);
            assertEquals(Integer.valueOf(222), actual2);
            assertEquals(Integer.valueOf(333), actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after INTEGER GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(Boolean.class, actual1);
            assertInstanceOf(Boolean.class, actual2);
            assertInstanceOf(Boolean.class, actual3);

            assertEquals(Boolean.TRUE, actual1);
            assertEquals(Boolean.FALSE, actual2);
            assertEquals(Boolean.TRUE, actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BOOLEAN GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithCharacterValuesShouldReturnCharacters() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-character-1-" + suffix;
        String key2 = "it-getall-character-2-" + suffix;
        String key3 = "it-getall-character-3-" + suffix;

        try {
            region.put(key1, Character.valueOf('A'));
            region.put(key2, Character.valueOf('Z'));
            region.put(key3, Character.valueOf('0'));

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(Character.class, actual1);
            assertInstanceOf(Character.class, actual2);
            assertInstanceOf(Character.class, actual3);

            assertEquals(Character.valueOf('A'), actual1);
            assertEquals(Character.valueOf('Z'), actual2);
            assertEquals(Character.valueOf('0'), actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after CHARACTER GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void getAllWithByteValuesShouldReturnBytes() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-byte-1-" + suffix;
        String key2 = "it-getall-byte-2-" + suffix;
        String key3 = "it-getall-byte-3-" + suffix;

        try {
            region.put(key1, Byte.valueOf((byte) 7));
            region.put(key2, Byte.valueOf((byte) -7));
            region.put(key3, Byte.valueOf(Byte.MAX_VALUE));

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(Byte.class, actual1);
            assertInstanceOf(Byte.class, actual2);
            assertInstanceOf(Byte.class, actual3);

            assertEquals(Byte.valueOf((byte) 7), actual1);
            assertEquals(Byte.valueOf((byte) -7), actual2);
            assertEquals(Byte.valueOf(Byte.MAX_VALUE), actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BYTE GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithShortValuesShouldReturnShorts() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-short-1-" + suffix;
        String key2 = "it-getall-short-2-" + suffix;
        String key3 = "it-getall-short-3-" + suffix;

        try {
            region.put(key1, Short.valueOf((short) 7));
            region.put(key2, Short.valueOf((short) -7));
            region.put(key3, Short.valueOf(Short.MAX_VALUE));

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(Short.class, actual1);
            assertInstanceOf(Short.class, actual2);
            assertInstanceOf(Short.class, actual3);

            assertEquals(Short.valueOf((short) 7), actual1);
            assertEquals(Short.valueOf((short) -7), actual2);
            assertEquals(Short.valueOf(Short.MAX_VALUE), actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after SHORT GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(Long.class, actual1);
            assertInstanceOf(Long.class, actual2);
            assertInstanceOf(Long.class, actual3);

            assertEquals(Long.valueOf(111L), actual1);
            assertEquals(Long.valueOf(-222L), actual2);
            assertEquals(Long.valueOf(9_876_543_210L), actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after LONG GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(Float.class, actual1);
            assertInstanceOf(Float.class, actual2);
            assertInstanceOf(Float.class, actual3);

            assertEquals(Float.valueOf(7.25f), actual1);
            assertEquals(Float.valueOf(-7.25f), actual2);
            assertEquals(Float.valueOf(987_654.25f), actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after FLOAT GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(Double.class, actual1);
            assertInstanceOf(Double.class, actual2);
            assertInstanceOf(Double.class, actual3);

            assertEquals(Double.valueOf(7.25d), actual1);
            assertEquals(Double.valueOf(-7.25d), actual2);
            assertEquals(Double.valueOf(9_876_543.210d), actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after DOUBLE GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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

            Set<String> keys = new LinkedHashSet<>();
            keys.add(stringKey1);
            keys.add(integerKey1);
            keys.add(stringKey2);
            keys.add(integerKey2);

            Map<String, Object> results = region.getAll(keys);

            Object stringActual1 = results.get(stringKey1);
            Object integerActual1 = results.get(integerKey1);
            Object stringActual2 = results.get(stringKey2);
            Object integerActual2 = results.get(integerKey2);

            assertInstanceOf(String.class, stringActual1);
            assertInstanceOf(Integer.class, integerActual1);
            assertInstanceOf(String.class, stringActual2);
            assertInstanceOf(Integer.class, integerActual2);

            assertEquals("string-value-1-" + suffix, stringActual1);
            assertEquals(Integer.valueOf(1001), integerActual1);
            assertEquals("string-value-2-" + suffix, stringActual2);
            assertEquals(Integer.valueOf(2002), integerActual2);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after MIXED PUT_ALL/GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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

            Set<String> keys = new LinkedHashSet<>();
            keys.add(stringKey);
            keys.add(integerKey);
            keys.add(booleanTrueKey);
            keys.add(booleanFalseKey);

            Map<String, Object> results = region.getAll(keys);

            Object stringActual = results.get(stringKey);
            Object integerActual = results.get(integerKey);
            Object booleanTrueActual = results.get(booleanTrueKey);
            Object booleanFalseActual = results.get(booleanFalseKey);

            assertInstanceOf(String.class, stringActual);
            assertInstanceOf(Integer.class, integerActual);
            assertInstanceOf(Boolean.class, booleanTrueActual);
            assertInstanceOf(Boolean.class, booleanFalseActual);

            assertEquals("string-value-" + suffix, stringActual);
            assertEquals(Integer.valueOf(3003), integerActual);
            assertEquals(Boolean.TRUE, booleanTrueActual);
            assertEquals(Boolean.FALSE, booleanFalseActual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after MIXED STRING/INTEGER/BOOLEAN PUT_ALL/GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

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

            Set<String> keys = new LinkedHashSet<>();
            keys.add(stringKey);
            keys.add(integerKey);
            keys.add(booleanTrueKey);
            keys.add(booleanFalseKey);
            keys.add(longPositiveKey);
            keys.add(longNegativeKey);

            Map<String, Object> results = region.getAll(keys);

            Object stringActual = results.get(stringKey);
            Object integerActual = results.get(integerKey);
            Object booleanTrueActual = results.get(booleanTrueKey);
            Object booleanFalseActual = results.get(booleanFalseKey);
            Object longPositiveActual = results.get(longPositiveKey);
            Object longNegativeActual = results.get(longNegativeKey);

            assertInstanceOf(String.class, stringActual);
            assertInstanceOf(Integer.class, integerActual);
            assertInstanceOf(Boolean.class, booleanTrueActual);
            assertInstanceOf(Boolean.class, booleanFalseActual);
            assertInstanceOf(Long.class, longPositiveActual);
            assertInstanceOf(Long.class, longNegativeActual);

            assertEquals("string-value-" + suffix, stringActual);
            assertEquals(Integer.valueOf(4004), integerActual);
            assertEquals(Boolean.TRUE, booleanTrueActual);
            assertEquals(Boolean.FALSE, booleanFalseActual);
            assertEquals(Long.valueOf(9_876_543_210L), longPositiveActual);
            assertEquals(Long.valueOf(-9_876_543_210L), longNegativeActual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after MIXED STRING/INTEGER/BOOLEAN/LONG PUT_ALL/GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void mixedStringIntegerBooleanAndLongFloatAndDoublePutAllAndGetAllShouldPreserveTypes() {
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

            Set<String> keys = new LinkedHashSet<>();
            keys.add(stringKey);
            keys.add(integerKey);
            keys.add(booleanTrueKey);
            keys.add(booleanFalseKey);
            keys.add(longPositiveKey);
            keys.add(longNegativeKey);
            keys.add(floatPositiveKey);
            keys.add(floatNegativeKey);
            keys.add(doublePositiveKey);
            keys.add(doubleNegativeKey);

            Map<String, Object> results = region.getAll(keys);

            Object stringActual = results.get(stringKey);
            Object integerActual = results.get(integerKey);
            Object booleanTrueActual = results.get(booleanTrueKey);
            Object booleanFalseActual = results.get(booleanFalseKey);
            Object longPositiveActual = results.get(longPositiveKey);
            Object longNegativeActual = results.get(longNegativeKey);
            Object floatPositiveActual = results.get(floatPositiveKey);
            Object floatNegativeActual = results.get(floatNegativeKey);
            Object doublePositiveActual = results.get(doublePositiveKey);
            Object doubleNegativeActual = results.get(doubleNegativeKey);

            assertInstanceOf(String.class, stringActual);
            assertInstanceOf(Integer.class, integerActual);
            assertInstanceOf(Boolean.class, booleanTrueActual);
            assertInstanceOf(Boolean.class, booleanFalseActual);
            assertInstanceOf(Long.class, longPositiveActual);
            assertInstanceOf(Long.class, longNegativeActual);
            assertInstanceOf(Float.class, floatPositiveActual);
            assertInstanceOf(Float.class, floatNegativeActual);
            assertInstanceOf(Double.class, doublePositiveActual);
            assertInstanceOf(Double.class, doubleNegativeActual);

            assertEquals("string-value-" + suffix, stringActual);
            assertEquals(Integer.valueOf(6006), integerActual);
            assertEquals(Boolean.TRUE, booleanTrueActual);
            assertEquals(Boolean.FALSE, booleanFalseActual);
            assertEquals(Long.valueOf(9_876_543_210L), longPositiveActual);
            assertEquals(Long.valueOf(-9_876_543_210L), longNegativeActual);
            assertEquals(Float.valueOf(7.25f), floatPositiveActual);
            assertEquals(Float.valueOf(-7.25f), floatNegativeActual);
            assertEquals(Double.valueOf(7.25d), doublePositiveActual);
            assertEquals(Double.valueOf(-7.25d), doubleNegativeActual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after MIXED STRING/INTEGER/BOOLEAN/LONG/FLOAT/DOUBLE PUT_ALL/GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void mixedStringShortIntegerBooleanLongFloatAndDoublePutAllAndGetAllShouldPreserveTypes() {
        String suffix = UUID.randomUUID().toString();

        String stringKey = "it-mixed7-string-" + suffix;
        String shortKey = "it-mixed7-short-" + suffix;
        String integerKey = "it-mixed7-integer-" + suffix;
        String booleanTrueKey = "it-mixed7-bool-true-" + suffix;
        String booleanFalseKey = "it-mixed7-bool-false-" + suffix;
        String longPositiveKey = "it-mixed7-long-positive-" + suffix;
        String longNegativeKey = "it-mixed7-long-negative-" + suffix;
        String floatPositiveKey = "it-mixed7-float-positive-" + suffix;
        String floatNegativeKey = "it-mixed7-float-negative-" + suffix;
        String doublePositiveKey = "it-mixed7-double-positive-" + suffix;
        String doubleNegativeKey = "it-mixed7-double-negative-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(stringKey, "string-value-" + suffix);
        entries.put(shortKey, Short.valueOf((short) 77));
        entries.put(integerKey, Integer.valueOf(7007));
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

            Set<String> keys = new LinkedHashSet<>();
            keys.add(stringKey);
            keys.add(shortKey);
            keys.add(integerKey);
            keys.add(booleanTrueKey);
            keys.add(booleanFalseKey);
            keys.add(longPositiveKey);
            keys.add(longNegativeKey);
            keys.add(floatPositiveKey);
            keys.add(floatNegativeKey);
            keys.add(doublePositiveKey);
            keys.add(doubleNegativeKey);

            Map<String, Object> results = region.getAll(keys);

            Object stringActual = results.get(stringKey);
            Object shortActual = results.get(shortKey);
            Object integerActual = results.get(integerKey);
            Object booleanTrueActual = results.get(booleanTrueKey);
            Object booleanFalseActual = results.get(booleanFalseKey);
            Object longPositiveActual = results.get(longPositiveKey);
            Object longNegativeActual = results.get(longNegativeKey);
            Object floatPositiveActual = results.get(floatPositiveKey);
            Object floatNegativeActual = results.get(floatNegativeKey);
            Object doublePositiveActual = results.get(doublePositiveKey);
            Object doubleNegativeActual = results.get(doubleNegativeKey);

            assertInstanceOf(String.class, stringActual);
            assertInstanceOf(Short.class, shortActual);
            assertInstanceOf(Integer.class, integerActual);
            assertInstanceOf(Boolean.class, booleanTrueActual);
            assertInstanceOf(Boolean.class, booleanFalseActual);
            assertInstanceOf(Long.class, longPositiveActual);
            assertInstanceOf(Long.class, longNegativeActual);
            assertInstanceOf(Float.class, floatPositiveActual);
            assertInstanceOf(Float.class, floatNegativeActual);
            assertInstanceOf(Double.class, doublePositiveActual);
            assertInstanceOf(Double.class, doubleNegativeActual);

            assertEquals("string-value-" + suffix, stringActual);
            assertEquals(Short.valueOf((short) 77), shortActual);
            assertEquals(Integer.valueOf(7007), integerActual);
            assertEquals(Boolean.TRUE, booleanTrueActual);
            assertEquals(Boolean.FALSE, booleanFalseActual);
            assertEquals(Long.valueOf(9_876_543_210L), longPositiveActual);
            assertEquals(Long.valueOf(-9_876_543_210L), longNegativeActual);
            assertEquals(Float.valueOf(7.25f), floatPositiveActual);
            assertEquals(Float.valueOf(-7.25f), floatNegativeActual);
            assertEquals(Double.valueOf(7.25d), doublePositiveActual);
            assertEquals(Double.valueOf(-7.25d), doubleNegativeActual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after MIXED STRING/SHORT/INTEGER/BOOLEAN/LONG/FLOAT/DOUBLE PUT_ALL/GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void mixedStringCharacterShortIntegerBooleanLongFloatAndDoublePutAllAndGetAllShouldPreserveTypes() {
        String suffix = UUID.randomUUID().toString();

        String stringKey = "it-mixed8-string-" + suffix;
        String characterKey = "it-mixed8-character-" + suffix;
        String shortKey = "it-mixed8-short-" + suffix;
        String integerKey = "it-mixed8-integer-" + suffix;
        String booleanTrueKey = "it-mixed8-bool-true-" + suffix;
        String booleanFalseKey = "it-mixed8-bool-false-" + suffix;
        String longPositiveKey = "it-mixed8-long-positive-" + suffix;
        String longNegativeKey = "it-mixed8-long-negative-" + suffix;
        String floatPositiveKey = "it-mixed8-float-positive-" + suffix;
        String floatNegativeKey = "it-mixed8-float-negative-" + suffix;
        String doublePositiveKey = "it-mixed8-double-positive-" + suffix;
        String doubleNegativeKey = "it-mixed8-double-negative-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(stringKey, "string-value-" + suffix);
        entries.put(characterKey, Character.valueOf('A'));
        entries.put(shortKey, Short.valueOf((short) 77));
        entries.put(integerKey, Integer.valueOf(8008));
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

            Set<String> keys = new LinkedHashSet<>();
            keys.add(stringKey);
            keys.add(characterKey);
            keys.add(shortKey);
            keys.add(integerKey);
            keys.add(booleanTrueKey);
            keys.add(booleanFalseKey);
            keys.add(longPositiveKey);
            keys.add(longNegativeKey);
            keys.add(floatPositiveKey);
            keys.add(floatNegativeKey);
            keys.add(doublePositiveKey);
            keys.add(doubleNegativeKey);

            Map<String, Object> results = region.getAll(keys);

            Object stringActual = results.get(stringKey);
            Object characterActual = results.get(characterKey);
            Object shortActual = results.get(shortKey);
            Object integerActual = results.get(integerKey);
            Object booleanTrueActual = results.get(booleanTrueKey);
            Object booleanFalseActual = results.get(booleanFalseKey);
            Object longPositiveActual = results.get(longPositiveKey);
            Object longNegativeActual = results.get(longNegativeKey);
            Object floatPositiveActual = results.get(floatPositiveKey);
            Object floatNegativeActual = results.get(floatNegativeKey);
            Object doublePositiveActual = results.get(doublePositiveKey);
            Object doubleNegativeActual = results.get(doubleNegativeKey);

            assertInstanceOf(String.class, stringActual);
            assertInstanceOf(Character.class, characterActual);
            assertInstanceOf(Short.class, shortActual);
            assertInstanceOf(Integer.class, integerActual);
            assertInstanceOf(Boolean.class, booleanTrueActual);
            assertInstanceOf(Boolean.class, booleanFalseActual);
            assertInstanceOf(Long.class, longPositiveActual);
            assertInstanceOf(Long.class, longNegativeActual);
            assertInstanceOf(Float.class, floatPositiveActual);
            assertInstanceOf(Float.class, floatNegativeActual);
            assertInstanceOf(Double.class, doublePositiveActual);
            assertInstanceOf(Double.class, doubleNegativeActual);

            assertEquals("string-value-" + suffix, stringActual);
            assertEquals(Character.valueOf('A'), characterActual);
            assertEquals(Short.valueOf((short) 77), shortActual);
            assertEquals(Integer.valueOf(8008), integerActual);
            assertEquals(Boolean.TRUE, booleanTrueActual);
            assertEquals(Boolean.FALSE, booleanFalseActual);
            assertEquals(Long.valueOf(9_876_543_210L), longPositiveActual);
            assertEquals(Long.valueOf(-9_876_543_210L), longNegativeActual);
            assertEquals(Float.valueOf(7.25f), floatPositiveActual);
            assertEquals(Float.valueOf(-7.25f), floatNegativeActual);
            assertEquals(Double.valueOf(7.25d), doublePositiveActual);
            assertEquals(Double.valueOf(-7.25d), doubleNegativeActual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after MIXED STRING/CHARACTER/SHORT/INTEGER/BOOLEAN/LONG/FLOAT/DOUBLE PUT_ALL/GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void mixedStringCharacterByteShortIntegerBooleanLongFloatAndDoublePutAllAndGetAllShouldPreserveTypes() {
        String suffix = UUID.randomUUID().toString();

        String stringKey = "it-mixed9-string-" + suffix;
        String characterKey = "it-mixed9-character-" + suffix;
        String byteKey = "it-mixed9-byte-" + suffix;
        String shortKey = "it-mixed9-short-" + suffix;
        String integerKey = "it-mixed9-integer-" + suffix;
        String booleanTrueKey = "it-mixed9-bool-true-" + suffix;
        String booleanFalseKey = "it-mixed9-bool-false-" + suffix;
        String longPositiveKey = "it-mixed9-long-positive-" + suffix;
        String longNegativeKey = "it-mixed9-long-negative-" + suffix;
        String floatPositiveKey = "it-mixed9-float-positive-" + suffix;
        String floatNegativeKey = "it-mixed9-float-negative-" + suffix;
        String doublePositiveKey = "it-mixed9-double-positive-" + suffix;
        String doubleNegativeKey = "it-mixed9-double-negative-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(stringKey, "string-value-" + suffix);
        entries.put(characterKey, Character.valueOf('A'));
        entries.put(byteKey, Byte.valueOf((byte) 7));
        entries.put(shortKey, Short.valueOf((short) 77));
        entries.put(integerKey, Integer.valueOf(9009));
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

            Set<String> keys = new LinkedHashSet<>();
            keys.add(stringKey);
            keys.add(characterKey);
            keys.add(byteKey);
            keys.add(shortKey);
            keys.add(integerKey);
            keys.add(booleanTrueKey);
            keys.add(booleanFalseKey);
            keys.add(longPositiveKey);
            keys.add(longNegativeKey);
            keys.add(floatPositiveKey);
            keys.add(floatNegativeKey);
            keys.add(doublePositiveKey);
            keys.add(doubleNegativeKey);

            Map<String, Object> results = region.getAll(keys);

            Object stringActual = results.get(stringKey);
            Object characterActual = results.get(characterKey);
            Object byteActual = results.get(byteKey);
            Object shortActual = results.get(shortKey);
            Object integerActual = results.get(integerKey);
            Object booleanTrueActual = results.get(booleanTrueKey);
            Object booleanFalseActual = results.get(booleanFalseKey);
            Object longPositiveActual = results.get(longPositiveKey);
            Object longNegativeActual = results.get(longNegativeKey);
            Object floatPositiveActual = results.get(floatPositiveKey);
            Object floatNegativeActual = results.get(floatNegativeKey);
            Object doublePositiveActual = results.get(doublePositiveKey);
            Object doubleNegativeActual = results.get(doubleNegativeKey);

            assertInstanceOf(String.class, stringActual);
            assertInstanceOf(Character.class, characterActual);
            assertInstanceOf(Byte.class, byteActual);
            assertInstanceOf(Short.class, shortActual);
            assertInstanceOf(Integer.class, integerActual);
            assertInstanceOf(Boolean.class, booleanTrueActual);
            assertInstanceOf(Boolean.class, booleanFalseActual);
            assertInstanceOf(Long.class, longPositiveActual);
            assertInstanceOf(Long.class, longNegativeActual);
            assertInstanceOf(Float.class, floatPositiveActual);
            assertInstanceOf(Float.class, floatNegativeActual);
            assertInstanceOf(Double.class, doublePositiveActual);
            assertInstanceOf(Double.class, doubleNegativeActual);

            assertEquals("string-value-" + suffix, stringActual);
            assertEquals(Character.valueOf('A'), characterActual);
            assertEquals(Byte.valueOf((byte) 7), byteActual);
            assertEquals(Short.valueOf((short) 77), shortActual);
            assertEquals(Integer.valueOf(9009), integerActual);
            assertEquals(Boolean.TRUE, booleanTrueActual);
            assertEquals(Boolean.FALSE, booleanFalseActual);
            assertEquals(Long.valueOf(9_876_543_210L), longPositiveActual);
            assertEquals(Long.valueOf(-9_876_543_210L), longNegativeActual);
            assertEquals(Float.valueOf(7.25f), floatPositiveActual);
            assertEquals(Float.valueOf(-7.25f), floatNegativeActual);
            assertEquals(Double.valueOf(7.25d), doublePositiveActual);
            assertEquals(Double.valueOf(-7.25d), doubleNegativeActual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after MIXED STRING/CHARACTER/BYTE/SHORT/INTEGER/BOOLEAN/LONG/FLOAT/DOUBLE PUT_ALL/GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void dateValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-date-value-" + suffix;

        Date expected = new Date(1_000L);

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Date.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after DATE round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void uuidValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-uuid-value-" + suffix;
        UUID expected = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        try {
            region.put(key, expected);
            Object actual = region.get(key);
            assertInstanceOf(UUID.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after UUID round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();
            throw e;
        }
    }

    @Test
    void bigIntegerValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-big-integer-value-" + suffix;
        BigInteger expected = new BigInteger("123456789012345678901234567890");

        try {
            region.put(key, expected);
            Object actual = region.get(key);
            assertInstanceOf(BigInteger.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BIG_INTEGER round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();
            throw e;
        }
    }

    @Test
    void bigDecimalValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-big-decimal-value-" + suffix;
        BigDecimal expected = new BigDecimal("1234567890.123456789");

        try {
            region.put(key, expected);
            Object actual = region.get(key);
            assertInstanceOf(BigDecimal.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BIG_DECIMAL round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();
            throw e;
        }
    }

    @Test
    void enumValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-enum-value-" + suffix;
        DemoStatus expected = DemoStatus.ACTIVE;

        try {
            region.put(key, expected);
            Object actual = region.get(key);
            assertInstanceOf(DemoStatus.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after ENUM round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();
            throw e;
        }
    }

    @Test
    void instantValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-instant-value-" + suffix;
        Instant expected = Instant.parse("2026-05-13T20:37:37Z");

        try {
            region.put(key, expected);
            Object actual = region.get(key);
            assertInstanceOf(Instant.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after INSTANT round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();
            throw e;
        }
    }

    @Test
    void localDateValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-local-date-value-" + suffix;
        LocalDate expected = LocalDate.of(2026, 5, 13);

        try {
            region.put(key, expected);
            Object actual = region.get(key);
            assertInstanceOf(LocalDate.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after LOCAL_DATE round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();
            throw e;
        }
    }

    @Test
    void localDateTimeValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-local-date-time-value-" + suffix;
        LocalDateTime expected = LocalDateTime.of(2026, 5, 13, 20, 37, 37);

        try {
            region.put(key, expected);
            Object actual = region.get(key);
            assertInstanceOf(LocalDateTime.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after LOCAL_DATE_TIME round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();
            throw e;
        }
    }

    @Test
    void putAllWithStandaloneUtilityValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String uuidKey = "it-putall-uuid-value-" + suffix;
        String bigIntegerKey = "it-putall-big-integer-value-" + suffix;
        String bigDecimalKey = "it-putall-big-decimal-value-" + suffix;
        String enumKey = "it-putall-enum-value-" + suffix;
        String instantKey = "it-putall-instant-value-" + suffix;
        String localDateKey = "it-putall-local-date-value-" + suffix;
        String localDateTimeKey = "it-putall-local-date-time-value-" + suffix;

        UUID expectedUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        BigInteger expectedBigInteger = new BigInteger("123456789012345678901234567890");
        BigDecimal expectedBigDecimal = new BigDecimal("1234567890.123456789");
        DemoStatus expectedEnum = DemoStatus.ACTIVE;
        Instant expectedInstant = Instant.parse("2026-05-13T20:37:37Z");
        LocalDate expectedLocalDate = LocalDate.of(2026, 5, 13);
        LocalDateTime expectedLocalDateTime = LocalDateTime.of(2026, 5, 13, 20, 37, 37);

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(uuidKey, expectedUuid);
        entries.put(bigIntegerKey, expectedBigInteger);
        entries.put(bigDecimalKey, expectedBigDecimal);
        entries.put(enumKey, expectedEnum);
        entries.put(instantKey, expectedInstant);
        entries.put(localDateKey, expectedLocalDate);
        entries.put(localDateTimeKey, expectedLocalDateTime);

        try {
            region.putAll(entries);

            assertEquals(expectedUuid, region.get(uuidKey));
            assertEquals(expectedBigInteger, region.get(bigIntegerKey));
            assertEquals(expectedBigDecimal, region.get(bigDecimalKey));
            assertEquals(expectedEnum, region.get(enumKey));
            assertEquals(expectedInstant, region.get(instantKey));
            assertEquals(expectedLocalDate, region.get(localDateKey));
            assertEquals(expectedLocalDateTime, region.get(localDateTimeKey));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STANDALONE_UTILITY PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();
            throw e;
        }
    }

    @Test
    void getAllWithStandaloneUtilityValuesShouldReturnValues() {
        String suffix = UUID.randomUUID().toString();

        String uuidKey = "it-getall-uuid-value-" + suffix;
        String bigIntegerKey = "it-getall-big-integer-value-" + suffix;
        String bigDecimalKey = "it-getall-big-decimal-value-" + suffix;
        String enumKey = "it-getall-enum-value-" + suffix;
        String instantKey = "it-getall-instant-value-" + suffix;
        String localDateKey = "it-getall-local-date-value-" + suffix;
        String localDateTimeKey = "it-getall-local-date-time-value-" + suffix;

        UUID expectedUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        BigInteger expectedBigInteger = new BigInteger("123456789012345678901234567890");
        BigDecimal expectedBigDecimal = new BigDecimal("1234567890.123456789");
        DemoStatus expectedEnum = DemoStatus.ACTIVE;
        Instant expectedInstant = Instant.parse("2026-05-13T20:37:37Z");
        LocalDate expectedLocalDate = LocalDate.of(2026, 5, 13);
        LocalDateTime expectedLocalDateTime = LocalDateTime.of(2026, 5, 13, 20, 37, 37);

        try {
            region.put(uuidKey, expectedUuid);
            region.put(bigIntegerKey, expectedBigInteger);
            region.put(bigDecimalKey, expectedBigDecimal);
            region.put(enumKey, expectedEnum);
            region.put(instantKey, expectedInstant);
            region.put(localDateKey, expectedLocalDate);
            region.put(localDateTimeKey, expectedLocalDateTime);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(uuidKey);
            keys.add(bigIntegerKey);
            keys.add(bigDecimalKey);
            keys.add(enumKey);
            keys.add(instantKey);
            keys.add(localDateKey);
            keys.add(localDateTimeKey);

            Map<String, Object> results = region.getAll(keys);

            assertEquals(expectedUuid, results.get(uuidKey));
            assertEquals(expectedBigInteger, results.get(bigIntegerKey));
            assertEquals(expectedBigDecimal, results.get(bigDecimalKey));
            assertEquals(expectedEnum, results.get(enumKey));
            assertEquals(expectedInstant, results.get(instantKey));
            assertEquals(expectedLocalDate, results.get(localDateKey));
            assertEquals(expectedLocalDateTime, results.get(localDateTimeKey));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STANDALONE_UTILITY GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();
            throw e;
        }
    }


    @Test
    void byteArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-byte-array-value-" + suffix;

        byte[] expected = new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(byte[].class, actual);
            assertArrayEquals(expected, (byte[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BYTE_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void booleanArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-boolean-array-value-" + suffix;

        boolean[] expected = new boolean[] {
                true,
                false,
                true,
                true,
                false
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(boolean[].class, actual);
            assertArrayEquals(expected, (boolean[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BOOLEAN_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void charArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-char-array-value-" + suffix;

        char[] expected = new char[] {
                'A',
                'Z',
                '0',
                '\n',
                '\u2603'
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(char[].class, actual);
            assertArrayEquals(expected, (char[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after CHAR_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void shortArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-short-array-value-" + suffix;

        short[] expected = new short[] {
                1,
                42,
                -7,
                Short.MAX_VALUE,
                Short.MIN_VALUE
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(short[].class, actual);
            assertArrayEquals(expected, (short[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after SHORT_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }



    @Test
    void intArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-int-array-value-" + suffix;

        int[] expected = new int[] {
                1,
                42,
                -7,
                Integer.MAX_VALUE,
                Integer.MIN_VALUE
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(int[].class, actual);
            assertArrayEquals(expected, (int[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after INT_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void emptyIntArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-empty-int-array-value-" + suffix;

        int[] expected = new int[] {};

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(int[].class, actual);
            assertArrayEquals(expected, (int[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after EMPTY INT_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void longArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-long-array-value-" + suffix;

        long[] expected = new long[] {
                1L,
                42L,
                -7L,
                9_876_543_210L,
                Long.MAX_VALUE,
                Long.MIN_VALUE
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(long[].class, actual);
            assertArrayEquals(expected, (long[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after LONG_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void floatArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-float-array-value-" + suffix;

        float[] expected = new float[] {
                1.0f,
                7.25f,
                -7.25f,
                Float.MAX_VALUE,
                Float.MIN_VALUE
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(float[].class, actual);
            assertArrayEquals(expected, (float[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after FLOAT_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void doubleArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-double-array-value-" + suffix;

        double[] expected = new double[] {
                1.0d,
                7.25d,
                -7.25d,
                Double.MAX_VALUE,
                Double.MIN_VALUE
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(double[].class, actual);
            assertArrayEquals(expected, (double[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after DOUBLE_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void integerWrapperArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-integer-wrapper-array-value-" + suffix;

        Integer[] expected = new Integer[] {
                Integer.valueOf(1),
                Integer.valueOf(42),
                Integer.valueOf(-7),
                null,
                Integer.MAX_VALUE,
                Integer.MIN_VALUE
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Integer[].class, actual);
            assertArrayEquals(expected, (Integer[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after INTEGER_WRAPPER_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void longWrapperArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-long-wrapper-array-value-" + suffix;

        Long[] expected = new Long[] {
                Long.valueOf(1L),
                Long.valueOf(42L),
                Long.valueOf(-7L),
                null,
                Long.valueOf(9_876_543_210L),
                Long.MAX_VALUE,
                Long.MIN_VALUE
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Long[].class, actual);
            assertArrayEquals(expected, (Long[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after LONG_WRAPPER_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void booleanWrapperArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-boolean-wrapper-array-value-" + suffix;

        Boolean[] expected = new Boolean[] {
                Boolean.TRUE,
                Boolean.FALSE,
                null,
                Boolean.TRUE
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Boolean[].class, actual);
            assertArrayEquals(expected, (Boolean[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BOOLEAN_WRAPPER_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void doubleWrapperArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-double-wrapper-array-value-" + suffix;

        Double[] expected = new Double[] {
                Double.valueOf(1.0d),
                Double.valueOf(7.25d),
                Double.valueOf(-7.25d),
                null,
                Double.MAX_VALUE,
                Double.MIN_VALUE
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Double[].class, actual);
            assertArrayEquals(expected, (Double[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after DOUBLE_WRAPPER_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void uuidArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-uuid-array-value-" + suffix;

        UUID[] expected = new UUID[] {
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000001")
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(UUID[].class, actual);
            assertArrayEquals(expected, (UUID[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after UUID_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void bigIntegerArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-big-integer-array-value-" + suffix;

        BigInteger[] expected = new BigInteger[] {
                BigInteger.ONE,
                BigInteger.valueOf(42L),
                null,
                BigInteger.valueOf(-7L),
                new BigInteger("123456789012345678901234567890")
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(BigInteger[].class, actual);
            assertArrayEquals(expected, (BigInteger[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BIG_INTEGER_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void bigDecimalArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-big-decimal-array-value-" + suffix;

        BigDecimal[] expected = new BigDecimal[] {
                new BigDecimal("1.00"),
                new BigDecimal("42.42"),
                null,
                new BigDecimal("-7.25"),
                new BigDecimal("1234567890.123456789")
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(BigDecimal[].class, actual);
            assertArrayEquals(expected, (BigDecimal[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BIG_DECIMAL_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void enumArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-enum-array-value-" + suffix;

        DemoStatus[] expected = new DemoStatus[] {
                DemoStatus.ACTIVE,
                DemoStatus.INACTIVE,
                null,
                DemoStatus.PENDING
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(DemoStatus[].class, actual);
            assertArrayEquals(expected, (DemoStatus[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after ENUM_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void instantArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-instant-array-value-" + suffix;

        Instant[] expected = new Instant[] {
                Instant.parse("2026-05-13T20:37:37Z"),
                null,
                Instant.EPOCH
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Instant[].class, actual);
            assertArrayEquals(expected, (Instant[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after INSTANT_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void localDateArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-local-date-array-value-" + suffix;

        LocalDate[] expected = new LocalDate[] {
                LocalDate.of(2026, 5, 13),
                null,
                LocalDate.of(1970, 1, 1)
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(LocalDate[].class, actual);
            assertArrayEquals(expected, (LocalDate[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after LOCAL_DATE_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void localDateTimeArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-local-date-time-array-value-" + suffix;

        LocalDateTime[] expected = new LocalDateTime[] {
                LocalDateTime.of(2026, 5, 13, 20, 37, 37),
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0, 0)
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(LocalDateTime[].class, actual);
            assertArrayEquals(expected, (LocalDateTime[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after LOCAL_DATE_TIME_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithWrapperAndUtilityArrayValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String integerArrayKey = "it-putall-integer-wrapper-array-" + suffix;
        String longArrayKey = "it-putall-long-wrapper-array-" + suffix;
        String booleanArrayKey = "it-putall-boolean-wrapper-array-" + suffix;
        String doubleArrayKey = "it-putall-double-wrapper-array-" + suffix;
        String uuidArrayKey = "it-putall-uuid-array-" + suffix;
        String bigIntegerArrayKey = "it-putall-big-integer-array-" + suffix;
        String bigDecimalArrayKey = "it-putall-big-decimal-array-" + suffix;
        String enumArrayKey = "it-putall-enum-array-" + suffix;
        String instantArrayKey = "it-putall-instant-array-" + suffix;
        String localDateArrayKey = "it-putall-local-date-array-" + suffix;
        String localDateTimeArrayKey = "it-putall-local-date-time-array-" + suffix;

        Integer[] expectedIntegerArray = new Integer[] {1, 42, -7, null, Integer.MAX_VALUE, Integer.MIN_VALUE};
        Long[] expectedLongArray = new Long[] {1L, 42L, -7L, null, 9_876_543_210L, Long.MAX_VALUE, Long.MIN_VALUE};
        Boolean[] expectedBooleanArray = new Boolean[] {Boolean.TRUE, Boolean.FALSE, null, Boolean.TRUE};
        Double[] expectedDoubleArray = new Double[] {1.0d, 7.25d, -7.25d, null, Double.MAX_VALUE, Double.MIN_VALUE};
        UUID[] expectedUuidArray = new UUID[] {
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000001")
        };
        BigInteger[] expectedBigIntegerArray = new BigInteger[] {
                BigInteger.ONE,
                BigInteger.valueOf(42L),
                null,
                BigInteger.valueOf(-7L),
                new BigInteger("123456789012345678901234567890")
        };
        BigDecimal[] expectedBigDecimalArray = new BigDecimal[] {
                new BigDecimal("1.00"),
                new BigDecimal("42.42"),
                null,
                new BigDecimal("-7.25"),
                new BigDecimal("1234567890.123456789")
        };
        DemoStatus[] expectedEnumArray = new DemoStatus[] {
                DemoStatus.ACTIVE,
                DemoStatus.INACTIVE,
                null,
                DemoStatus.PENDING
        };
        Instant[] expectedInstantArray = new Instant[] {
                Instant.parse("2026-05-13T20:37:37Z"),
                null,
                Instant.EPOCH
        };
        LocalDate[] expectedLocalDateArray = new LocalDate[] {
                LocalDate.of(2026, 5, 13),
                null,
                LocalDate.of(1970, 1, 1)
        };
        LocalDateTime[] expectedLocalDateTimeArray = new LocalDateTime[] {
                LocalDateTime.of(2026, 5, 13, 20, 37, 37),
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0, 0)
        };

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(integerArrayKey, expectedIntegerArray);
        entries.put(longArrayKey, expectedLongArray);
        entries.put(booleanArrayKey, expectedBooleanArray);
        entries.put(doubleArrayKey, expectedDoubleArray);
        entries.put(uuidArrayKey, expectedUuidArray);
        entries.put(bigIntegerArrayKey, expectedBigIntegerArray);
        entries.put(bigDecimalArrayKey, expectedBigDecimalArray);
        entries.put(enumArrayKey, expectedEnumArray);
        entries.put(instantArrayKey, expectedInstantArray);
        entries.put(localDateArrayKey, expectedLocalDateArray);
        entries.put(localDateTimeArrayKey, expectedLocalDateTimeArray);

        try {
            region.putAll(entries);

            assertArrayEquals(expectedIntegerArray, (Integer[]) region.get(integerArrayKey));
            assertArrayEquals(expectedLongArray, (Long[]) region.get(longArrayKey));
            assertArrayEquals(expectedBooleanArray, (Boolean[]) region.get(booleanArrayKey));
            assertArrayEquals(expectedDoubleArray, (Double[]) region.get(doubleArrayKey));
            assertArrayEquals(expectedUuidArray, (UUID[]) region.get(uuidArrayKey));
            assertArrayEquals(expectedBigIntegerArray, (BigInteger[]) region.get(bigIntegerArrayKey));
            assertArrayEquals(expectedBigDecimalArray, (BigDecimal[]) region.get(bigDecimalArrayKey));
            assertArrayEquals(expectedEnumArray, (DemoStatus[]) region.get(enumArrayKey));
            assertArrayEquals(expectedInstantArray, (Instant[]) region.get(instantArrayKey));
            assertArrayEquals(expectedLocalDateArray, (LocalDate[]) region.get(localDateArrayKey));
            assertArrayEquals(expectedLocalDateTimeArray, (LocalDateTime[]) region.get(localDateTimeArrayKey));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after WRAPPER_AND_UTILITY_ARRAY PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithWrapperAndUtilityArrayValuesShouldReturnArrays() {
        String suffix = UUID.randomUUID().toString();

        String integerArrayKey = "it-getall-integer-wrapper-array-" + suffix;
        String longArrayKey = "it-getall-long-wrapper-array-" + suffix;
        String booleanArrayKey = "it-getall-boolean-wrapper-array-" + suffix;
        String doubleArrayKey = "it-getall-double-wrapper-array-" + suffix;
        String uuidArrayKey = "it-getall-uuid-array-" + suffix;
        String bigIntegerArrayKey = "it-getall-big-integer-array-" + suffix;
        String bigDecimalArrayKey = "it-getall-big-decimal-array-" + suffix;
        String enumArrayKey = "it-getall-enum-array-" + suffix;
        String instantArrayKey = "it-getall-instant-array-" + suffix;
        String localDateArrayKey = "it-getall-local-date-array-" + suffix;
        String localDateTimeArrayKey = "it-getall-local-date-time-array-" + suffix;

        Integer[] expectedIntegerArray = new Integer[] {1, 42, -7, null, Integer.MAX_VALUE, Integer.MIN_VALUE};
        Long[] expectedLongArray = new Long[] {1L, 42L, -7L, null, 9_876_543_210L, Long.MAX_VALUE, Long.MIN_VALUE};
        Boolean[] expectedBooleanArray = new Boolean[] {Boolean.TRUE, Boolean.FALSE, null, Boolean.TRUE};
        Double[] expectedDoubleArray = new Double[] {1.0d, 7.25d, -7.25d, null, Double.MAX_VALUE, Double.MIN_VALUE};
        UUID[] expectedUuidArray = new UUID[] {
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000001")
        };
        BigInteger[] expectedBigIntegerArray = new BigInteger[] {
                BigInteger.ONE,
                BigInteger.valueOf(42L),
                null,
                BigInteger.valueOf(-7L),
                new BigInteger("123456789012345678901234567890")
        };
        BigDecimal[] expectedBigDecimalArray = new BigDecimal[] {
                new BigDecimal("1.00"),
                new BigDecimal("42.42"),
                null,
                new BigDecimal("-7.25"),
                new BigDecimal("1234567890.123456789")
        };
        DemoStatus[] expectedEnumArray = new DemoStatus[] {
                DemoStatus.ACTIVE,
                DemoStatus.INACTIVE,
                null,
                DemoStatus.PENDING
        };
        Instant[] expectedInstantArray = new Instant[] {
                Instant.parse("2026-05-13T20:37:37Z"),
                null,
                Instant.EPOCH
        };
        LocalDate[] expectedLocalDateArray = new LocalDate[] {
                LocalDate.of(2026, 5, 13),
                null,
                LocalDate.of(1970, 1, 1)
        };
        LocalDateTime[] expectedLocalDateTimeArray = new LocalDateTime[] {
                LocalDateTime.of(2026, 5, 13, 20, 37, 37),
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0, 0)
        };

        try {
            region.put(integerArrayKey, expectedIntegerArray);
            region.put(longArrayKey, expectedLongArray);
            region.put(booleanArrayKey, expectedBooleanArray);
            region.put(doubleArrayKey, expectedDoubleArray);
            region.put(uuidArrayKey, expectedUuidArray);
            region.put(bigIntegerArrayKey, expectedBigIntegerArray);
            region.put(bigDecimalArrayKey, expectedBigDecimalArray);
            region.put(enumArrayKey, expectedEnumArray);
            region.put(instantArrayKey, expectedInstantArray);
            region.put(localDateArrayKey, expectedLocalDateArray);
            region.put(localDateTimeArrayKey, expectedLocalDateTimeArray);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(integerArrayKey);
            keys.add(longArrayKey);
            keys.add(booleanArrayKey);
            keys.add(doubleArrayKey);
            keys.add(uuidArrayKey);
            keys.add(bigIntegerArrayKey);
            keys.add(bigDecimalArrayKey);
            keys.add(enumArrayKey);
            keys.add(instantArrayKey);
            keys.add(localDateArrayKey);
            keys.add(localDateTimeArrayKey);

            Map<String, Object> results = region.getAll(keys);

            assertArrayEquals(expectedIntegerArray, (Integer[]) results.get(integerArrayKey));
            assertArrayEquals(expectedLongArray, (Long[]) results.get(longArrayKey));
            assertArrayEquals(expectedBooleanArray, (Boolean[]) results.get(booleanArrayKey));
            assertArrayEquals(expectedDoubleArray, (Double[]) results.get(doubleArrayKey));
            assertArrayEquals(expectedUuidArray, (UUID[]) results.get(uuidArrayKey));
            assertArrayEquals(expectedBigIntegerArray, (BigInteger[]) results.get(bigIntegerArrayKey));
            assertArrayEquals(expectedBigDecimalArray, (BigDecimal[]) results.get(bigDecimalArrayKey));
            assertArrayEquals(expectedEnumArray, (DemoStatus[]) results.get(enumArrayKey));
            assertArrayEquals(expectedInstantArray, (Instant[]) results.get(instantArrayKey));
            assertArrayEquals(expectedLocalDateArray, (LocalDate[]) results.get(localDateArrayKey));
            assertArrayEquals(expectedLocalDateTimeArray, (LocalDateTime[]) results.get(localDateTimeArrayKey));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after WRAPPER_AND_UTILITY_ARRAY GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }




    @Test
    void putAllWithIntArrayValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-int-array-1-" + suffix;
        String key2 = "it-putall-int-array-2-" + suffix;
        String key3 = "it-putall-int-array-3-" + suffix;

        int[] expected1 = new int[] {};
        int[] expected2 = new int[] {1, 42, -7};
        int[] expected3 = new int[] {
                1,
                42,
                -7,
                Integer.MAX_VALUE,
                Integer.MIN_VALUE
        };

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, expected1);
        entries.put(key2, expected2);
        entries.put(key3, expected3);

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(int[].class, actual1);
            assertInstanceOf(int[].class, actual2);
            assertInstanceOf(int[].class, actual3);

            assertArrayEquals(expected1, (int[]) actual1);
            assertArrayEquals(expected2, (int[]) actual2);
            assertArrayEquals(expected3, (int[]) actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after INT_ARRAY PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithPrimitiveArrayFamilyValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String booleanKey = "it-putall-boolean-array-" + suffix;
        String charKey = "it-putall-char-array-" + suffix;
        String shortKey = "it-putall-short-array-" + suffix;
        String longKey = "it-putall-long-array-" + suffix;
        String floatKey = "it-putall-float-array-" + suffix;
        String doubleKey = "it-putall-double-array-" + suffix;

        boolean[] expectedBoolean = new boolean[] {true, false, true, true, false};
        char[] expectedChar = new char[] {'A', 'Z', '0', '\n', '\u2603'};
        short[] expectedShort = new short[] {1, 42, -7, Short.MAX_VALUE, Short.MIN_VALUE};
        long[] expectedLong = new long[] {1L, 42L, -7L, 9_876_543_210L, Long.MAX_VALUE, Long.MIN_VALUE};
        float[] expectedFloat = new float[] {1.0f, 7.25f, -7.25f, Float.MAX_VALUE, Float.MIN_VALUE};
        double[] expectedDouble = new double[] {1.0d, 7.25d, -7.25d, Double.MAX_VALUE, Double.MIN_VALUE};

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(booleanKey, expectedBoolean);
        entries.put(charKey, expectedChar);
        entries.put(shortKey, expectedShort);
        entries.put(longKey, expectedLong);
        entries.put(floatKey, expectedFloat);
        entries.put(doubleKey, expectedDouble);

        try {
            region.putAll(entries);

            Object actualBoolean = region.get(booleanKey);
            Object actualChar = region.get(charKey);
            Object actualShort = region.get(shortKey);
            Object actualLong = region.get(longKey);
            Object actualFloat = region.get(floatKey);
            Object actualDouble = region.get(doubleKey);

            assertInstanceOf(boolean[].class, actualBoolean);
            assertInstanceOf(char[].class, actualChar);
            assertInstanceOf(short[].class, actualShort);
            assertInstanceOf(long[].class, actualLong);
            assertInstanceOf(float[].class, actualFloat);
            assertInstanceOf(double[].class, actualDouble);

            assertArrayEquals(expectedBoolean, (boolean[]) actualBoolean);
            assertArrayEquals(expectedChar, (char[]) actualChar);
            assertArrayEquals(expectedShort, (short[]) actualShort);
            assertArrayEquals(expectedLong, (long[]) actualLong);
            assertArrayEquals(expectedFloat, (float[]) actualFloat);
            assertArrayEquals(expectedDouble, (double[]) actualDouble);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PRIMITIVE_ARRAY_FAMILY PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithPrimitiveArrayFamilyValuesShouldReturnPrimitiveArrays() {
        String suffix = UUID.randomUUID().toString();

        String booleanKey = "it-getall-boolean-array-" + suffix;
        String charKey = "it-getall-char-array-" + suffix;
        String shortKey = "it-getall-short-array-" + suffix;
        String longKey = "it-getall-long-array-" + suffix;
        String floatKey = "it-getall-float-array-" + suffix;
        String doubleKey = "it-getall-double-array-" + suffix;

        boolean[] expectedBoolean = new boolean[] {true, false, true, true, false};
        char[] expectedChar = new char[] {'A', 'Z', '0', '\n', '\u2603'};
        short[] expectedShort = new short[] {1, 42, -7, Short.MAX_VALUE, Short.MIN_VALUE};
        long[] expectedLong = new long[] {1L, 42L, -7L, 9_876_543_210L, Long.MAX_VALUE, Long.MIN_VALUE};
        float[] expectedFloat = new float[] {1.0f, 7.25f, -7.25f, Float.MAX_VALUE, Float.MIN_VALUE};
        double[] expectedDouble = new double[] {1.0d, 7.25d, -7.25d, Double.MAX_VALUE, Double.MIN_VALUE};

        try {
            region.put(booleanKey, expectedBoolean);
            region.put(charKey, expectedChar);
            region.put(shortKey, expectedShort);
            region.put(longKey, expectedLong);
            region.put(floatKey, expectedFloat);
            region.put(doubleKey, expectedDouble);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(booleanKey);
            keys.add(charKey);
            keys.add(shortKey);
            keys.add(longKey);
            keys.add(floatKey);
            keys.add(doubleKey);

            Map<String, Object> results = region.getAll(keys);

            Object actualBoolean = results.get(booleanKey);
            Object actualChar = results.get(charKey);
            Object actualShort = results.get(shortKey);
            Object actualLong = results.get(longKey);
            Object actualFloat = results.get(floatKey);
            Object actualDouble = results.get(doubleKey);

            assertInstanceOf(boolean[].class, actualBoolean);
            assertInstanceOf(char[].class, actualChar);
            assertInstanceOf(short[].class, actualShort);
            assertInstanceOf(long[].class, actualLong);
            assertInstanceOf(float[].class, actualFloat);
            assertInstanceOf(double[].class, actualDouble);

            assertArrayEquals(expectedBoolean, (boolean[]) actualBoolean);
            assertArrayEquals(expectedChar, (char[]) actualChar);
            assertArrayEquals(expectedShort, (short[]) actualShort);
            assertArrayEquals(expectedLong, (long[]) actualLong);
            assertArrayEquals(expectedFloat, (float[]) actualFloat);
            assertArrayEquals(expectedDouble, (double[]) actualDouble);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PRIMITIVE_ARRAY_FAMILY GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithIntArrayValuesShouldReturnIntArrays() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-int-array-1-" + suffix;
        String key2 = "it-getall-int-array-2-" + suffix;
        String key3 = "it-getall-int-array-3-" + suffix;

        int[] expected1 = new int[] {};
        int[] expected2 = new int[] {1, 42, -7};
        int[] expected3 = new int[] {
                1,
                42,
                -7,
                Integer.MAX_VALUE,
                Integer.MIN_VALUE
        };

        try {
            region.put(key1, expected1);
            region.put(key2, expected2);
            region.put(key3, expected3);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(int[].class, actual1);
            assertInstanceOf(int[].class, actual2);
            assertInstanceOf(int[].class, actual3);

            assertArrayEquals(expected1, (int[]) actual1);
            assertArrayEquals(expected2, (int[]) actual2);
            assertArrayEquals(expected3, (int[]) actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after INT_ARRAY GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void stringArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-string-array-value-" + suffix;

        String[] expected = new String[] {
                "one",
                null,
                "three"
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(String[].class, actual);
            assertArrayEquals(expected, (String[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void stringArrayListValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-string-array-list-value-" + suffix;

        ArrayList<String> expected = new ArrayList<>();
        expected.add("one");
        expected.add(null);
        expected.add("three");

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(ArrayList.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_ARRAY_LIST round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void stringHashMapValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-string-hash-map-value-" + suffix;

        LinkedHashMap<String, String> expected = new LinkedHashMap<>();
        expected.put("one", "value-1");
        expected.put("two", null);
        expected.put("three", "value-3");

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Map.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_HASH_MAP round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void emptyStringHashMapValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-empty-string-hash-map-value-" + suffix;

        LinkedHashMap<String, String> expected = new LinkedHashMap<>();

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Map.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after EMPTY STRING_HASH_MAP round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void stringObjectHashMapValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-string-object-hash-map-value-" + suffix;

        LinkedHashMap<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "rob");
        expected.put("age", Integer.valueOf(42));
        expected.put("active", Boolean.TRUE);
        expected.put("createdAt", new Date(1_000L));

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Map.class, actual);
            assertMapValuesEqual(expected, castMap(actual));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_OBJECT_HASH_MAP round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void stringObjectHashMapWithArrayValuesShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-string-object-hash-map-arrays-" + suffix;

        ArrayList<String> list = new ArrayList<>();
        list.add("one");
        list.add(null);
        list.add("three");

        LinkedHashMap<String, Object> expected = new LinkedHashMap<>();
        expected.put("payload", new byte[] {0x01, 0x02, 0x03, 0x04, 0x05});
        expected.put("booleanItems", new boolean[] {true, false, true});
        expected.put("charItems", new char[] {'A', 'Z', '0'});
        expected.put("shortItems", new short[] {1, 42, -7});
        expected.put("intItems", new int[] {1, 42, -7});
        expected.put("longItems", new long[] {1L, 42L, -7L});
        expected.put("floatItems", new float[] {1.0f, 7.25f, -7.25f});
        expected.put("doubleItems", new double[] {1.0d, 7.25d, -7.25d});
        expected.put("items", new String[] {"one", null, "three"});
        expected.put("list", list);

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Map.class, actual);
            assertMapValuesEqual(expected, castMap(actual));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_OBJECT_HASH_MAP array-values round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void complexStringObjectHashMapWithNestedUtilityValuesShouldRoundTripAsOpaqueJavaSerializedMap() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-complex-map-utilities-" + suffix;

        LinkedHashMap<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "complex-utility-map");
        expected.put("uuid", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        expected.put("bigInteger", new BigInteger("123456789012345678901234567890"));
        expected.put("bigDecimal", new BigDecimal("1234567890.123456789"));
        expected.put("status", DemoStatus.ACTIVE);
        expected.put("instant", Instant.parse("2026-05-13T20:37:37Z"));
        expected.put("localDate", LocalDate.of(2026, 5, 13));
        expected.put("localDateTime", LocalDateTime.of(2026, 5, 13, 20, 37, 37));

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Map.class, actual);
            assertMapValuesEqual(expected, castMap(actual));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after COMPLEX STRING_OBJECT_HASH_MAP utility-values round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void complexStringObjectHashMapWithNestedObjectArrayAndObjectArrayListShouldRoundTripAsOpaqueJavaSerializedMap() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-complex-map-object-containers-" + suffix;

        ArrayList<Object> objectList = new ArrayList<>();
        objectList.add("list-item");
        objectList.add(Integer.valueOf(42));
        objectList.add(Boolean.TRUE);
        objectList.add(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        LinkedHashMap<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "complex-object-container-map");
        expected.put("objectArray", new Object[] {
                "one",
                Integer.valueOf(42),
                Boolean.TRUE,
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        });
        expected.put("objectArrayList", objectList);

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Map.class, actual);
            assertMapValuesEqual(expected, castMap(actual));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after COMPLEX STRING_OBJECT_HASH_MAP object-container round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void complexStringObjectHashMapWithNestedSerializablePojoShouldRoundTripAsOpaqueJavaSerializedMap() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-complex-map-pojo-" + suffix;

        LinkedHashMap<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "complex-pojo-map");
        expected.put("profile", new CustomerProfile(
                "customer-nested-map-" + suffix,
                "Rob",
                42,
                true
        ));

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Map.class, actual);
            assertMapValuesEqual(expected, castMap(actual));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after COMPLEX STRING_OBJECT_HASH_MAP nested-pojo round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void complexStringObjectHashMapWithNestedWrapperAndUtilityArraysShouldRoundTripAsOpaqueJavaSerializedMap() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-complex-map-wrapper-utility-arrays-" + suffix;

        LinkedHashMap<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "complex-wrapper-utility-array-map");
        expected.put("integerArray", new Integer[] {1, 42, -7, null, Integer.MAX_VALUE, Integer.MIN_VALUE});
        expected.put("uuidArray", new UUID[] {
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000001")
        });
        expected.put("bigDecimalArray", new BigDecimal[] {
                new BigDecimal("1.00"),
                new BigDecimal("42.42"),
                null,
                new BigDecimal("-7.25"),
                new BigDecimal("1234567890.123456789")
        });
        expected.put("instantArray", new Instant[] {
                Instant.parse("2026-05-13T20:37:37Z"),
                null,
                Instant.EPOCH
        });
        expected.put("localDateArray", new LocalDate[] {
                LocalDate.of(2026, 5, 13),
                null,
                LocalDate.of(1970, 1, 1)
        });
        expected.put("localDateTimeArray", new LocalDateTime[] {
                LocalDateTime.of(2026, 5, 13, 20, 37, 37),
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0, 0)
        });

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Map.class, actual);
            assertMapValuesEqual(expected, castMap(actual));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after COMPLEX STRING_OBJECT_HASH_MAP nested-array round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void complexStringObjectHashMapWithMixedNestedOpaqueValuesShouldRoundTripAsOpaqueJavaSerializedMap() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-complex-map-mixed-" + suffix;

        LinkedHashMap<String, Object> expected = complexNestedOpaqueMap("mixed-" + suffix);

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Map.class, actual);
            assertMapValuesEqual(expected, castMap(actual));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after COMPLEX STRING_OBJECT_HASH_MAP mixed nested opaque round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithComplexNestedStringObjectHashMapValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String utilityKey = "it-putall-complex-map-utilities-" + suffix;
        String containerKey = "it-putall-complex-map-containers-" + suffix;
        String mixedKey = "it-putall-complex-map-mixed-" + suffix;

        LinkedHashMap<String, Object> utilityMap = new LinkedHashMap<>();
        utilityMap.put("name", "putall-utility-map");
        utilityMap.put("uuid", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        utilityMap.put("bigInteger", new BigInteger("123456789012345678901234567890"));
        utilityMap.put("bigDecimal", new BigDecimal("1234567890.123456789"));
        utilityMap.put("status", DemoStatus.ACTIVE);
        utilityMap.put("instant", Instant.parse("2026-05-13T20:37:37Z"));

        ArrayList<Object> objectList = new ArrayList<>();
        objectList.add("list-item");
        objectList.add(Integer.valueOf(42));
        objectList.add(Boolean.TRUE);
        objectList.add(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        LinkedHashMap<String, Object> containerMap = new LinkedHashMap<>();
        containerMap.put("name", "putall-container-map");
        containerMap.put("objectArray", new Object[] {"one", Integer.valueOf(42), Boolean.TRUE});
        containerMap.put("objectArrayList", objectList);

        LinkedHashMap<String, Object> mixedMap = complexNestedOpaqueMap("putall-mixed-" + suffix);

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(utilityKey, utilityMap);
        entries.put(containerKey, containerMap);
        entries.put(mixedKey, mixedMap);

        try {
            region.putAll(entries);

            assertMapValuesEqual(utilityMap, castMap(region.get(utilityKey)));
            assertMapValuesEqual(containerMap, castMap(region.get(containerKey)));
            assertMapValuesEqual(mixedMap, castMap(region.get(mixedKey)));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after COMPLEX STRING_OBJECT_HASH_MAP PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithComplexNestedStringObjectHashMapValuesShouldReturnMaps() {
        String suffix = UUID.randomUUID().toString();

        String utilityKey = "it-getall-complex-map-utilities-" + suffix;
        String arrayKey = "it-getall-complex-map-arrays-" + suffix;
        String mixedKey = "it-getall-complex-map-mixed-" + suffix;

        LinkedHashMap<String, Object> utilityMap = new LinkedHashMap<>();
        utilityMap.put("name", "getall-utility-map");
        utilityMap.put("uuid", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        utilityMap.put("bigInteger", new BigInteger("123456789012345678901234567890"));
        utilityMap.put("bigDecimal", new BigDecimal("1234567890.123456789"));
        utilityMap.put("status", DemoStatus.ACTIVE);
        utilityMap.put("localDate", LocalDate.of(2026, 5, 13));

        LinkedHashMap<String, Object> arrayMap = new LinkedHashMap<>();
        arrayMap.put("name", "getall-array-map");
        arrayMap.put("integerArray", new Integer[] {1, 42, null, -7});
        arrayMap.put("uuidArray", new UUID[] {
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000001")
        });

        LinkedHashMap<String, Object> mixedMap = complexNestedOpaqueMap("getall-mixed-" + suffix);

        try {
            region.put(utilityKey, utilityMap);
            region.put(arrayKey, arrayMap);
            region.put(mixedKey, mixedMap);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(utilityKey);
            keys.add(arrayKey);
            keys.add(mixedKey);

            Map<String, Object> results = region.getAll(keys);

            assertMapValuesEqual(utilityMap, castMap(results.get(utilityKey)));
            assertMapValuesEqual(arrayMap, castMap(results.get(arrayKey)));
            assertMapValuesEqual(mixedMap, castMap(results.get(mixedKey)));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after COMPLEX STRING_OBJECT_HASH_MAP GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }


    @Test
    void serializablePojoValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-serializable-pojo-value-" + suffix;

        CustomerProfile expected = new CustomerProfile(
                "customer-1-" + suffix,
                "Rob",
                42,
                true
        );

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(CustomerProfile.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after JAVA_SERIALIZED_OBJECT round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void serializablePojoWithNullFieldShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-serializable-pojo-null-field-" + suffix;

        CustomerProfile expected = new CustomerProfile(
                "customer-2-" + suffix,
                null,
                43,
                false
        );

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(CustomerProfile.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after JAVA_SERIALIZED_OBJECT null-field round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void serializablePojoWithDateAndByteArrayShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-serializable-pojo-extras-" + suffix;

        CustomerProfileWithExtras expected = new CustomerProfileWithExtras(
                "customer-3-" + suffix,
                "Rob",
                42,
                true,
                new Date(1_000L),
                new byte[] {0x01, 0x02, 0x03, 0x04, 0x05}
        );

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(CustomerProfileWithExtras.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after JAVA_SERIALIZED_OBJECT extras round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void serializablePojoWithNestedMapShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-serializable-pojo-nested-map-" + suffix;

        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tier", "gold");
        attributes.put("score", Integer.valueOf(9001));
        attributes.put("active", Boolean.TRUE);
        attributes.put("lastSeen", new Date(1_000L));

        CustomerProfileWithAttributes expected = new CustomerProfileWithAttributes(
                "customer-4-" + suffix,
                "Rob",
                attributes
        );

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(CustomerProfileWithAttributes.class, actual);
            assertEquals(expected, actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after JAVA_SERIALIZED_OBJECT nested-map round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void objectArrayValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-object-array-value-" + suffix;

        Object[] expected = new Object[] {
                "one",
                Integer.valueOf(42),
                Boolean.TRUE
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Object[].class, actual);
            assertObjectArrayDeepEquals(expected, (Object[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after OBJECT_ARRAY round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void objectArrayWithNullElementShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-object-array-null-" + suffix;

        Object[] expected = new Object[] {
                "one",
                null,
                "three"
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Object[].class, actual);
            assertObjectArrayDeepEquals(expected, (Object[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after OBJECT_ARRAY null-element round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void objectArrayWithNestedValuesShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-object-array-nested-" + suffix;

        ArrayList<String> list = new ArrayList<>();
        list.add("one");
        list.add(null);
        list.add("three");

        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("name", "rob");
        map.put("age", Integer.valueOf(42));
        map.put("active", Boolean.TRUE);
        map.put("createdAt", new Date(1_000L));

        CustomerProfile profile = new CustomerProfile(
                "customer-object-array-" + suffix,
                "Rob",
                42,
                true
        );

        Object[] expected = new Object[] {
                "string-value",
                Character.valueOf('A'),
                Byte.valueOf((byte) 7),
                new byte[] {0x01, 0x02, 0x03},
                new String[] {"one", null, "three"},
                list,
                map,
                profile,
                Short.valueOf((short) 7),
                Integer.valueOf(42),
                Boolean.TRUE,
                Long.valueOf(9_876_543_210L),
                Float.valueOf(7.25f),
                Double.valueOf(7.25d),
                new Date(1_000L)
        };

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(Object[].class, actual);
            assertObjectArrayDeepEquals(expected, (Object[]) actual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after OBJECT_ARRAY nested-values round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithObjectArrayValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-object-array-1-" + suffix;
        String key2 = "it-putall-object-array-2-" + suffix;
        String key3 = "it-putall-object-array-3-" + suffix;

        Object[] expected1 = new Object[] {
                "one",
                Integer.valueOf(42),
                Boolean.TRUE
        };

        Object[] expected2 = new Object[] {
                "one",
                null,
                "three"
        };

        Object[] expected3 = new Object[] {
                "string-value",
                Character.valueOf('A'),
                Byte.valueOf((byte) 7),
                Short.valueOf((short) 7),
                Integer.valueOf(42),
                Boolean.TRUE,
                Long.valueOf(9_876_543_210L),
                Float.valueOf(7.25f),
                Double.valueOf(7.25d),
                new Date(1_000L)
        };

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, expected1);
        entries.put(key2, expected2);
        entries.put(key3, expected3);

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(Object[].class, actual1);
            assertInstanceOf(Object[].class, actual2);
            assertInstanceOf(Object[].class, actual3);

            assertObjectArrayDeepEquals(expected1, (Object[]) actual1);
            assertObjectArrayDeepEquals(expected2, (Object[]) actual2);
            assertObjectArrayDeepEquals(expected3, (Object[]) actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after OBJECT_ARRAY PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithObjectArrayValuesShouldReturnObjectArrays() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-object-array-1-" + suffix;
        String key2 = "it-getall-object-array-2-" + suffix;
        String key3 = "it-getall-object-array-3-" + suffix;

        Object[] expected1 = new Object[] {
                "one",
                Integer.valueOf(42),
                Boolean.TRUE
        };

        Object[] expected2 = new Object[] {
                "one",
                null,
                "three"
        };

        Object[] expected3 = new Object[] {
                "string-value",
                Character.valueOf('A'),
                Byte.valueOf((byte) 7),
                Short.valueOf((short) 7),
                Integer.valueOf(42),
                Boolean.TRUE,
                Long.valueOf(9_876_543_210L),
                Float.valueOf(7.25f),
                Double.valueOf(7.25d),
                new Date(1_000L)
        };

        try {
            region.put(key1, expected1);
            region.put(key2, expected2);
            region.put(key3, expected3);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(Object[].class, actual1);
            assertInstanceOf(Object[].class, actual2);
            assertInstanceOf(Object[].class, actual3);

            assertObjectArrayDeepEquals(expected1, (Object[]) actual1);
            assertObjectArrayDeepEquals(expected2, (Object[]) actual2);
            assertObjectArrayDeepEquals(expected3, (Object[]) actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after OBJECT_ARRAY GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void objectArrayListValueShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-object-array-list-value-" + suffix;

        ArrayList<Object> expected = new ArrayList<>();
        expected.add("one");
        expected.add(Integer.valueOf(42));
        expected.add(Boolean.TRUE);

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(ArrayList.class, actual);
            assertObjectArrayListDeepEquals(expected, castArrayList(actual));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after OBJECT_ARRAY_LIST round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void objectArrayListWithNullElementShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-object-array-list-null-" + suffix;

        ArrayList<Object> expected = new ArrayList<>();
        expected.add("one");
        expected.add(null);
        expected.add("three");

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(ArrayList.class, actual);
            assertObjectArrayListDeepEquals(expected, castArrayList(actual));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after OBJECT_ARRAY_LIST null-element round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void objectArrayListWithNestedValuesShouldRoundTripThroughShimAndCouchbase() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-object-array-list-nested-" + suffix;

        ArrayList<String> nestedStringList = new ArrayList<>();
        nestedStringList.add("one");
        nestedStringList.add(null);
        nestedStringList.add("three");

        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("name", "rob");
        map.put("age", Integer.valueOf(42));
        map.put("active", Boolean.TRUE);
        map.put("createdAt", new Date(1_000L));

        CustomerProfile profile = new CustomerProfile(
                "customer-object-array-list-" + suffix,
                "Rob",
                42,
                true
        );

        ArrayList<Object> expected = new ArrayList<>();
        expected.add("string-value");
        expected.add(Character.valueOf('A'));
        expected.add(Byte.valueOf((byte) 7));
        expected.add(new byte[] {0x01, 0x02, 0x03});
        expected.add(new String[] {"one", null, "three"});
        expected.add(new Object[] {"one", Integer.valueOf(42), Boolean.TRUE});
        expected.add(nestedStringList);
        expected.add(map);
        expected.add(profile);
        expected.add(Short.valueOf((short) 7));
        expected.add(Integer.valueOf(42));
        expected.add(Boolean.TRUE);
        expected.add(Long.valueOf(9_876_543_210L));
        expected.add(Float.valueOf(7.25f));
        expected.add(Double.valueOf(7.25d));
        expected.add(new Date(1_000L));

        try {
            region.put(key, expected);

            Object actual = region.get(key);

            assertInstanceOf(ArrayList.class, actual);
            assertObjectArrayListDeepEquals(expected, castArrayList(actual));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after OBJECT_ARRAY_LIST nested-values round-trip failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithObjectArrayListValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-object-array-list-1-" + suffix;
        String key2 = "it-putall-object-array-list-2-" + suffix;
        String key3 = "it-putall-object-array-list-3-" + suffix;

        ArrayList<Object> expected1 = new ArrayList<>();
        expected1.add("one");
        expected1.add(Integer.valueOf(42));
        expected1.add(Boolean.TRUE);

        ArrayList<Object> expected2 = new ArrayList<>();
        expected2.add("one");
        expected2.add(null);
        expected2.add("three");

        ArrayList<Object> expected3 = new ArrayList<>();
        expected3.add("string-value");
        expected3.add(Character.valueOf('A'));
        expected3.add(Byte.valueOf((byte) 7));
        expected3.add(Short.valueOf((short) 7));
        expected3.add(Integer.valueOf(42));
        expected3.add(Boolean.TRUE);
        expected3.add(Long.valueOf(9_876_543_210L));
        expected3.add(Float.valueOf(7.25f));
        expected3.add(Double.valueOf(7.25d));
        expected3.add(new Date(1_000L));

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, expected1);
        entries.put(key2, expected2);
        entries.put(key3, expected3);

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(ArrayList.class, actual1);
            assertInstanceOf(ArrayList.class, actual2);
            assertInstanceOf(ArrayList.class, actual3);

            assertObjectArrayListDeepEquals(expected1, castArrayList(actual1));
            assertObjectArrayListDeepEquals(expected2, castArrayList(actual2));
            assertObjectArrayListDeepEquals(expected3, castArrayList(actual3));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after OBJECT_ARRAY_LIST PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithObjectArrayListValuesShouldReturnArrayLists() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-object-array-list-1-" + suffix;
        String key2 = "it-getall-object-array-list-2-" + suffix;
        String key3 = "it-getall-object-array-list-3-" + suffix;

        ArrayList<Object> expected1 = new ArrayList<>();
        expected1.add("one");
        expected1.add(Integer.valueOf(42));
        expected1.add(Boolean.TRUE);

        ArrayList<Object> expected2 = new ArrayList<>();
        expected2.add("one");
        expected2.add(null);
        expected2.add("three");

        ArrayList<Object> expected3 = new ArrayList<>();
        expected3.add("string-value");
        expected3.add(Character.valueOf('A'));
        expected3.add(Byte.valueOf((byte) 7));
        expected3.add(Short.valueOf((short) 7));
        expected3.add(Integer.valueOf(42));
        expected3.add(Boolean.TRUE);
        expected3.add(Long.valueOf(9_876_543_210L));
        expected3.add(Float.valueOf(7.25f));
        expected3.add(Double.valueOf(7.25d));
        expected3.add(new Date(1_000L));

        try {
            region.put(key1, expected1);
            region.put(key2, expected2);
            region.put(key3, expected3);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(ArrayList.class, actual1);
            assertInstanceOf(ArrayList.class, actual2);
            assertInstanceOf(ArrayList.class, actual3);

            assertObjectArrayListDeepEquals(expected1, castArrayList(actual1));
            assertObjectArrayListDeepEquals(expected2, castArrayList(actual2));
            assertObjectArrayListDeepEquals(expected3, castArrayList(actual3));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after OBJECT_ARRAY_LIST GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithDateValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-date-1-" + suffix;
        String key2 = "it-putall-date-2-" + suffix;
        String key3 = "it-putall-date-3-" + suffix;

        Date expected1 = new Date(0L);
        Date expected2 = new Date(1_000L);
        Date expected3 = new Date(1_778_265_266_000L);

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, expected1);
        entries.put(key2, expected2);
        entries.put(key3, expected3);

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(Date.class, actual1);
            assertInstanceOf(Date.class, actual2);
            assertInstanceOf(Date.class, actual3);

            assertEquals(expected1, actual1);
            assertEquals(expected2, actual2);
            assertEquals(expected3, actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after DATE PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithByteArrayValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-byte-array-1-" + suffix;
        String key2 = "it-putall-byte-array-2-" + suffix;
        String key3 = "it-putall-byte-array-3-" + suffix;

        byte[] expected1 = new byte[] {};
        byte[] expected2 = new byte[] {0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] expected3 = new byte[] {0x00, 0x01, 0x7f, (byte) 0x80, (byte) 0xff};

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, expected1);
        entries.put(key2, expected2);
        entries.put(key3, expected3);

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(byte[].class, actual1);
            assertInstanceOf(byte[].class, actual2);
            assertInstanceOf(byte[].class, actual3);

            assertArrayEquals(expected1, (byte[]) actual1);
            assertArrayEquals(expected2, (byte[]) actual2);
            assertArrayEquals(expected3, (byte[]) actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BYTE_ARRAY PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithStringArrayValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-string-array-1-" + suffix;
        String key2 = "it-putall-string-array-2-" + suffix;
        String key3 = "it-putall-string-array-3-" + suffix;

        String[] expected1 = new String[] {};
        String[] expected2 = new String[] {"one", "two", "three"};
        String[] expected3 = new String[] {"one", null, "three"};

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, expected1);
        entries.put(key2, expected2);
        entries.put(key3, expected3);

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(String[].class, actual1);
            assertInstanceOf(String[].class, actual2);
            assertInstanceOf(String[].class, actual3);

            assertArrayEquals(expected1, (String[]) actual1);
            assertArrayEquals(expected2, (String[]) actual2);
            assertArrayEquals(expected3, (String[]) actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_ARRAY PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithStringArrayListValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-string-array-list-1-" + suffix;
        String key2 = "it-putall-string-array-list-2-" + suffix;
        String key3 = "it-putall-string-array-list-3-" + suffix;

        ArrayList<String> expected1 = new ArrayList<>();

        ArrayList<String> expected2 = new ArrayList<>();
        expected2.add("one");
        expected2.add("two");
        expected2.add("three");

        ArrayList<String> expected3 = new ArrayList<>();
        expected3.add("one");
        expected3.add(null);
        expected3.add("three");

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, expected1);
        entries.put(key2, expected2);
        entries.put(key3, expected3);

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(ArrayList.class, actual1);
            assertInstanceOf(ArrayList.class, actual2);
            assertInstanceOf(ArrayList.class, actual3);

            assertEquals(expected1, actual1);
            assertEquals(expected2, actual2);
            assertEquals(expected3, actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_ARRAY_LIST PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithStringHashMapValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-string-hash-map-1-" + suffix;
        String key2 = "it-putall-string-hash-map-2-" + suffix;
        String key3 = "it-putall-string-hash-map-3-" + suffix;

        LinkedHashMap<String, String> expected1 = new LinkedHashMap<>();

        LinkedHashMap<String, String> expected2 = new LinkedHashMap<>();
        expected2.put("one", "value-1");
        expected2.put("two", "value-2");
        expected2.put("three", "value-3");

        LinkedHashMap<String, String> expected3 = new LinkedHashMap<>();
        expected3.put("one", "value-1");
        expected3.put("two", null);
        expected3.put("three", "value-3");

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, expected1);
        entries.put(key2, expected2);
        entries.put(key3, expected3);

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(Map.class, actual1);
            assertInstanceOf(Map.class, actual2);
            assertInstanceOf(Map.class, actual3);

            assertEquals(expected1, actual1);
            assertEquals(expected2, actual2);
            assertEquals(expected3, actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_HASH_MAP PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithStringObjectHashMapValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-string-object-hash-map-1-" + suffix;
        String key2 = "it-putall-string-object-hash-map-2-" + suffix;
        String key3 = "it-putall-string-object-hash-map-3-" + suffix;

        LinkedHashMap<String, Object> expected1 = new LinkedHashMap<>();
        expected1.put("name", "rob");
        expected1.put("age", Integer.valueOf(42));
        expected1.put("active", Boolean.TRUE);

        LinkedHashMap<String, Object> expected2 = new LinkedHashMap<>();
        expected2.put("name", "rob");
        expected2.put("middleName", null);
        expected2.put("createdAt", new Date(1_000L));

        ArrayList<String> list = new ArrayList<>();
        list.add("one");
        list.add(null);
        list.add("three");

        LinkedHashMap<String, Object> expected3 = new LinkedHashMap<>();
        expected3.put("payload", new byte[] {0x01, 0x02, 0x03, 0x04, 0x05});
        expected3.put("items", new String[] {"one", null, "three"});
        expected3.put("list", list);

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, expected1);
        entries.put(key2, expected2);
        entries.put(key3, expected3);

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(Map.class, actual1);
            assertInstanceOf(Map.class, actual2);
            assertInstanceOf(Map.class, actual3);

            assertMapValuesEqual(expected1, castMap(actual1));
            assertMapValuesEqual(expected2, castMap(actual2));
            assertMapValuesEqual(expected3, castMap(actual3));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_OBJECT_HASH_MAP PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void putAllWithSerializablePojoValuesShouldPersistAllEntriesAndBeReadableByGet() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-serializable-pojo-1-" + suffix;
        String key2 = "it-putall-serializable-pojo-2-" + suffix;
        String key3 = "it-putall-serializable-pojo-3-" + suffix;

        CustomerProfile expected1 = new CustomerProfile(
                "customer-1-" + suffix,
                "Rob",
                42,
                true
        );

        CustomerProfile expected2 = new CustomerProfile(
                "customer-2-" + suffix,
                null,
                43,
                false
        );

        CustomerProfileWithExtras expected3 = new CustomerProfileWithExtras(
                "customer-3-" + suffix,
                "Rob",
                42,
                true,
                new Date(1_000L),
                new byte[] {0x01, 0x02, 0x03, 0x04, 0x05}
        );

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, expected1);
        entries.put(key2, expected2);
        entries.put(key3, expected3);

        try {
            region.putAll(entries);

            Object actual1 = region.get(key1);
            Object actual2 = region.get(key2);
            Object actual3 = region.get(key3);

            assertInstanceOf(CustomerProfile.class, actual1);
            assertInstanceOf(CustomerProfile.class, actual2);
            assertInstanceOf(CustomerProfileWithExtras.class, actual3);

            assertEquals(expected1, actual1);
            assertEquals(expected2, actual2);
            assertEquals(expected3, actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after JAVA_SERIALIZED_OBJECT PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithDateValuesShouldReturnDates() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-date-1-" + suffix;
        String key2 = "it-getall-date-2-" + suffix;
        String key3 = "it-getall-date-3-" + suffix;

        Date expected1 = new Date(0L);
        Date expected2 = new Date(1_000L);
        Date expected3 = new Date(1_778_265_266_000L);

        try {
            region.put(key1, expected1);
            region.put(key2, expected2);
            region.put(key3, expected3);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(Date.class, actual1);
            assertInstanceOf(Date.class, actual2);
            assertInstanceOf(Date.class, actual3);

            assertEquals(expected1, actual1);
            assertEquals(expected2, actual2);
            assertEquals(expected3, actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after DATE GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithByteArrayValuesShouldReturnByteArrays() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-byte-array-1-" + suffix;
        String key2 = "it-getall-byte-array-2-" + suffix;
        String key3 = "it-getall-byte-array-3-" + suffix;

        byte[] expected1 = new byte[] {};
        byte[] expected2 = new byte[] {0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] expected3 = new byte[] {0x00, 0x01, 0x7f, (byte) 0x80, (byte) 0xff};

        try {
            region.put(key1, expected1);
            region.put(key2, expected2);
            region.put(key3, expected3);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(byte[].class, actual1);
            assertInstanceOf(byte[].class, actual2);
            assertInstanceOf(byte[].class, actual3);

            assertArrayEquals(expected1, (byte[]) actual1);
            assertArrayEquals(expected2, (byte[]) actual2);
            assertArrayEquals(expected3, (byte[]) actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after BYTE_ARRAY GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithStringArrayValuesShouldReturnStringArrays() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-string-array-1-" + suffix;
        String key2 = "it-getall-string-array-2-" + suffix;
        String key3 = "it-getall-string-array-3-" + suffix;

        String[] expected1 = new String[] {};
        String[] expected2 = new String[] {"one", "two", "three"};
        String[] expected3 = new String[] {"one", null, "three"};

        try {
            region.put(key1, expected1);
            region.put(key2, expected2);
            region.put(key3, expected3);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(String[].class, actual1);
            assertInstanceOf(String[].class, actual2);
            assertInstanceOf(String[].class, actual3);

            assertArrayEquals(expected1, (String[]) actual1);
            assertArrayEquals(expected2, (String[]) actual2);
            assertArrayEquals(expected3, (String[]) actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_ARRAY GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithStringArrayListValuesShouldReturnArrayLists() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-string-array-list-1-" + suffix;
        String key2 = "it-getall-string-array-list-2-" + suffix;
        String key3 = "it-getall-string-array-list-3-" + suffix;

        ArrayList<String> expected1 = new ArrayList<>();

        ArrayList<String> expected2 = new ArrayList<>();
        expected2.add("one");
        expected2.add("two");
        expected2.add("three");

        ArrayList<String> expected3 = new ArrayList<>();
        expected3.add("one");
        expected3.add(null);
        expected3.add("three");

        try {
            region.put(key1, expected1);
            region.put(key2, expected2);
            region.put(key3, expected3);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(ArrayList.class, actual1);
            assertInstanceOf(ArrayList.class, actual2);
            assertInstanceOf(ArrayList.class, actual3);

            assertEquals(expected1, actual1);
            assertEquals(expected2, actual2);
            assertEquals(expected3, actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_ARRAY_LIST GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithStringHashMapValuesShouldReturnLinkedHashMaps() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-string-hash-map-1-" + suffix;
        String key2 = "it-getall-string-hash-map-2-" + suffix;
        String key3 = "it-getall-string-hash-map-3-" + suffix;

        LinkedHashMap<String, String> expected1 = new LinkedHashMap<>();

        LinkedHashMap<String, String> expected2 = new LinkedHashMap<>();
        expected2.put("one", "value-1");
        expected2.put("two", "value-2");
        expected2.put("three", "value-3");

        LinkedHashMap<String, String> expected3 = new LinkedHashMap<>();
        expected3.put("one", "value-1");
        expected3.put("two", null);
        expected3.put("three", "value-3");

        try {
            region.put(key1, expected1);
            region.put(key2, expected2);
            region.put(key3, expected3);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(Map.class, actual1);
            assertInstanceOf(Map.class, actual2);
            assertInstanceOf(Map.class, actual3);

            assertEquals(expected1, actual1);
            assertEquals(expected2, actual2);
            assertEquals(expected3, actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_HASH_MAP GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithStringObjectHashMapValuesShouldReturnMaps() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-string-object-hash-map-1-" + suffix;
        String key2 = "it-getall-string-object-hash-map-2-" + suffix;
        String key3 = "it-getall-string-object-hash-map-3-" + suffix;

        LinkedHashMap<String, Object> expected1 = new LinkedHashMap<>();
        expected1.put("name", "rob");
        expected1.put("age", Integer.valueOf(42));
        expected1.put("active", Boolean.TRUE);

        LinkedHashMap<String, Object> expected2 = new LinkedHashMap<>();
        expected2.put("name", "rob");
        expected2.put("middleName", null);
        expected2.put("createdAt", new Date(1_000L));

        ArrayList<String> list = new ArrayList<>();
        list.add("one");
        list.add(null);
        list.add("three");

        LinkedHashMap<String, Object> expected3 = new LinkedHashMap<>();
        expected3.put("payload", new byte[] {0x01, 0x02, 0x03, 0x04, 0x05});
        expected3.put("items", new String[] {"one", null, "three"});
        expected3.put("list", list);

        try {
            region.put(key1, expected1);
            region.put(key2, expected2);
            region.put(key3, expected3);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(Map.class, actual1);
            assertInstanceOf(Map.class, actual2);
            assertInstanceOf(Map.class, actual3);

            assertMapValuesEqual(expected1, castMap(actual1));
            assertMapValuesEqual(expected2, castMap(actual2));
            assertMapValuesEqual(expected3, castMap(actual3));
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after STRING_OBJECT_HASH_MAP GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void getAllWithSerializablePojoValuesShouldReturnSerializablePojos() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-serializable-pojo-1-" + suffix;
        String key2 = "it-getall-serializable-pojo-2-" + suffix;
        String key3 = "it-getall-serializable-pojo-3-" + suffix;

        CustomerProfile expected1 = new CustomerProfile(
                "customer-1-" + suffix,
                "Rob",
                42,
                true
        );

        CustomerProfile expected2 = new CustomerProfile(
                "customer-2-" + suffix,
                null,
                43,
                false
        );

        CustomerProfileWithExtras expected3 = new CustomerProfileWithExtras(
                "customer-3-" + suffix,
                "Rob",
                42,
                true,
                new Date(1_000L),
                new byte[] {0x01, 0x02, 0x03, 0x04, 0x05}
        );

        try {
            region.put(key1, expected1);
            region.put(key2, expected2);
            region.put(key3, expected3);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(key1);
            keys.add(key2);
            keys.add(key3);

            Map<String, Object> results = region.getAll(keys);

            Object actual1 = results.get(key1);
            Object actual2 = results.get(key2);
            Object actual3 = results.get(key3);

            assertInstanceOf(CustomerProfile.class, actual1);
            assertInstanceOf(CustomerProfile.class, actual2);
            assertInstanceOf(CustomerProfileWithExtras.class, actual3);

            assertEquals(expected1, actual1);
            assertEquals(expected2, actual2);
            assertEquals(expected3, actual3);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after JAVA_SERIALIZED_OBJECT GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    @Test
    void mixedStringCharacterBytePrimitiveArraysStringArrayStringArrayListStringHashMapStringObjectHashMapSerializablePojoObjectArrayObjectArrayListShortIntegerBooleanLongFloatDoubleDatePutAllAndGetAllShouldPreserveTypes() {
        String suffix = UUID.randomUUID().toString();

        String stringKey = "it-mixed-primitive-arrays-string-" + suffix;
        String characterKey = "it-mixed-primitive-arrays-character-" + suffix;
        String byteKey = "it-mixed-primitive-arrays-byte-" + suffix;
        String byteArrayKey = "it-mixed-primitive-arrays-byte-array-" + suffix;
        String booleanArrayKey = "it-mixed-primitive-arrays-boolean-array-" + suffix;
        String charArrayKey = "it-mixed-primitive-arrays-char-array-" + suffix;
        String shortArrayKey = "it-mixed-primitive-arrays-short-array-" + suffix;
        String intArrayKey = "it-mixed-primitive-arrays-int-array-" + suffix;
        String longArrayKey = "it-mixed-primitive-arrays-long-array-" + suffix;
        String floatArrayKey = "it-mixed-primitive-arrays-float-array-" + suffix;
        String doubleArrayKey = "it-mixed-primitive-arrays-double-array-" + suffix;
        String stringArrayKey = "it-mixed-primitive-arrays-string-array-" + suffix;
        String stringArrayListKey = "it-mixed-primitive-arrays-string-array-list-" + suffix;
        String stringHashMapKey = "it-mixed-primitive-arrays-string-hash-map-" + suffix;
        String stringObjectHashMapKey = "it-mixed-primitive-arrays-string-object-hash-map-" + suffix;
        String serializablePojoKey = "it-mixed-primitive-arrays-serializable-pojo-" + suffix;
        String objectArrayKey = "it-mixed-primitive-arrays-object-array-" + suffix;
        String objectArrayListKey = "it-mixed-primitive-arrays-object-array-list-" + suffix;
        String shortKey = "it-mixed-primitive-arrays-short-" + suffix;
        String integerKey = "it-mixed-primitive-arrays-integer-" + suffix;
        String booleanTrueKey = "it-mixed-primitive-arrays-bool-true-" + suffix;
        String booleanFalseKey = "it-mixed-primitive-arrays-bool-false-" + suffix;
        String longPositiveKey = "it-mixed-primitive-arrays-long-positive-" + suffix;
        String longNegativeKey = "it-mixed-primitive-arrays-long-negative-" + suffix;
        String floatPositiveKey = "it-mixed-primitive-arrays-float-positive-" + suffix;
        String floatNegativeKey = "it-mixed-primitive-arrays-float-negative-" + suffix;
        String doublePositiveKey = "it-mixed-primitive-arrays-double-positive-" + suffix;
        String doubleNegativeKey = "it-mixed-primitive-arrays-double-negative-" + suffix;
        String dateKey = "it-mixed-primitive-arrays-date-" + suffix;

        byte[] expectedByteArray = new byte[] {0x01, 0x02, 0x03, 0x04, 0x05};
        boolean[] expectedBooleanArray = new boolean[] {true, false, true};
        char[] expectedCharArray = new char[] {'A', 'Z', '0'};
        short[] expectedShortArray = new short[] {1, 42, -7};
        int[] expectedIntArray = new int[] {1, 42, -7, Integer.MAX_VALUE, Integer.MIN_VALUE};
        long[] expectedLongArray = new long[] {1L, 42L, -7L};
        float[] expectedFloatArray = new float[] {1.0f, 7.25f, -7.25f};
        double[] expectedDoubleArray = new double[] {1.0d, 7.25d, -7.25d};
        String[] expectedStringArray = new String[] {"one", null, "three"};

        ArrayList<String> expectedStringArrayList = new ArrayList<>();
        expectedStringArrayList.add("one");
        expectedStringArrayList.add(null);
        expectedStringArrayList.add("three");

        LinkedHashMap<String, String> expectedStringHashMap = new LinkedHashMap<>();
        expectedStringHashMap.put("one", "value-1");
        expectedStringHashMap.put("two", null);
        expectedStringHashMap.put("three", "value-3");

        LinkedHashMap<String, Object> expectedStringObjectHashMap = new LinkedHashMap<>();
        expectedStringObjectHashMap.put("name", "rob");
        expectedStringObjectHashMap.put("age", Integer.valueOf(42));
        expectedStringObjectHashMap.put("active", Boolean.TRUE);
        expectedStringObjectHashMap.put("createdAt", new Date(1_000L));
        expectedStringObjectHashMap.put("payload", new byte[] {0x01, 0x02, 0x03});
        expectedStringObjectHashMap.put("booleanItems", new boolean[] {true, false, true});
        expectedStringObjectHashMap.put("charItems", new char[] {'A', 'Z', '0'});
        expectedStringObjectHashMap.put("shortItems", new short[] {1, 42, -7});
        expectedStringObjectHashMap.put("intItems", new int[] {1, 42, -7});
        expectedStringObjectHashMap.put("longItems", new long[] {1L, 42L, -7L});
        expectedStringObjectHashMap.put("floatItems", new float[] {1.0f, 7.25f, -7.25f});
        expectedStringObjectHashMap.put("doubleItems", new double[] {1.0d, 7.25d, -7.25d});
        expectedStringObjectHashMap.put("items", new String[] {"one", null, "three"});
        expectedStringObjectHashMap.put("list", expectedStringArrayList);

        CustomerProfileWithExtras expectedSerializablePojo = new CustomerProfileWithExtras(
                "customer-mixed-" + suffix,
                "Rob",
                42,
                true,
                new Date(1_000L),
                new byte[] {0x01, 0x02, 0x03, 0x04, 0x05}
        );

        Object[] expectedObjectArray = new Object[] {
                "object-array-value",
                Integer.valueOf(42),
                Boolean.TRUE,
                new Date(1_000L)
        };

        ArrayList<Object> expectedObjectArrayList = new ArrayList<>();
        expectedObjectArrayList.add("object-array-list-value");
        expectedObjectArrayList.add(Integer.valueOf(42));
        expectedObjectArrayList.add(Boolean.TRUE);
        expectedObjectArrayList.add(new Date(1_000L));

        Date expectedDate = new Date(1_000L);

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(stringKey, "string-value-" + suffix);
        entries.put(characterKey, Character.valueOf('A'));
        entries.put(byteKey, Byte.valueOf((byte) 7));
        entries.put(byteArrayKey, expectedByteArray);
        entries.put(booleanArrayKey, expectedBooleanArray);
        entries.put(charArrayKey, expectedCharArray);
        entries.put(shortArrayKey, expectedShortArray);
        entries.put(intArrayKey, expectedIntArray);
        entries.put(longArrayKey, expectedLongArray);
        entries.put(floatArrayKey, expectedFloatArray);
        entries.put(doubleArrayKey, expectedDoubleArray);
        entries.put(stringArrayKey, expectedStringArray);
        entries.put(stringArrayListKey, expectedStringArrayList);
        entries.put(stringHashMapKey, expectedStringHashMap);
        entries.put(stringObjectHashMapKey, expectedStringObjectHashMap);
        entries.put(serializablePojoKey, expectedSerializablePojo);
        entries.put(objectArrayKey, expectedObjectArray);
        entries.put(objectArrayListKey, expectedObjectArrayList);
        entries.put(shortKey, Short.valueOf((short) 77));
        entries.put(integerKey, Integer.valueOf(12_012));
        entries.put(booleanTrueKey, Boolean.TRUE);
        entries.put(booleanFalseKey, Boolean.FALSE);
        entries.put(longPositiveKey, Long.valueOf(9_876_543_210L));
        entries.put(longNegativeKey, Long.valueOf(-9_876_543_210L));
        entries.put(floatPositiveKey, Float.valueOf(7.25f));
        entries.put(floatNegativeKey, Float.valueOf(-7.25f));
        entries.put(doublePositiveKey, Double.valueOf(7.25d));
        entries.put(doubleNegativeKey, Double.valueOf(-7.25d));
        entries.put(dateKey, expectedDate);

        try {
            region.putAll(entries);

            Set<String> keys = new LinkedHashSet<>();
            keys.add(stringKey);
            keys.add(characterKey);
            keys.add(byteKey);
            keys.add(byteArrayKey);
            keys.add(booleanArrayKey);
            keys.add(charArrayKey);
            keys.add(shortArrayKey);
            keys.add(intArrayKey);
            keys.add(longArrayKey);
            keys.add(floatArrayKey);
            keys.add(doubleArrayKey);
            keys.add(stringArrayKey);
            keys.add(stringArrayListKey);
            keys.add(stringHashMapKey);
            keys.add(stringObjectHashMapKey);
            keys.add(serializablePojoKey);
            keys.add(objectArrayKey);
            keys.add(objectArrayListKey);
            keys.add(shortKey);
            keys.add(integerKey);
            keys.add(booleanTrueKey);
            keys.add(booleanFalseKey);
            keys.add(longPositiveKey);
            keys.add(longNegativeKey);
            keys.add(floatPositiveKey);
            keys.add(floatNegativeKey);
            keys.add(doublePositiveKey);
            keys.add(doubleNegativeKey);
            keys.add(dateKey);

            Map<String, Object> results = region.getAll(keys);

            Object stringActual = results.get(stringKey);
            Object characterActual = results.get(characterKey);
            Object byteActual = results.get(byteKey);
            Object byteArrayActual = results.get(byteArrayKey);
            Object booleanArrayActual = results.get(booleanArrayKey);
            Object charArrayActual = results.get(charArrayKey);
            Object shortArrayActual = results.get(shortArrayKey);
            Object intArrayActual = results.get(intArrayKey);
            Object longArrayActual = results.get(longArrayKey);
            Object floatArrayActual = results.get(floatArrayKey);
            Object doubleArrayActual = results.get(doubleArrayKey);
            Object stringArrayActual = results.get(stringArrayKey);
            Object stringArrayListActual = results.get(stringArrayListKey);
            Object stringHashMapActual = results.get(stringHashMapKey);
            Object stringObjectHashMapActual = results.get(stringObjectHashMapKey);
            Object serializablePojoActual = results.get(serializablePojoKey);
            Object objectArrayActual = results.get(objectArrayKey);
            Object objectArrayListActual = results.get(objectArrayListKey);
            Object shortActual = results.get(shortKey);
            Object integerActual = results.get(integerKey);
            Object booleanTrueActual = results.get(booleanTrueKey);
            Object booleanFalseActual = results.get(booleanFalseKey);
            Object longPositiveActual = results.get(longPositiveKey);
            Object longNegativeActual = results.get(longNegativeKey);
            Object floatPositiveActual = results.get(floatPositiveKey);
            Object floatNegativeActual = results.get(floatNegativeKey);
            Object doublePositiveActual = results.get(doublePositiveKey);
            Object doubleNegativeActual = results.get(doubleNegativeKey);
            Object dateActual = results.get(dateKey);

            assertInstanceOf(String.class, stringActual);
            assertInstanceOf(Character.class, characterActual);
            assertInstanceOf(Byte.class, byteActual);
            assertInstanceOf(byte[].class, byteArrayActual);
            assertInstanceOf(boolean[].class, booleanArrayActual);
            assertInstanceOf(char[].class, charArrayActual);
            assertInstanceOf(short[].class, shortArrayActual);
            assertInstanceOf(int[].class, intArrayActual);
            assertInstanceOf(long[].class, longArrayActual);
            assertInstanceOf(float[].class, floatArrayActual);
            assertInstanceOf(double[].class, doubleArrayActual);
            assertInstanceOf(String[].class, stringArrayActual);
            assertInstanceOf(ArrayList.class, stringArrayListActual);
            assertInstanceOf(Map.class, stringHashMapActual);
            assertInstanceOf(Map.class, stringObjectHashMapActual);
            assertInstanceOf(CustomerProfileWithExtras.class, serializablePojoActual);
            assertInstanceOf(Object[].class, objectArrayActual);
            assertInstanceOf(ArrayList.class, objectArrayListActual);
            assertInstanceOf(Short.class, shortActual);
            assertInstanceOf(Integer.class, integerActual);
            assertInstanceOf(Boolean.class, booleanTrueActual);
            assertInstanceOf(Boolean.class, booleanFalseActual);
            assertInstanceOf(Long.class, longPositiveActual);
            assertInstanceOf(Long.class, longNegativeActual);
            assertInstanceOf(Float.class, floatPositiveActual);
            assertInstanceOf(Float.class, floatNegativeActual);
            assertInstanceOf(Double.class, doublePositiveActual);
            assertInstanceOf(Double.class, doubleNegativeActual);
            assertInstanceOf(Date.class, dateActual);

            assertEquals("string-value-" + suffix, stringActual);
            assertEquals(Character.valueOf('A'), characterActual);
            assertEquals(Byte.valueOf((byte) 7), byteActual);
            assertArrayEquals(expectedByteArray, (byte[]) byteArrayActual);
            assertArrayEquals(expectedBooleanArray, (boolean[]) booleanArrayActual);
            assertArrayEquals(expectedCharArray, (char[]) charArrayActual);
            assertArrayEquals(expectedShortArray, (short[]) shortArrayActual);
            assertArrayEquals(expectedIntArray, (int[]) intArrayActual);
            assertArrayEquals(expectedLongArray, (long[]) longArrayActual);
            assertArrayEquals(expectedFloatArray, (float[]) floatArrayActual);
            assertArrayEquals(expectedDoubleArray, (double[]) doubleArrayActual);
            assertArrayEquals(expectedStringArray, (String[]) stringArrayActual);
            assertEquals(expectedStringArrayList, stringArrayListActual);
            assertEquals(expectedStringHashMap, stringHashMapActual);
            assertMapValuesEqual(expectedStringObjectHashMap, castMap(stringObjectHashMapActual));
            assertEquals(expectedSerializablePojo, serializablePojoActual);
            assertObjectArrayDeepEquals(expectedObjectArray, (Object[]) objectArrayActual);
            assertObjectArrayListDeepEquals(expectedObjectArrayList, castArrayList(objectArrayListActual));
            assertEquals(Short.valueOf((short) 77), shortActual);
            assertEquals(Integer.valueOf(12_012), integerActual);
            assertEquals(Boolean.TRUE, booleanTrueActual);
            assertEquals(Boolean.FALSE, booleanFalseActual);
            assertEquals(Long.valueOf(9_876_543_210L), longPositiveActual);
            assertEquals(Long.valueOf(-9_876_543_210L), longNegativeActual);
            assertEquals(Float.valueOf(7.25f), floatPositiveActual);
            assertEquals(Float.valueOf(-7.25f), floatNegativeActual);
            assertEquals(Double.valueOf(7.25d), doublePositiveActual);
            assertEquals(Double.valueOf(-7.25d), doubleNegativeActual);
            assertEquals(expectedDate, dateActual);
        } catch (RuntimeException | AssertionError e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after MIXED PRIMITIVE_ARRAY_FAMILY PUT_ALL/GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
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


    private static LinkedHashMap<String, Object> complexNestedOpaqueMap(String suffix) {
        ArrayList<Object> objectList = new ArrayList<>();
        objectList.add("list-item");
        objectList.add(Integer.valueOf(42));
        objectList.add(Boolean.TRUE);
        objectList.add(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "mixed-nested-opaque-map-" + suffix);
        value.put("uuid", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        value.put("bigInteger", new BigInteger("123456789012345678901234567890"));
        value.put("bigDecimal", new BigDecimal("1234567890.123456789"));
        value.put("status", DemoStatus.ACTIVE);
        value.put("objectArray", new Object[] {
                "one",
                Integer.valueOf(42),
                Boolean.TRUE
        });
        value.put("objectArrayList", objectList);
        value.put("integerArray", new Integer[] {
                Integer.valueOf(1),
                Integer.valueOf(42),
                null,
                Integer.valueOf(-7)
        });
        value.put("uuidArray", new UUID[] {
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000001")
        });
        value.put("instant", Instant.parse("2026-05-13T20:37:37Z"));
        value.put("localDate", LocalDate.of(2026, 5, 13));
        value.put("localDateTime", LocalDateTime.of(2026, 5, 13, 20, 37, 37));
        value.put("profile", new CustomerProfile(
                "customer-complex-map-" + suffix,
                "Rob",
                42,
                true
        ));

        return value;
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



    private static final class CustomerProfile implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String id;
        private final String name;
        private final int age;
        private final boolean active;

        private CustomerProfile(String id, String name, int age, boolean active) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.active = active;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof CustomerProfile that)) {
                return false;
            }

            return age == that.age
                    && active == that.active
                    && Objects.equals(id, that.id)
                    && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, age, active);
        }
    }

    private static final class CustomerProfileWithExtras implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String id;
        private final String name;
        private final int age;
        private final boolean active;
        private final Date createdAt;
        private final byte[] payload;

        private CustomerProfileWithExtras(
                String id,
                String name,
                int age,
                boolean active,
                Date createdAt,
                byte[] payload
        ) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.active = active;
            this.createdAt = createdAt == null ? null : new Date(createdAt.getTime());
            this.payload = payload == null ? null : payload.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof CustomerProfileWithExtras that)) {
                return false;
            }

            return age == that.age
                    && active == that.active
                    && Objects.equals(id, that.id)
                    && Objects.equals(name, that.name)
                    && Objects.equals(createdAt, that.createdAt)
                    && java.util.Arrays.equals(payload, that.payload);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(id, name, age, active, createdAt);
            result = 31 * result + java.util.Arrays.hashCode(payload);
            return result;
        }
    }

    private static final class CustomerProfileWithAttributes implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String id;
        private final String name;
        private final LinkedHashMap<String, Object> attributes;

        private CustomerProfileWithAttributes(
                String id,
                String name,
                LinkedHashMap<String, Object> attributes
        ) {
            this.id = id;
            this.name = name;
            this.attributes = attributes == null ? null : new LinkedHashMap<>(attributes);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof CustomerProfileWithAttributes that)) {
                return false;
            }

            return Objects.equals(id, that.id)
                    && Objects.equals(name, that.name)
                    && Objects.equals(attributes, that.attributes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, attributes);
        }
    }


    @SuppressWarnings("unchecked")
    private static ArrayList<?> castArrayList(Object value) {
        return (ArrayList<?>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }



    private static void assertObjectArrayListDeepEquals(ArrayList<?> expected, ArrayList<?> actual) {
        assertEquals(expected.size(), actual.size(), "ArrayList<Object> size mismatch");

        for (int i = 0; i < expected.size(); i++) {
            assertDeepValueEquals(expected.get(i), actual.get(i), "ArrayList<Object> item mismatch at index " + i);
        }
    }

    private static void assertObjectArrayDeepEquals(Object[] expected, Object[] actual) {
        assertEquals(expected.length, actual.length, "Object[] length mismatch");

        for (int i = 0; i < expected.length; i++) {
            assertDeepValueEquals(expected[i], actual[i], "Object[] item mismatch at index " + i);
        }
    }

    private static void assertMapValuesEqual(Map<String, Object> expected, Map<String, Object> actual) {
        assertEquals(expected.keySet(), actual.keySet());

        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            assertDeepValueEquals(
                    entry.getValue(),
                    actual.get(entry.getKey()),
                    "Map value mismatch for key " + entry.getKey()
            );
        }
    }

    private static void assertDeepValueEquals(Object expected, Object actual, String context) {
        if (expected instanceof byte[] expectedBytes) {
            assertInstanceOf(byte[].class, actual, context + " should be byte[]");
            assertArrayEquals(expectedBytes, (byte[]) actual, context);
        } else if (expected instanceof boolean[] expectedBooleans) {
            assertInstanceOf(boolean[].class, actual, context + " should be boolean[]");
            assertArrayEquals(expectedBooleans, (boolean[]) actual, context);
        } else if (expected instanceof char[] expectedChars) {
            assertInstanceOf(char[].class, actual, context + " should be char[]");
            assertArrayEquals(expectedChars, (char[]) actual, context);
        } else if (expected instanceof short[] expectedShorts) {
            assertInstanceOf(short[].class, actual, context + " should be short[]");
            assertArrayEquals(expectedShorts, (short[]) actual, context);
        } else if (expected instanceof int[] expectedInts) {
            assertInstanceOf(int[].class, actual, context + " should be int[]");
            assertArrayEquals(expectedInts, (int[]) actual, context);
        } else if (expected instanceof long[] expectedLongs) {
            assertInstanceOf(long[].class, actual, context + " should be long[]");
            assertArrayEquals(expectedLongs, (long[]) actual, context);
        } else if (expected instanceof float[] expectedFloats) {
            assertInstanceOf(float[].class, actual, context + " should be float[]");
            assertArrayEquals(expectedFloats, (float[]) actual, context);
        } else if (expected instanceof double[] expectedDoubles) {
            assertInstanceOf(double[].class, actual, context + " should be double[]");
            assertArrayEquals(expectedDoubles, (double[]) actual, context);
        } else if (expected instanceof String[] expectedStrings) {
            assertInstanceOf(String[].class, actual, context + " should be String[]");
            assertArrayEquals(expectedStrings, (String[]) actual, context);
        } else if (expected instanceof Integer[] expectedIntegers) {
            assertInstanceOf(Integer[].class, actual, context + " should be Integer[]");
            assertArrayEquals(expectedIntegers, (Integer[]) actual, context);
        } else if (expected instanceof Long[] expectedLongObjects) {
            assertInstanceOf(Long[].class, actual, context + " should be Long[]");
            assertArrayEquals(expectedLongObjects, (Long[]) actual, context);
        } else if (expected instanceof Boolean[] expectedBooleanObjects) {
            assertInstanceOf(Boolean[].class, actual, context + " should be Boolean[]");
            assertArrayEquals(expectedBooleanObjects, (Boolean[]) actual, context);
        } else if (expected instanceof Double[] expectedDoubleObjects) {
            assertInstanceOf(Double[].class, actual, context + " should be Double[]");
            assertArrayEquals(expectedDoubleObjects, (Double[]) actual, context);
        } else if (expected instanceof UUID[] expectedUuids) {
            assertInstanceOf(UUID[].class, actual, context + " should be UUID[]");
            assertArrayEquals(expectedUuids, (UUID[]) actual, context);
        } else if (expected instanceof BigInteger[] expectedBigIntegers) {
            assertInstanceOf(BigInteger[].class, actual, context + " should be BigInteger[]");
            assertArrayEquals(expectedBigIntegers, (BigInteger[]) actual, context);
        } else if (expected instanceof BigDecimal[] expectedBigDecimals) {
            assertInstanceOf(BigDecimal[].class, actual, context + " should be BigDecimal[]");
            assertArrayEquals(expectedBigDecimals, (BigDecimal[]) actual, context);
        } else if (expected instanceof DemoStatus[] expectedStatuses) {
            assertInstanceOf(DemoStatus[].class, actual, context + " should be DemoStatus[]");
            assertArrayEquals(expectedStatuses, (DemoStatus[]) actual, context);
        } else if (expected instanceof Instant[] expectedInstants) {
            assertInstanceOf(Instant[].class, actual, context + " should be Instant[]");
            assertArrayEquals(expectedInstants, (Instant[]) actual, context);
        } else if (expected instanceof LocalDate[] expectedLocalDates) {
            assertInstanceOf(LocalDate[].class, actual, context + " should be LocalDate[]");
            assertArrayEquals(expectedLocalDates, (LocalDate[]) actual, context);
        } else if (expected instanceof LocalDateTime[] expectedLocalDateTimes) {
            assertInstanceOf(LocalDateTime[].class, actual, context + " should be LocalDateTime[]");
            assertArrayEquals(expectedLocalDateTimes, (LocalDateTime[]) actual, context);
        } else if (expected instanceof Object[] expectedObjects) {
            assertInstanceOf(Object[].class, actual, context + " should be Object[]");
            assertObjectArrayDeepEquals(expectedObjects, (Object[]) actual);
        } else if (expected instanceof ArrayList<?> expectedList) {
            assertInstanceOf(ArrayList.class, actual, context + " should be ArrayList");
            assertObjectArrayListDeepEquals(expectedList, castArrayList(actual));
        } else if (expected instanceof Map<?, ?> expectedMap) {
            assertInstanceOf(Map.class, actual, context + " should be Map");
            assertMapValuesEqual(toStringObjectMap(expectedMap), castMap(actual));
        } else {
            assertEquals(expected, actual, context);
        }
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> value) {
        Map<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();

            out.put(
                    key == null ? null : String.valueOf(key),
                    entry.getValue()
            );
        }

        return out;
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

    private enum DemoStatus {
        ACTIVE,
        INACTIVE,
        PENDING
    }

}
