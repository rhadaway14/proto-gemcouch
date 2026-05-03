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