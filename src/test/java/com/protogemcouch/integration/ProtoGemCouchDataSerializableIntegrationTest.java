package com.protogemcouch.integration;

import org.apache.geode.DataSerializable;
import org.apache.geode.DataSerializer;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates opaque round-trip of a custom {@link DataSerializable} value: the class lives only on the
 * client; the shim has never seen it and cannot deserialize it. The shim must preserve the encoded
 * bytes verbatim so the client gets its object back via its own {@code fromData}. Field-level
 * querying of a DataSerializable is out of scope (it carries no schema, unlike PDX).
 */
@Tag("integration")
class ProtoGemCouchDataSerializableIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

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
        String regionName = "ds" + UUID.randomUUID().toString().replace("-", "");
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
    void dataSerializableValueRoundTripsThroughPutAndGet() {
        DemoOrder expected = new DemoOrder("widget", 42, true);

        region.put("order-1", expected);
        Object actual = region.get("order-1");

        assertInstanceOf(DemoOrder.class, actual, "DataSerializable value should round-trip to its class");
        assertEquals(expected, actual);
    }

    @Test
    void dataSerializableValuesRoundTripThroughPutAllAndGetAll() {
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("a", new DemoOrder("alpha", 1, true));
        batch.put("b", new DemoOrder("", 0, false));
        batch.put("c", new DemoOrder("gamma-中-😀", Integer.MIN_VALUE, true));

        region.putAll(batch);
        Map<String, Object> fetched = region.getAll(Set.of("a", "b", "c"));

        for (Map.Entry<String, Object> entry : batch.entrySet()) {
            assertEquals(entry.getValue(), fetched.get(entry.getKey()),
                    "DataSerializable putAll/getAll round-trip for key " + entry.getKey());
        }
    }

    /** A plain custom {@link DataSerializable} — known only to the client, never to the shim. */
    public static final class DemoOrder implements DataSerializable {
        private String name;
        private int quantity;
        private boolean active;

        public DemoOrder() {
            // required public no-arg constructor for DataSerializable deserialization
        }

        DemoOrder(String name, int quantity, boolean active) {
            this.name = name;
            this.quantity = quantity;
            this.active = active;
        }

        @Override
        public void toData(DataOutput out) throws IOException {
            DataSerializer.writeString(name, out);
            out.writeInt(quantity);
            out.writeBoolean(active);
        }

        @Override
        public void fromData(DataInput in) throws IOException, ClassNotFoundException {
            name = DataSerializer.readString(in);
            quantity = in.readInt();
            active = in.readBoolean();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            return o instanceof DemoOrder other
                    && quantity == other.quantity
                    && active == other.active
                    && Objects.equals(name, other.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, quantity, active);
        }

        @Override
        public String toString() {
            return "DemoOrder{name=" + name + ", quantity=" + quantity + ", active=" + active + "}";
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
