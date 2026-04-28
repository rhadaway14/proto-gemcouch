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
import java.util.List;
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