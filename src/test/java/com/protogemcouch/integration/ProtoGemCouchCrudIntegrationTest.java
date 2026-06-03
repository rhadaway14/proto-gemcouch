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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ProtoGemCouchCrudIntegrationTest {

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
    void validatedCrudAndContainsBaselineShouldPassAndShimLogsShouldStayClean() {
        String suffix = UUID.randomUUID().toString();

        String createKey = "it-sample-user-" + suffix;
        String deleteKey = "it-sample-user-delete-" + suffix;

        String createValue = "Robert-created-this-" + suffix;
        String updateValue = "Robert-updated-this-" + suffix;
        String deleteValue = "delete-me-" + suffix;

        region.put(createKey, createValue);

        Object createdRaw = region.get(createKey);
        assertInstanceOf(String.class, createdRaw);
        assertEquals(createValue, createdRaw);

        region.put(createKey, updateValue);

        Object updatedRaw = region.get(createKey);
        assertInstanceOf(String.class, updatedRaw);
        assertEquals(updateValue, updatedRaw);

        assertTrue(region.containsKeyOnServer(createKey));
        assertTrue(GeodeServerProxyTestUtil.containsValueForKeyOnServer(region, createKey));

        region.put(deleteKey, deleteValue);

        Object deleteCandidateRaw = region.get(deleteKey);
        assertInstanceOf(String.class, deleteCandidateRaw);
        assertEquals(deleteValue, deleteCandidateRaw);

        assertTrue(region.containsKeyOnServer(deleteKey));
        assertTrue(GeodeServerProxyTestUtil.containsValueForKeyOnServer(region, deleteKey));

        region.remove(deleteKey);

        assertFalse(region.containsKeyOnServer(deleteKey));
        assertFalse(GeodeServerProxyTestUtil.containsValueForKeyOnServer(region, deleteKey));

        Object deletedRaw = region.get(deleteKey);
        assertNull(deletedRaw);

        assertShimLogsClean();
    }

    @Test
    void getAllForExistingStringKeysShouldReturnExpectedValues() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-1-" + suffix;
        String key2 = "it-getall-2-" + suffix;
        String key3 = "it-getall-3-" + suffix;

        String value1 = "value-1-" + suffix;
        String value2 = "value-2-" + suffix;
        String value3 = "value-3-" + suffix;

        region.put(key1, value1);
        region.put(key2, value2);
        region.put(key3, value3);

        Map<String, Object> result = getAllOrDumpShimLogs(region, Set.of(key1, key2, key3));

        assertNotNull(result);
        assertEquals(3, result.size());

        assertInstanceOf(String.class, result.get(key1));
        assertInstanceOf(String.class, result.get(key2));
        assertInstanceOf(String.class, result.get(key3));

        assertEquals(value1, result.get(key1));
        assertEquals(value2, result.get(key2));
        assertEquals(value3, result.get(key3));
    }

    @Test
    void getAllWithMissingKeyShouldReturnOnlyExistingValuesOrNullForMissingKey() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-getall-existing-1-" + suffix;
        String key2 = "it-getall-existing-2-" + suffix;
        String missingKey = "it-getall-missing-" + suffix;

        String value1 = "value-1-" + suffix;
        String value2 = "value-2-" + suffix;

        region.put(key1, value1);
        region.put(key2, value2);

        Map<String, Object> result = getAllOrDumpShimLogs(region, Set.of(key1, key2, missingKey));

        assertNotNull(result);

        assertEquals(value1, result.get(key1));
        assertEquals(value2, result.get(key2));

        if (result.containsKey(missingKey)) {
            assertNull(result.get(missingKey));
        }
    }

    @Test
    void putAllForStringValuesShouldPersistAllEntriesAndBeReadableByGetAndGetAll() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-1-" + suffix;
        String key2 = "it-putall-2-" + suffix;
        String key3 = "it-putall-3-" + suffix;

        String value1 = "putall-value-1-" + suffix;
        String value2 = "putall-value-2-" + suffix;
        String value3 = "putall-value-3-" + suffix;

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, value1);
        entries.put(key2, value2);
        entries.put(key3, value3);

        putAllOrDumpShimLogs(region, entries);

        Object raw1 = region.get(key1);
        Object raw2 = region.get(key2);
        Object raw3 = region.get(key3);

        assertInstanceOf(String.class, raw1);
        assertInstanceOf(String.class, raw2);
        assertInstanceOf(String.class, raw3);

        assertEquals(value1, raw1);
        assertEquals(value2, raw2);
        assertEquals(value3, raw3);

        Map<String, Object> getAllResult = getAllOrDumpShimLogs(region, Set.of(key1, key2, key3));

        assertNotNull(getAllResult);
        assertEquals(3, getAllResult.size());

        assertEquals(value1, getAllResult.get(key1));
        assertEquals(value2, getAllResult.get(key2));
        assertEquals(value3, getAllResult.get(key3));
    }

    @Test
    void putAllShouldOverwriteExistingStringValues() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-putall-overwrite-1-" + suffix;
        String key2 = "it-putall-overwrite-2-" + suffix;

        region.put(key1, "before-1-" + suffix);
        region.put(key2, "before-2-" + suffix);

        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(key1, "after-1-" + suffix);
        entries.put(key2, "after-2-" + suffix);

        putAllOrDumpShimLogs(region, entries);

        assertEquals("after-1-" + suffix, region.get(key1));
        assertEquals("after-2-" + suffix, region.get(key2));
    }

    @Test
    void sizeOnServerShouldReturnCurrentRegionCount() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-size-1-" + suffix;
        String key2 = "it-size-2-" + suffix;
        String key3 = "it-size-3-" + suffix;

        region.put(key1, "size-value-1-" + suffix);
        region.put(key2, "size-value-2-" + suffix);
        region.put(key3, "size-value-3-" + suffix);

        int size = sizeOnServerOrDumpShimLogs(region);

        /*
         * The shared test bucket may contain rows from previous tests in this same
         * integration run. So assert that the server-side size includes at least
         * the three rows created here.
         */
        assertTrue(
                size >= 3,
                "Expected sizeOnServer() to be at least 3 after inserting three keys, but was " + size
        );
    }

    @Test
    void keySetOnServerShouldReturnCurrentKeys() {
        String suffix = UUID.randomUUID().toString();

        String key1 = "it-keyset-1-" + suffix;
        String key2 = "it-keyset-2-" + suffix;
        String key3 = "it-keyset-3-" + suffix;

        region.put(key1, "keyset-value-1-" + suffix);
        region.put(key2, "keyset-value-2-" + suffix);
        region.put(key3, "keyset-value-3-" + suffix);

        Set<String> keys = keySetOnServerOrDumpShimLogs(region);

        assertNotNull(keys);
        assertTrue(keys.contains(key1), "Expected keySetOnServer() to contain " + key1 + ", but got " + keys);
        assertTrue(keys.contains(key2), "Expected keySetOnServer() to contain " + key2 + ", but got " + keys);
        assertTrue(keys.contains(key3), "Expected keySetOnServer() to contain " + key3 + ", but got " + keys);
    }

    private static void putAllOrDumpShimLogs(
            Region<String, Object> region,
            Map<String, Object> entries
    ) {
        try {
            region.putAll(entries);
        } catch (RuntimeException e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after PUT_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    private static Map<String, Object> getAllOrDumpShimLogs(
            Region<String, Object> region,
            Set<String> keys
    ) {
        try {
            return region.getAll(keys);
        } catch (RuntimeException e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after GET_ALL failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    private static int sizeOnServerOrDumpShimLogs(Region<String, Object> region) {
        try {
            return region.sizeOnServer();
        } catch (RuntimeException e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after SIZE_ON_SERVER failure ==========");
            dumpShimLogs();
            System.err.println("========== end protogemcouch-shim logs ==========");
            System.err.println();

            throw e;
        }
    }

    private static Set<String> keySetOnServerOrDumpShimLogs(Region<String, Object> region) {
        try {
            return region.keySetOnServer();
        } catch (RuntimeException e) {
            System.err.println();
            System.err.println("========== protogemcouch-shim logs after KEY_SET_ON_SERVER failure ==========");
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

    private static void assertShimLogsClean() {
        if (booleanEnv("IT_SKIP_LOG_GATE", false)) {
            return;
        }

        String containerName = envOrDefault("IT_SHIM_CONTAINER", DEFAULT_SHIM_CONTAINER);
        String logs = dockerLogs(containerName);

        List<String> forbiddenMarkers = List.of(
                "ClassCastException",
                "handler_put_value_deserialize_error",
                "handler_put_value_fallback_decode_ok",
                "repository_contains_value_for_key_error",
                "unknown_opcodes=1",
                "unknown_opcodes=2",
                "unknown_opcodes=3",
                "unknown_opcodes=4",
                "unknown_opcodes=5",
                "request_errors=1",
                "request_errors=2",
                "request_errors=3",
                "request_errors=4",
                "request_errors=5",
                "UNKNOWN FRAME TYPE"
        );

        for (String marker : forbiddenMarkers) {
            assertFalse(
                    logs.contains(marker),
                    "Shim logs contained forbidden regression marker [" + marker + "].\n\n"
                            + tail(logs, 200)
            );
        }

        List<String> requiredMarkers = List.of(
                "event=handler_put_value_decode_ok encoding=geode-string",
                "event=handler_contains",
                "mode=0 result=true",
                "mode=1 result=true",
                "mode=0 result=false",
                "mode=1 result=false",
                "event=repository_contains_value_for_key_miss"
        );

        for (String marker : requiredMarkers) {
            assertTrue(
                    logs.contains(marker),
                    "Shim logs did not contain required validation marker [" + marker + "].\n\n"
                            + tail(logs, 200)
            );
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

    private static String tail(String text, int maxLines) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String[] lines = text.split("\\R");
        int start = Math.max(0, lines.length - maxLines);

        StringBuilder out = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            out.append(lines[i]).append(System.lineSeparator());
        }

        return out.toString();
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

    private static boolean booleanEnv(String name, boolean fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return Boolean.parseBoolean(value.trim());
    }
}