package com.protogemcouch.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates the shim's TLS protocol policy against the dedicated TLS-1.3-only instance
 * ({@code protogemcouch-tls13}, {@code TLS_PROTOCOLS=TLSv1.3}). A client that offers only TLS 1.2 is
 * rejected at the TLS handshake (proving the policy is enforced <em>server-side</em>, since a JDK 17
 * client can offer 1.2), while a TLS 1.3 client negotiates successfully. This guards against a config
 * or JVM change silently re-enabling a weaker protocol.
 */
@Tag("integration")
class ProtoGemCouchTlsPolicyIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int TLS_PORT = intEnv("IT_TLS13_SHIM_PORT", 40413);
    private static final int HEALTH_PORT = intEnv("IT_TLS13_HEALTH_PORT", 8089);

    @Test
    void rejectsTls12ClientWhenPinnedToTls13() throws Exception {
        waitForReady();
        try (SSLSocket socket = newSocket(new String[] {"TLSv1.2"})) {
            // The server is pinned to TLS 1.3, so a 1.2-only client shares no protocol -> handshake fails.
            assertThrows(SSLException.class, socket::startHandshake,
                    "a TLS 1.2 client must be rejected by a TLS-1.3-pinned server");
        }
    }

    @Test
    void acceptsTls13Client() throws Exception {
        waitForReady();
        try (SSLSocket socket = newSocket(new String[] {"TLSv1.3"})) {
            socket.startHandshake();
            assertEquals("TLSv1.3", socket.getSession().getProtocol(),
                    "a TLS 1.3 client negotiates TLS 1.3");
        }
    }

    private SSLSocket newSocket(String[] enabledProtocols) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll(), new SecureRandom());
        SSLSocketFactory factory = ctx.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();
        socket.connect(new InetSocketAddress(HOST, TLS_PORT), 5000);
        socket.setSoTimeout(5000);
        socket.setEnabledProtocols(enabledProtocols);
        return socket;
    }

    /** Trust-all manager: this test validates protocol negotiation, not certificate trust. */
    private static TrustManager[] trustAll() {
        return new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
    }

    private void waitForReady() {
        String url = "http://" + HOST + ":" + HEALTH_PORT + "/ready";
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
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
                fail("interrupted waiting for shim readiness");
            }
        }
        fail("TLS-1.3 shim did not become ready before timeout: " + url);
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
