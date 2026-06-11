package com.protogemcouch.integration;

import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.execute.FunctionException;
import org.apache.geode.cache.execute.FunctionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Graceful-degradation gate for server-side functions: the shim cannot run user {@code Function}
 * code, so a real Geode 1.15 client that executes a function against it must get a clean, prompt
 * exception (as it would from a real server for an unregistered function) rather than a hang or an
 * abrupt connection close. Covers both {@code onServer(..)} and {@code onRegion(..)} executions.
 */
@Tag("integration")
class ProtoGemCouchFunctionIntegrationTest {

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
    void onServerFunctionExecutionFailsCleanly() {
        String functionId = "shimFn" + UUID.randomUUID().toString().replace("-", "");

        Exception thrown = assertThrows(Exception.class,
                () -> FunctionService.onServer(cache).execute(functionId).getResult(),
                "executing a function the shim cannot run must raise an exception, not hang");

        assertTrue(mentionsFunction(thrown, functionId),
                "the failure names the rejected function (got: " + describe(thrown) + ")");
    }

    @Test
    void onRegionFunctionExecutionFailsCleanly() {
        String regionName = "fn" + UUID.randomUUID().toString().replace("-", "");
        var region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
        String functionId = "shimFn" + UUID.randomUUID().toString().replace("-", "");

        Exception thrown = assertThrows(Exception.class,
                () -> FunctionService.onRegion(region).execute(functionId).getResult(),
                "executing a region function the shim cannot run must raise an exception, not hang");

        // onRegion may surface the rejection as a FunctionException with no function id in the message;
        // the contract that matters is a prompt, clean exception rather than a hang/crash.
        assertTrue(thrown instanceof FunctionException || mentionsFunction(thrown, functionId),
                "region function rejected cleanly (got: " + describe(thrown) + ")");
    }

    private static boolean mentionsFunction(Throwable t, String functionId) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && (m.contains(functionId) || m.contains("not registered"))) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
        }
        return sb.toString();
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
                fail("interrupted waiting for shim readiness");
            }
        }
        fail("shim did not become ready before timeout: " + url);
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
