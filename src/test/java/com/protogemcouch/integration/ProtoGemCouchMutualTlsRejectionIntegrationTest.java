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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Confirms the mutual-TLS listener (`protogemcouch-mtls`) rejects a client that presents no
 * certificate. In its own class (separate forked JVM) so Geode's JVM-wide SSL socket singleton is
 * clean — the positive case lives in {@link ProtoGemCouchMutualTlsIntegrationTest}.
 */
@Tag("integration")
class ProtoGemCouchMutualTlsRejectionIntegrationTest {

    private static final String HOST = envOrDefault("IT_MTLS_HOST", "127.0.0.1");
    private static final int MTLS_SHIM_PORT = intEnv("IT_MTLS_SHIM_PORT", 40407);
    private static final int MTLS_HEALTH_PORT = intEnv("IT_MTLS_HEALTH_PORT", 8083);
    private static final String REGION = envOrDefault("IT_REGION", "helloWorld");
    private static final String CLIENT_TRUSTSTORE = "certs/client-truststore.p12";
    private static final String PASS = "changeit";

    private ClientCache cache;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(Files.exists(Path.of(CLIENT_TRUSTSTORE)), "client truststore present");
        waitForReady("http://" + HOST + ":" + MTLS_HEALTH_PORT + "/ready", Duration.ofSeconds(90));
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void clientWithoutCertificateIsRejected() {
        // Trusts the server, but presents NO client keystore. The mTLS listener requires a client
        // certificate, so the handshake fails and the operation cannot complete.
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .set("ssl-enabled-components", "server")
                .set("ssl-truststore", new File(CLIENT_TRUSTSTORE).getAbsolutePath())
                .set("ssl-truststore-password", PASS)
                .set("ssl-truststore-type", "pkcs12")
                .set("ssl-endpoint-identification-enabled", "false")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(HOST, MTLS_SHIM_PORT)
                .create();

        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(REGION);

        assertThrows(Exception.class,
                () -> region.put("no-cert-" + UUID.randomUUID(), "should-fail"),
                "a client without a certificate must be rejected by the mTLS listener");
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
                fail("interrupted waiting for mTLS shim readiness");
            }
        }
        fail("mTLS shim did not become ready before timeout: " + url);
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
