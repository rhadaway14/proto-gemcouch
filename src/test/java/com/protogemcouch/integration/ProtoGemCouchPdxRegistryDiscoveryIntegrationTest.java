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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration")
@EnabledIfSystemProperty(named = "pdx.discovery", matches = "true")
class ProtoGemCouchPdxRegistryDiscoveryIntegrationTest {
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

        waitForShimReadyOrContinue(host, healthPort, Duration.ofSeconds(30));

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

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void discoverSimplePdxInstanceRegistryProtocol() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-discovery-simple-" + suffix;

        assertDoesNotThrow(() -> {
            PdxInstance value = pdxFactory("com.example.discovery.SimplePdx")
                    .writeString("id", "customer-1")
                    .writeString("name", "Rob")
                    .writeInt("age", 42)
                    .writeBoolean("active", true)
                    .create();

            region.put(key, value);

            Object actual = region.get(key);

            System.err.println();
            System.err.println("========== PDX SIMPLE PUT/GET RESULT ==========");
            System.err.println("actual type: " + (actual == null ? "null" : actual.getClass().getName()));

            if (actual instanceof PdxInstance pdx) {
                System.err.println("id: " + pdx.getField("id"));
                System.err.println("name: " + pdx.getField("name"));
                System.err.println("age: " + pdx.getField("age"));
                System.err.println("active: " + pdx.getField("active"));
            }

            System.err.println("========== END PDX SIMPLE PUT/GET RESULT ==========");
            System.err.println();
        });

        System.err.println();
        System.err.println("========== PDX REGISTRY DISCOVERY SUCCESS: SIMPLE_PDX_INSTANCE ==========");
        dumpShimLogs();
        System.err.println("========== END PDX REGISTRY DISCOVERY SUCCESS: SIMPLE_PDX_INSTANCE ==========");
        System.err.println();
    }

    @Test
    void discoverPdxInstanceWithUtilityFieldsRegistryProtocol() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-discovery-utilities-" + suffix;

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            PdxInstance value = pdxFactory("com.example.discovery.PdxWithUtilityFields")
                    .writeString("id", "utility-doc-1")
                    .writeObject("uuid", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
                    .writeObject("bigInteger", new BigInteger("123456789012345678901234567890"))
                    .writeObject("bigDecimal", new BigDecimal("1234567890.123456789"))
                    .create();

            region.put(key, value);
        });

        dumpDiscoveryFailure("PDX_INSTANCE_WITH_UTILITY_FIELDS", thrown);
    }

    @Test
    void discoverStandaloneEnumPdxRegistryProtocol() {
        String suffix = UUID.randomUUID().toString();
        String key = "it-pdx-discovery-standalone-enum-" + suffix;

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            region.put(key, DemoStatus.ACTIVE);
        });

        dumpDiscoveryFailure("STANDALONE_ENUM_PDX_REGISTRY", thrown);
    }

    private PdxInstanceFactory pdxFactory(String className) {
        return cache.createPdxInstanceFactory(className);
    }

    private static void dumpDiscoveryFailure(String label, RuntimeException thrown) {
        System.err.println();
        System.err.println("========== PDX REGISTRY DISCOVERY FAILURE: " + label + " ==========");
        System.err.println("Exception type: " + thrown.getClass().getName());
        System.err.println("Exception message: " + thrown.getMessage());

        Throwable cause = thrown.getCause();
        int depth = 0;
        while (cause != null && depth < 12) {
            System.err.println("Cause[" + depth + "] type: " + cause.getClass().getName());
            System.err.println("Cause[" + depth + "] message: " + cause.getMessage());
            cause = cause.getCause();
            depth++;
        }

        System.err.println();
        System.err.println("========== protogemcouch-shim logs during " + label + " ==========");
        dumpShimLogs();
        System.err.println("========== end protogemcouch-shim logs ==========");
        System.err.println("========== END PDX REGISTRY DISCOVERY FAILURE: " + label + " ==========");
        System.err.println();
    }

    private static void waitForShimReadyOrContinue(String host, int healthPort, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline) {
            HttpURLConnection conn = null;
            try {
                URI uri = URI.create("http://" + host + ":" + healthPort + "/health");
                conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setConnectTimeout(1_000);
                conn.setReadTimeout(1_000);
                conn.setRequestMethod("GET");

                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) {
                    return;
                }
            } catch (Exception ignored) {
                // Retry until timeout.
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }

            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        System.err.println();
        System.err.println("========== protogemcouch-shim logs after non-fatal health timeout ==========");
        dumpShimLogs();
        System.err.println("========== end protogemcouch-shim logs after non-fatal health timeout ==========");
        System.err.println("Continuing discovery despite health timeout.");
        System.err.println();
    }

    private static void dumpShimLogs() {
        String containerName = envOrDefault("IT_SHIM_CONTAINER", DEFAULT_SHIM_CONTAINER);

        ProcessBuilder pb = new ProcessBuilder(
                "docker",
                "logs",
                "--tail",
                "500",
                containerName
        );

        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }

            if (!finished) {
                process.destroyForcibly();
                System.err.println("docker logs command timed out");
            }

            if (output.isBlank()) {
                System.err.println("(no shim logs captured)");
            } else {
                System.err.println(output);
            }
        } catch (IOException e) {
            System.err.println("Unable to run docker logs: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while collecting docker logs: " + e.getMessage());
        }
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private enum DemoStatus {
        ACTIVE,
        INACTIVE,
        PENDING
    }
}