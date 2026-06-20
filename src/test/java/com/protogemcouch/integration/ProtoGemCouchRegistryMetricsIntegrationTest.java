package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.pdx.PdxInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates that the in-memory registry gauges added in 1.1.0-M1 are exposed on the Prometheus
 * {@code /metrics} endpoint and reflect live state: after a client registers a PDX type, the
 * {@code protogemcouch_pdx_types} gauge is &gt;= 1, and all the registry gauges plus the
 * PDX-registry-rejection counter are present with their declared types.
 */
@Tag("integration")
class ProtoGemCouchRegistryMetricsIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private ClientCache cache;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(HOST, SHIM_PORT)
                .create();
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void registryGaugesAreExposedOnMetrics() {
        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("rm" + UUID.randomUUID().toString().replace("-", ""));

        // Register a PDX type so the pdx_types gauge is non-zero.
        PdxInstance pdx = cache.createPdxInstanceFactory("demo.Metrics")
                .writeString("s", "v").writeInt("n", 1).create();
        region.put("k", pdx);

        String metrics = scrape();

        // All M1 gauges + the rejection counter are present with their declared TYPE.
        for (String gauge : new String[] {
                "protogemcouch_pdx_types", "protogemcouch_pdx_enums", "protogemcouch_active_transactions",
                "protogemcouch_subscription_feeds", "protogemcouch_registered_interests",
                "protogemcouch_registered_cqs", "protogemcouch_durable_clients",
                "protogemcouch_durable_queue_depth"}) {
            assertTrue(metrics.contains("# TYPE " + gauge + " gauge"), "missing gauge: " + gauge);
        }
        assertTrue(metrics.contains("# TYPE protogemcouch_pdx_registry_rejected_total counter"),
                "missing rejection counter");

        assertTrue(pdxTypesValue(metrics) >= 1,
                "pdx_types gauge should reflect the registered type: \n" + metrics);
    }

    private static long pdxTypesValue(String metrics) {
        for (String line : metrics.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("protogemcouch_pdx_types ")) {
                try {
                    return (long) Double.parseDouble(trimmed.substring(trimmed.lastIndexOf(' ') + 1));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String scrape() {
        try {
            HttpURLConnection connection =
                    (HttpURLConnection) URI.create("http://" + HOST + ":" + HEALTH_PORT + "/metrics").toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().reduce("", (a, b) -> a + "\n" + b);
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            fail("failed to scrape /metrics: " + e);
            return "";
        }
    }

    private static void waitForReady(String url, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(1500);
                connection.setRequestMethod("GET");
                try {
                    if (connection.getResponseCode() == 200) {
                        return;
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {
                // retry
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for readiness");
            }
        }
        fail("shim did not become ready: " + url);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
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
