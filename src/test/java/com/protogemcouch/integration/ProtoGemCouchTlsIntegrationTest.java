package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates inbound TLS on the Geode listener against a real Geode SSL client.
 *
 * <p>Targets the TLS-enabled shim instance (`protogemcouch-tls`, host port 40406, keystore mounted),
 * configuring a Geode {@link ClientCache} with SSL and the matching truststore, and confirms a
 * value round-trips over the encrypted connection.
 */
@Tag("integration")
class ProtoGemCouchTlsIntegrationTest {

    private static final String HOST = envOrDefault("IT_TLS_HOST", "127.0.0.1");
    private static final int TLS_SHIM_PORT = intEnv("IT_TLS_SHIM_PORT", 40406);
    private static final int TLS_HEALTH_PORT = intEnv("IT_TLS_HEALTH_PORT", 8082);
    private static final String REGION = envOrDefault("IT_REGION", "helloWorld");
    private static final String TRUSTSTORE = envOrDefault("IT_TLS_TRUSTSTORE", "certs/client-truststore.p12");
    private static final String TRUSTSTORE_PASSWORD = envOrDefault("IT_TLS_TRUSTSTORE_PASSWORD", "changeit");

    private ClientCache cache;
    private Region<String, Object> region;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(Files.exists(Path.of(TRUSTSTORE)), "client truststore present");
        waitForReady("http://" + HOST + ":" + TLS_HEALTH_PORT + "/ready", Duration.ofSeconds(90));

        String truststoreAbs = new File(TRUSTSTORE).getAbsolutePath();
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .set("ssl-enabled-components", "server")
                .set("ssl-truststore", truststoreAbs)
                .set("ssl-truststore-password", TRUSTSTORE_PASSWORD)
                .set("ssl-truststore-type", "pkcs12")
                .set("ssl-endpoint-identification-enabled", "false")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(HOST, TLS_SHIM_PORT)
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
    void valueRoundTripsOverTls() {
        String key = "tls-" + UUID.randomUUID();
        region.put(key, "secret-over-tls");

        Object value = region.get(key);

        assertEquals("secret-over-tls", value,
                "value should round-trip over the TLS-encrypted Geode connection");
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
                fail("interrupted waiting for TLS shim readiness");
            }
        }
        fail("TLS shim did not become ready before timeout: " + url);
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
