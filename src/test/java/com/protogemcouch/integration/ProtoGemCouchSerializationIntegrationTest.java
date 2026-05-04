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