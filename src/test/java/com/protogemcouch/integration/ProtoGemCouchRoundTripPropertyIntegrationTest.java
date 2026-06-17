package com.protogemcouch.integration;

import com.protogemcouch.testsupport.RandomValueGraphs;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Property-style end-to-end round-trip: many randomly generated {@code HashMap<String,Object>} value
 * graphs (the full structured supported matrix, nested) are written and read back through a real
 * Geode 1.15 client and the live shim + Couchbase, and must come back equals-level identical. This is
 * the authoritative companion to the fast in-process JSON round-trip property test — it also exercises
 * the inbound DataSerializer decode and the read-path wire re-encode that only a real client drives.
 *
 * <p>Each iteration uses its own seed, printed on failure for exact reproduction.
 */
@Tag("integration")
class ProtoGemCouchRoundTripPropertyIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private static final long BASE_SEED = 0xABCDEF01L;
    private static final int PUT_GET_ITERATIONS = 60;
    private static final int PUT_ALL_BATCH = 30;

    private ClientCache cache;
    private Region<String, Object> region;
    private String regionName;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(HOST, SHIM_PORT)
                .create();
        regionName = "rt" + UUID.randomUUID().toString().replace("-", "");
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void randomValueGraphsRoundTripThroughPutAndGet() {
        for (int i = 0; i < PUT_GET_ITERATIONS; i++) {
            long seed = BASE_SEED + i;
            LinkedHashMap<String, Object> expected = RandomValueGraphs.randomMap(new Random(seed));
            String key = "k" + i;

            region.put(key, expected);
            Object actual = region.get(key);

            String ctx = "put/get round-trip mismatch at iteration " + i + " (seed=" + seed + ")";
            assertInstanceOf(Map.class, actual, ctx);
            assertDeepEquals(expected, actual, ctx);
        }
    }

    @Test
    void randomValueGraphsRoundTripThroughPutAllAndGetAll() {
        Map<String, Object> batch = new LinkedHashMap<>();
        Map<String, LinkedHashMap<String, Object>> expectedByKey = new LinkedHashMap<>();

        for (int i = 0; i < PUT_ALL_BATCH; i++) {
            long seed = BASE_SEED + 10_000 + i;
            LinkedHashMap<String, Object> expected = RandomValueGraphs.randomMap(new Random(seed));
            String key = "b" + i;
            batch.put(key, expected);
            expectedByKey.put(key, expected);
        }

        region.putAll(batch);
        Map<String, Object> fetched = region.getAll(expectedByKey.keySet());

        for (Map.Entry<String, LinkedHashMap<String, Object>> entry : expectedByKey.entrySet()) {
            String key = entry.getKey();
            Object actual = fetched.get(key);
            String ctx = "putAll/getAll round-trip mismatch for key " + key;
            assertInstanceOf(Map.class, actual, ctx);
            assertDeepEquals(entry.getValue(), actual, ctx);
        }
    }

    /** Recursive equals-level deep comparison over the generator's supported value types. */
    private static void assertDeepEquals(Object expected, Object actual, String ctx) {
        if (expected == null) {
            assertNull(actual, ctx);
            return;
        }
        assertNotNull(actual, ctx);

        if (expected instanceof byte[] e) {
            assertArrayEquals(e, (byte[]) actual, ctx);
        } else if (expected instanceof boolean[] e) {
            assertArrayEquals(e, (boolean[]) actual, ctx);
        } else if (expected instanceof char[] e) {
            assertArrayEquals(e, (char[]) actual, ctx);
        } else if (expected instanceof short[] e) {
            assertArrayEquals(e, (short[]) actual, ctx);
        } else if (expected instanceof int[] e) {
            assertArrayEquals(e, (int[]) actual, ctx);
        } else if (expected instanceof long[] e) {
            assertArrayEquals(e, (long[]) actual, ctx);
        } else if (expected instanceof float[] e) {
            assertArrayEquals(e, (float[]) actual, ctx);
        } else if (expected instanceof double[] e) {
            assertArrayEquals(e, (double[]) actual, ctx);
        } else if (expected instanceof String[] e) {
            assertArrayEquals(e, (String[]) actual, ctx);
        } else if (expected instanceof Object[] e) {
            assertInstanceOf(Object[].class, actual, ctx);
            Object[] a = (Object[]) actual;
            assertEquals(e.length, a.length, ctx + " (Object[] length)");
            for (int i = 0; i < e.length; i++) {
                assertDeepEquals(e[i], a[i], ctx + "[" + i + "]");
            }
        } else if (expected instanceof List<?> e) {
            assertInstanceOf(List.class, actual, ctx);
            List<?> a = (List<?>) actual;
            assertEquals(e.size(), a.size(), ctx + " (List size)");
            for (int i = 0; i < e.size(); i++) {
                assertDeepEquals(e.get(i), a.get(i), ctx + "[" + i + "]");
            }
        } else if (expected instanceof Map<?, ?> e) {
            assertInstanceOf(Map.class, actual, ctx);
            Map<?, ?> a = (Map<?, ?>) actual;
            assertEquals(e.keySet(), a.keySet(), ctx + " (Map keys)");
            for (Map.Entry<?, ?> entry : e.entrySet()) {
                assertDeepEquals(entry.getValue(), a.get(entry.getKey()), ctx + "{" + entry.getKey() + "}");
            }
        } else {
            // scalars: String, Boolean, Character, numeric wrappers, Date, BigInteger, BigDecimal, UUID, enum
            assertEquals(expected, actual, ctx);
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
                // retry until the deadline
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for shim readiness");
            }
        }
        fail("shim did not become ready at " + url + " within " + timeout);
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
