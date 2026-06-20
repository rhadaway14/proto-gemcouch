package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.client.Pool;
import org.apache.geode.cache.client.PoolManager;
import org.apache.geode.pdx.PdxInstance;
import org.apache.geode.pdx.internal.EnumInfo;
import org.apache.geode.pdx.internal.PdxType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates bulk PDX registry discovery end-to-end against the shim with a real Geode 1.15 client:
 * after a client registers PDX types (and an enum) by writing PdxInstances, the client's own registry
 * sync ops read every type/enum back. These ops (GET_PDX_TYPES 101 / GET_PDX_ENUMS 102 /
 * GET_PDX_ENUM_BY_ID 98) have no clean public API trigger, so the test drives the real client
 * {@code GetPDXTypesOp}/{@code GetPDXEnumsOp}/{@code GetPDXEnumByIdOp} directly — their
 * {@code processResponse} parsers are the authoritative oracle for the shim's reply bytes.
 */
@Tag("integration")
class ProtoGemCouchPdxRegistryDiscoveryIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private enum Status { ACTIVE, CLOSED }

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
                .create("pdxdisc" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void registrySyncReadsBackEveryRegisteredTypeAndEnum() throws Exception {
        String orderType = "demo.Order." + UUID.randomUUID().toString().replace("-", "");
        String customerType = "demo.Customer." + UUID.randomUUID().toString().replace("-", "");

        // Register two distinct PDX types; the enum object field also registers a PDX enum.
        PdxInstance order = cache.createPdxInstanceFactory(orderType)
                .writeString("sku", "abc").writeInt("qty", 3)
                .writeObject("status", Status.ACTIVE)
                .create();
        PdxInstance customer = cache.createPdxInstanceFactory(customerType)
                .writeString("name", "acme").writeBoolean("vip", true)
                .create();
        region.put("o1", order);
        region.put("c1", customer);

        Pool pool = PoolManager.getAll().values().iterator().next();

        // GET_PDX_TYPES (101): both types come back, keyed by id, decoded to PdxType with our names.
        Map<?, ?> types = executeOp("org.apache.geode.cache.client.internal.GetPDXTypesOp", pool, null);
        boolean sawOrder = false;
        boolean sawCustomer = false;
        for (Object v : types.values()) {
            String name = ((PdxType) v).getClassName();
            sawOrder |= orderType.equals(name);
            sawCustomer |= customerType.equals(name);
        }
        assertTrue(sawOrder && sawCustomer,
                "GET_PDX_TYPES must return both registered types; got " + types.values());

        // GET_PDX_ENUMS (102): the Status enum comes back, decoded to EnumInfo.
        Map<?, ?> enums = executeOp("org.apache.geode.cache.client.internal.GetPDXEnumsOp", pool, null);
        assertTrue(!enums.isEmpty(), "GET_PDX_ENUMS must return the registered enum; got " + enums);
        Object enumId = enums.keySet().iterator().next();
        assertTrue(enums.get(enumId) instanceof EnumInfo, "enum value decodes to EnumInfo");

        // GET_PDX_ENUM_BY_ID (98): the reverse lookup returns the same EnumInfo for that id.
        Object byId = executeOp("org.apache.geode.cache.client.internal.GetPDXEnumByIdOp", pool, (Integer) enumId);
        assertNotNull(byId, "GET_PDX_ENUM_BY_ID must return the EnumInfo for a known id");
        assertEquals(enums.get(enumId).toString(), byId.toString(),
                "reverse enum lookup matches the bulk-discovery entry");
    }

    /** Invoke an internal client PDX Op by reflection (no public API triggers these). */
    @SuppressWarnings("unchecked")
    private static <T> T executeOp(String opClass, Pool pool, Integer arg) throws Exception {
        Class<?> op = Class.forName(opClass);
        Class<?> execPool = Class.forName("org.apache.geode.cache.client.internal.ExecutablePool");
        if (arg == null) {
            Method m = op.getMethod("execute", execPool);
            return (T) m.invoke(null, pool);
        }
        Method m = op.getMethod("execute", execPool, int.class);
        return (T) m.invoke(null, pool, arg.intValue());
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
