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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P3 gate for per-key interest filtering: a client that registers interest in a <em>specific key</em>
 * or a <em>regex</em> must receive server-pushed events only for matching keys — not for every key in
 * the region. Mutations come from a separate client process so events can only arrive via the feed.
 */
@Tag("integration")
class ProtoGemCouchInterestFilteringIntegrationTest {

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
    void registerInterestInASpecificKeyDeliversOnlyThatKey() throws Exception {
        String region = "intf" + UUID.randomUUID().toString().replace("-", "");
        Set<Object> fired = ConcurrentHashMap.newKeySet();
        CountDownLatch wantedFired = new CountDownLatch(1);

        Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .addCacheListener(recordingListener(fired, "wanted", wantedFired))
                .create(region);
        r.registerInterest("wanted", InterestResultPolicy.NONE); // single-key interest

        runPutOnce(region, "ignored", "v");  // outside the interest -> must NOT be delivered
        runPutOnce(region, "wanted", "v");   // the registered key -> must be delivered

        assertTrue(wantedFired.await(20, TimeUnit.SECONDS), "listener fires for the registered key");
        Thread.sleep(3000); // let any erroneous 'ignored' event arrive before asserting its absence
        assertFalse(fired.contains("ignored"), "listener must NOT fire for a key outside the registered interest");
    }

    @Test
    void registerInterestRegexDeliversOnlyMatchingKeys() throws Exception {
        String region = "intf" + UUID.randomUUID().toString().replace("-", "");
        Set<Object> fired = ConcurrentHashMap.newKeySet();
        CountDownLatch matchFired = new CountDownLatch(1);

        Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .addCacheListener(recordingListener(fired, "order-42", matchFired))
                .create(region);
        r.registerInterestRegex("order-.*"); // regex interest

        runPutOnce(region, "user-7", "v");   // does not match -> must NOT be delivered
        runPutOnce(region, "order-42", "v"); // matches -> must be delivered

        assertTrue(matchFired.await(20, TimeUnit.SECONDS), "listener fires for a key matching the regex");
        Thread.sleep(3000);
        assertFalse(fired.contains("user-7"), "listener must NOT fire for a key not matching the regex");
    }

    @Test
    void registerInterestInAKeyListDeliversOnlyThoseKeys() throws Exception {
        String region = "intf" + UUID.randomUUID().toString().replace("-", "");
        Set<Object> fired = ConcurrentHashMap.newKeySet();
        CountDownLatch listedFired = new CountDownLatch(1);

        Region<String, Object> r = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .addCacheListener(recordingListener(fired, "k-a", listedFired))
                .create(region);
        // A key list goes through the raw registerInterest(Object, policy) overload.
        @SuppressWarnings({"rawtypes", "unchecked"})
        Region rawRegion = r;
        rawRegion.registerInterest(java.util.List.of("k-a", "k-b"), InterestResultPolicy.NONE); // key-list interest

        runPutOnce(region, "k-c", "v");   // not in the list -> must NOT be delivered
        runPutOnce(region, "k-a", "v");   // in the list -> must be delivered

        assertTrue(listedFired.await(20, TimeUnit.SECONDS), "listener fires for a key in the registered list");
        Thread.sleep(3000);
        assertFalse(fired.contains("k-c"), "listener must NOT fire for a key outside the registered list");
    }

    private static CacheListenerAdapter<String, Object> recordingListener(
            Set<Object> fired, String awaited, CountDownLatch latch) {
        return new CacheListenerAdapter<>() {
            @Override
            public void afterCreate(EntryEvent<String, Object> event) {
                fired.add(event.getKey());
                if (awaited.equals(event.getKey())) {
                    latch.countDown();
                }
            }

            @Override
            public void afterUpdate(EntryEvent<String, Object> event) {
                fired.add(event.getKey());
                if (awaited.equals(event.getKey())) {
                    latch.countDown();
                }
            }
        };
    }

    private static void runPutOnce(String region, String key, String value) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        Process process = new ProcessBuilder(
                javaBin, "-cp", classpath, "com.protogemcouch.tools.PutOnce",
                HOST, Integer.toString(SHIM_PORT), region, key, value)
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
