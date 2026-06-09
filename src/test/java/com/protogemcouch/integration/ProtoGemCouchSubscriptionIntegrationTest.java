package com.protogemcouch.integration;

import org.apache.geode.cache.EntryEvent;
import org.apache.geode.cache.InterestResultPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.util.CacheListenerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1a gate for register-interest/subscriptions: a real subscription-enabled Geode client must
 * establish its connections against the shim (the op connection, the mode-107 control connection, and
 * the mode-101 server&rarr;client feed) and successfully register interest. This exercises the shim's
 * connection-mode handshake dispatch and the register-interest reply end to end. Event delivery
 * (CacheListener firing) is P1b.
 */
@Tag("integration")
class ProtoGemCouchSubscriptionIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private ClientCache cache;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(true)
                .setPoolSubscriptionRedundancy(0)
                .setPoolSubscriptionAckInterval(100)
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
    void subscriptionClientConnectsAndRegistersInterest() {
        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .create("sub" + UUID.randomUUID().toString().replace("-", ""));

        // The gate: registerInterest requires the subscription feed (mode 101) and control (mode 107)
        // connections to be established and the register-interest reply to be accepted. If any
        // handshake is wrong the client raises here instead.
        assertDoesNotThrow(() -> region.registerInterest("ALL_KEYS", InterestResultPolicy.NONE),
                "subscription-enabled client registers interest against the shim without error");
    }

    @Test
    void cacheListenerFiresOnRemoteCreate() throws Exception {
        String regionName = "sub" + UUID.randomUUID().toString().replace("-", "");
        CountDownLatch created = new CountDownLatch(1);
        AtomicReference<Object> key = new AtomicReference<>();
        AtomicReference<Object> value = new AtomicReference<>();

        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .addCacheListener(new CacheListenerAdapter<String, Object>() {
                    @Override
                    public void afterCreate(EntryEvent<String, Object> event) {
                        key.set(event.getKey());
                        value.set(event.getNewValue());
                        created.countDown();
                    }

                    @Override
                    public void afterUpdate(EntryEvent<String, Object> event) {
                        key.set(event.getKey());
                        value.set(event.getNewValue());
                        created.countDown();
                    }
                })
                .create(regionName);
        region.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);

        // Mutate from a SEPARATE client process, so the listener can only fire from the
        // server-pushed notification (not a local operation).
        runPutOnce(regionName, "evt1", "hello");

        assertTrue(created.await(20, TimeUnit.SECONDS),
                "the CacheListener fired from the server-pushed create event");
        assertEquals("evt1", key.get());
        assertEquals("hello", value.get());
    }

    @Test
    void cacheListenerDistinguishesCreateFromUpdate() throws Exception {
        String regionName = "sub" + UUID.randomUUID().toString().replace("-", "");
        CountDownLatch updated = new CountDownLatch(1);
        AtomicReference<Object> updatedValue = new AtomicReference<>();

        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .addCacheListener(new CacheListenerAdapter<String, Object>() {
                    @Override
                    public void afterUpdate(EntryEvent<String, Object> event) {
                        updatedValue.set(event.getNewValue());
                        updated.countDown();
                    }
                })
                .create(regionName);
        region.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);

        // A separate client creates then updates the same key; only the second put is an UPDATE, so
        // afterUpdate (not afterCreate) must fire.
        runPutOnce(regionName, "evt3", "hello", "update");

        assertTrue(updated.await(20, TimeUnit.SECONDS),
                "afterUpdate fired for the server-pushed update (LOCAL_UPDATE, not LOCAL_CREATE)");
        assertEquals("hello-upd", updatedValue.get());
    }

    @Test
    void cacheListenerFiresOnRemoteDestroy() throws Exception {
        String regionName = "sub" + UUID.randomUUID().toString().replace("-", "");
        CountDownLatch destroyed = new CountDownLatch(1);
        AtomicReference<Object> key = new AtomicReference<>();

        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .addCacheListener(new CacheListenerAdapter<String, Object>() {
                    @Override
                    public void afterDestroy(EntryEvent<String, Object> event) {
                        key.set(event.getKey());
                        destroyed.countDown();
                    }
                })
                .create(regionName);
        region.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);

        // A separate client creates then removes the key; the destroy must reach the listener.
        runPutOnce(regionName, "evt2", "hello", "remove");

        assertTrue(destroyed.await(20, TimeUnit.SECONDS),
                "the CacheListener fired from the server-pushed destroy event");
        assertEquals("evt2", key.get());
    }

    @Test
    void clientDoesNotReceiveItsOwnEventsEchoedBack() throws Exception {
        String regionName = "sub" + UUID.randomUUID().toString().replace("-", "");
        // A self-echoed create would land as an afterUpdate (the key already exists locally after the
        // local put). With self-event suppression it must never arrive.
        CountDownLatch selfEcho = new CountDownLatch(1);

        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .addCacheListener(new CacheListenerAdapter<String, Object>() {
                    @Override
                    public void afterUpdate(EntryEvent<String, Object> event) {
                        selfEcho.countDown();
                    }
                })
                .create(regionName);
        region.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);

        // This client makes the mutation itself; the server must not echo it back to this client.
        region.put("selfKey", "v");

        assertFalse(selfEcho.await(5, TimeUnit.SECONDS),
                "a client does not receive its own mutation echoed back to its feed");
    }

    @Test
    void cacheListenerFiresOnRemoteInvalidate() throws Exception {
        String regionName = "sub" + UUID.randomUUID().toString().replace("-", "");
        CountDownLatch invalidated = new CountDownLatch(1);
        AtomicReference<Object> key = new AtomicReference<>();

        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .addCacheListener(new CacheListenerAdapter<String, Object>() {
                    @Override
                    public void afterInvalidate(EntryEvent<String, Object> event) {
                        key.set(event.getKey());
                        invalidated.countDown();
                    }
                })
                .create(regionName);
        region.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);

        // A separate client creates then invalidates the key; afterInvalidate must fire.
        runPutOnce(regionName, "evt4", "hello", "invalidate");

        assertTrue(invalidated.await(20, TimeUnit.SECONDS),
                "the CacheListener fired from the server-pushed invalidate event");
        assertEquals("evt4", key.get());
    }

    private static void runPutOnce(String region, String key, String value) throws Exception {
        runPutOnce(region, key, value, null);
    }

    private static void runPutOnce(String region, String key, String value, String op) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                javaBin, "-cp", classpath, "com.protogemcouch.tools.PutOnce",
                HOST, Integer.toString(SHIM_PORT), region, key, value));
        if (op != null) {
            command.add(op);
        }
        Process process = new ProcessBuilder(command)
                .inheritIO()
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("PutOnce mutator process did not finish in time");
        }
        assertEquals(0, process.exitValue(), "PutOnce mutator process succeeded");
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
