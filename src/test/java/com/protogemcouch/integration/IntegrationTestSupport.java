package com.protogemcouch.integration;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;

public abstract class IntegrationTestSupport {

    protected static String shimHost;
    protected static int shimPort;

    protected static String cbConnStr;
    protected static String cbUsername;
    protected static String cbPassword;
    protected static String cbBucket;
    protected static String cbScope;
    protected static String cbCollection;

    protected static ClientCache cache;
    protected static Region<String, String> region;

    protected static Cluster cluster;
    protected static Bucket bucket;
    protected static Scope scope;
    protected static Collection collection;

    @BeforeAll
    static void setUpBase() {
        shimHost = envOrDefault("TEST_SHIM_HOST", "127.0.0.1");
        shimPort = envIntOrDefault("TEST_SHIM_PORT", 40405);

        cbConnStr = envOrDefault("CB_CONNSTR", "couchbase://127.0.0.1");
        cbUsername = envOrDefault("CB_USERNAME", "Administrator");
        cbPassword = envOrDefault("CB_PASSWORD", "password");
        cbBucket = envOrDefault("CB_BUCKET", "test");
        cbScope = envOrDefault("CB_SCOPE", "_default");
        cbCollection = envOrDefault("CB_COLLECTION", "_default");

        Assumptions.assumeTrue(
                isPortOpen(shimHost, shimPort, 1000),
                () -> "Integration tests require RawShimServer to be running on " + shimHost + ":" + shimPort
        );

        cache = new ClientCacheFactory()
                .addPoolServer(shimHost, shimPort)
                .setPoolSubscriptionEnabled(false)
                .set("log-level", "warn")
                .create();

        region = cache.<String, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("helloWorld");

        cluster = Cluster.connect(cbConnStr, cbUsername, cbPassword);
        bucket = cluster.bucket(cbBucket);
        bucket.waitUntilReady(Duration.ofSeconds(15));
        scope = bucket.scope(cbScope);
        collection = scope.collection(cbCollection);
    }

    @AfterAll
    static void tearDownBase() {
        if (cache != null) {
            cache.close();
        }
        if (cluster != null) {
            cluster.disconnect();
        }
    }

    protected static String regionPath() {
        return "/helloWorld";
    }

    protected static String docId(String key) {
        return regionPath() + "::" + key;
    }

    protected static String uniqueKey(String prefix) {
        return prefix + "::" + UUID.randomUUID();
    }

    protected static void removeDocIfPresent(String key) {
        try {
            collection.remove(docId(key));
        } catch (Exception ignored) {
        }
    }

    protected static String readStoredValue(String key) {
        try {
            return collection.get(docId(key)).contentAsObject().getString("value");
        } catch (Exception e) {
            return null;
        }
    }

    protected static boolean docExists(String key) {
        try {
            return collection.exists(docId(key)).exists();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int envIntOrDefault(String name, int fallback) {
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