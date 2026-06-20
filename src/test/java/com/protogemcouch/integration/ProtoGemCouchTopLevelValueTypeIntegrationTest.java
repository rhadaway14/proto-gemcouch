package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Round-trips the JDK utility scalars and typed object arrays as <em>top-level</em> region values
 * (not nested inside a Map or PDX, where they are already covered). Each value is put and read back
 * through the shim + Couchbase and must come back {@code equals} (or array-equal) to the original.
 * Closes the DataSerializer-coverage gap by validating the broad value-type profile at the top level.
 */
@Tag("integration")
class ProtoGemCouchTopLevelValueTypeIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private enum Status { ACTIVE, INACTIVE, PENDING }

    private ClientCache cache;
    private Region<String, Object> region;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .addPoolServer(HOST, SHIM_PORT)
                .create();
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("vt" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    private Object roundTrip(Object value) {
        String key = "k-" + UUID.randomUUID();
        region.put(key, value);
        return region.get(key);
    }

    // --- top-level utility scalars ---

    @Test
    void uuidRoundTrips() {
        UUID v = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals(v, roundTrip(v));
    }

    @Test
    void bigIntegerRoundTrips() {
        BigInteger v = new BigInteger("123456789012345678901234567890");
        assertEquals(v, roundTrip(v));
    }

    @Test
    void bigDecimalRoundTrips() {
        BigDecimal v = new BigDecimal("1234567890.123456789");
        assertEquals(v, roundTrip(v));
    }

    @Test
    void enumRoundTrips() {
        assertEquals(DayOfWeek.FRIDAY, roundTrip(DayOfWeek.FRIDAY));
    }

    @Test
    void customEnumRoundTrips() {
        assertEquals(Status.ACTIVE, roundTrip(Status.ACTIVE));
    }

    @Test
    void instantRoundTrips() {
        Instant v = Instant.parse("2026-05-13T20:37:37Z");
        assertEquals(v, roundTrip(v));
    }

    @Test
    void localDateRoundTrips() {
        LocalDate v = LocalDate.of(2026, 5, 13);
        assertEquals(v, roundTrip(v));
    }

    @Test
    void localDateTimeRoundTrips() {
        LocalDateTime v = LocalDateTime.of(2026, 5, 13, 20, 37, 37);
        assertEquals(v, roundTrip(v));
    }

    // --- top-level typed object arrays ---

    @Test
    void integerArrayRoundTrips() {
        Integer[] v = {1, 42, -7, null, Integer.MAX_VALUE, Integer.MIN_VALUE};
        assertTrue(Arrays.equals(v, (Integer[]) roundTrip(v)), "Integer[] round-trips type-exact");
    }

    @Test
    void longArrayRoundTrips() {
        Long[] v = {1L, 42L, -7L, null, 9_876_543_210L};
        assertTrue(Arrays.equals(v, (Long[]) roundTrip(v)), "Long[] round-trips type-exact");
    }

    @Test
    void doubleArrayRoundTrips() {
        Double[] v = {1.0d, 7.25d, -7.25d, null};
        assertTrue(Arrays.equals(v, (Double[]) roundTrip(v)), "Double[] round-trips type-exact");
    }

    @Test
    void uuidArrayRoundTrips() {
        UUID[] v = {UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), null,
                UUID.fromString("00000000-0000-0000-0000-000000000001")};
        assertTrue(Arrays.equals(v, (UUID[]) roundTrip(v)), "UUID[] round-trips type-exact");
    }

    @Test
    void bigIntegerArrayRoundTrips() {
        BigInteger[] v = {BigInteger.ONE, BigInteger.valueOf(42), null,
                new BigInteger("123456789012345678901234567890")};
        assertTrue(Arrays.equals(v, (BigInteger[]) roundTrip(v)), "BigInteger[] round-trips type-exact");
    }

    @Test
    void instantArrayRoundTrips() {
        Instant[] v = {Instant.parse("2026-05-13T20:37:37Z"), null, Instant.EPOCH};
        assertTrue(Arrays.equals(v, (Instant[]) roundTrip(v)), "Instant[] round-trips type-exact");
    }

    @Test
    void enumArrayRoundTrips() {
        Status[] v = {Status.ACTIVE, Status.INACTIVE, null, Status.PENDING};
        assertTrue(Arrays.equals(v, (Status[]) roundTrip(v)), "enum[] round-trips type-exact");
    }

    // --- top-level collection types beyond ArrayList / LinkedHashMap ---

    @Test
    void linkedListRoundTrips() {
        java.util.LinkedList<String> v = new java.util.LinkedList<>(java.util.List.of("a", "b", "c"));
        // List.equals is cross-implementation (element-wise), so content+order must survive.
        assertEquals(v, roundTrip(v));
    }

    @Test
    void hashSetRoundTrips() {
        java.util.HashSet<String> v = new java.util.HashSet<>(java.util.List.of("x", "y", "z"));
        assertEquals(v, roundTrip(v));
    }

    @Test
    void treeMapRoundTrips() {
        java.util.TreeMap<String, String> v = new java.util.TreeMap<>();
        v.put("a", "1");
        v.put("b", "2");
        assertEquals(v, roundTrip(v));
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
