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
import java.io.Serial;
import java.io.Serializable;
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
    void mixedStringCharacterByteByteArrayStringArrayStringArrayListStringHashMapStringObjectHashMapSerializablePojoObjectArrayShortIntegerBooleanLongFloatDoubleDatePutAllAndGetAllShouldPreserveTypes() {
        String suffix = UUID.randomUUID().toString();

        String stringKey = "it-mixed16-string-" + suffix;
        String characterKey = "it-mixed16-character-" + suffix;
        String byteKey = "it-mixed16-byte-" + suffix;
        String byteArrayKey = "it-mixed16-byte-array-" + suffix;
        String stringArrayKey = "it-mixed16-string-array-" + suffix;
        String stringArrayListKey = "it-mixed16-string-array-list-" + suffix;
        String stringHashMapKey = "it-mixed16-string-hash-map-" + suffix;
        String stringObjectHashMapKey = "it-mixed16-string-object-hash-map-" + suffix;
        String serializablePojoKey = "it-mixed16-serializable-pojo-" + suffix;
        String objectArrayKey = "it-mixed16-object-array-" + suffix;
        String shortKey = "it-mixed16-short-" + suffix;
        String integerKey = "it-mixed16-integer-" + suffix;
        String booleanTrueKey = "it-mixed16-bool-true-" + suffix;
        String booleanFalseKey = "it-mixed16-bool-false-" + suffix;
        String longPositiveKey = "it-mixed16-long-positive-" + suffix;
        String longNegativeKey = "it-mixed16-long-negative-" + suffix;
        String floatPositiveKey = "it-mixed16-float-positive-" + suffix;
        String floatNegativeKey = "it-mixed16-float-negative-" + suffix;
        String doublePositiveKey = "it-mixed16-double-positive-" + suffix;
        String doubleNegativeKey = "it-mixed16-double-negative-" + suffix;
        String dateKey = "it-mixed16-date-" + suffix;

        byte[] expectedByteArray = new byte[] {0x01, 0x02, 0x03, 0x04, 0x05};
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

        Date expectedDate = new Date(1_000L);

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(stringKey, "string-value-" + suffix);
        entries.put(characterKey, Character.valueOf('A'));
        entries.put(byteKey, Byte.valueOf((byte) 7));
        entries.put(byteArrayKey, expectedByteArray);
        entries.put(stringArrayKey, expectedStringArray);
        entries.put(stringArrayListKey, expectedStringArrayList);
        entries.put(stringHashMapKey, expectedStringHashMap);
        entries.put(stringObjectHashMapKey, expectedStringObjectHashMap);
        entries.put(serializablePojoKey, expectedSerializablePojo);
        entries.put(objectArrayKey, expectedObjectArray);
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
            keys.add(stringArrayKey);
            keys.add(stringArrayListKey);
            keys.add(stringHashMapKey);
            keys.add(stringObjectHashMapKey);
            keys.add(serializablePojoKey);
            keys.add(objectArrayKey);
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
            Object stringArrayActual = results.get(stringArrayKey);
            Object stringArrayListActual = results.get(stringArrayListKey);
            Object stringHashMapActual = results.get(stringHashMapKey);
            Object stringObjectHashMapActual = results.get(stringObjectHashMapKey);
            Object serializablePojoActual = results.get(serializablePojoKey);
            Object objectArrayActual = results.get(objectArrayKey);
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
            assertInstanceOf(String[].class, stringArrayActual);
            assertInstanceOf(ArrayList.class, stringArrayListActual);
            assertInstanceOf(Map.class, stringHashMapActual);
            assertInstanceOf(Map.class, stringObjectHashMapActual);
            assertInstanceOf(CustomerProfileWithExtras.class, serializablePojoActual);
            assertInstanceOf(Object[].class, objectArrayActual);
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
            assertArrayEquals(expectedStringArray, (String[]) stringArrayActual);
            assertEquals(expectedStringArrayList, stringArrayListActual);
            assertEquals(expectedStringHashMap, stringHashMapActual);
            assertMapValuesEqual(expectedStringObjectHashMap, castMap(stringObjectHashMapActual));
            assertEquals(expectedSerializablePojo, serializablePojoActual);
            assertObjectArrayDeepEquals(expectedObjectArray, (Object[]) objectArrayActual);
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
            System.err.println("========== protogemcouch-shim logs after MIXED OBJECT_ARRAY PUT_ALL/GET_ALL failure ==========");
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
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }


    private static void assertObjectArrayDeepEquals(Object[] expected, Object[] actual) {
        assertEquals(expected.length, actual.length, "Object[] length mismatch");

        for (int i = 0; i < expected.length; i++) {
            Object expectedItem = expected[i];
            Object actualItem = actual[i];

            if (expectedItem instanceof byte[] expectedBytes) {
                assertInstanceOf(byte[].class, actualItem, "Object[] item " + i + " should be byte[]");
                assertArrayEquals(expectedBytes, (byte[]) actualItem, "Object[] byte[] item mismatch at index " + i);
            } else if (expectedItem instanceof String[] expectedStrings) {
                assertInstanceOf(String[].class, actualItem, "Object[] item " + i + " should be String[]");
                assertArrayEquals(expectedStrings, (String[]) actualItem, "Object[] String[] item mismatch at index " + i);
            } else if (expectedItem instanceof Object[] expectedObjects) {
                assertInstanceOf(Object[].class, actualItem, "Object[] item " + i + " should be Object[]");
                assertObjectArrayDeepEquals(expectedObjects, (Object[]) actualItem);
            } else if (expectedItem instanceof Map<?, ?> expectedMap) {
                assertInstanceOf(Map.class, actualItem, "Object[] item " + i + " should be Map");
                assertMapValuesEqual(toStringObjectMap(expectedMap), castMap(actualItem));
            } else {
                assertEquals(expectedItem, actualItem, "Object[] item mismatch at index " + i);
            }
        }
    }

    private static void assertMapValuesEqual(Map<String, Object> expected, Map<String, Object> actual) {
        assertEquals(expected.keySet(), actual.keySet());

        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Object expectedValue = entry.getValue();
            Object actualValue = actual.get(entry.getKey());

            if (expectedValue instanceof byte[] expectedBytes) {
                assertInstanceOf(byte[].class, actualValue);
                assertArrayEquals(expectedBytes, (byte[]) actualValue);
            } else if (expectedValue instanceof String[] expectedStrings) {
                assertInstanceOf(String[].class, actualValue);
                assertArrayEquals(expectedStrings, (String[]) actualValue);
            } else if (expectedValue instanceof Map<?, ?> expectedMap) {
                assertInstanceOf(Map.class, actualValue);
                assertMapValuesEqual(toStringObjectMap(expectedMap), castMap(actualValue));
            } else {
                assertEquals(expectedValue, actualValue);
            }
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
}
