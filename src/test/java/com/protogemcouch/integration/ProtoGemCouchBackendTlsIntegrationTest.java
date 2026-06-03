package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates that the shim can talk to Couchbase over TLS (backend transport security).
 *
 * <p>Targets the backend-TLS shim instance (`protogemcouch-cbtls`, host port 40408), which connects
 * to Couchbase via {@code couchbases://} trusting the exported cluster certificate. The Geode client
 * here is plaintext; a successful value round-trip proves the shim's encrypted Couchbase connection
 * works (reads and writes could not complete otherwise).
 */
@Tag("integration")
class ProtoGemCouchBackendTlsIntegrationTest {

    private static final String HOST = envOrDefault("IT_CBTLS_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_CBTLS_SHIM_PORT", 40408);
    private static final int HEALTH_PORT = intEnv("IT_CBTLS_HEALTH_PORT", 8084);
    private static final String REGION = envOrDefault("IT_REGION", "helloWorld");

    private ClientCache cache;
    private Region<String, Object> region;

    @BeforeEach
    void setUp() {
        // Readiness implies the shim connected to Couchbase over TLS at startup.
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(120));

        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(HOST, SHIM_PORT)
                .create();

        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(REGION);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void valueRoundTripsThroughTlsBackedCouchbase() {
        String key = "cbtls-" + UUID.randomUUID();
        region.put(key, "stored-via-tls-couchbase");

        assertEquals("stored-via-tls-couchbase", region.get(key),
                "value should persist and read back via the TLS-encrypted Couchbase connection");
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
                fail("interrupted waiting for backend-TLS shim readiness");
            }
        }
        fail("backend-TLS shim did not become ready before timeout: " + url);
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
