package com.protogemcouch.samples;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

import java.nio.charset.StandardCharsets;

public class SampleCrudApp {

    public static void main(String[] args) {
        String host = envOrDefault("APP_HOST", "127.0.0.1");
        int port = intEnv("APP_PORT", 40405);
        String regionName = envOrDefault("APP_REGION", "helloWorld");

        String createKey = envOrDefault("APP_CREATE_KEY", "sample-user-1");
        String createValue = envOrDefault("APP_CREATE_VALUE", "Robert-created-this");
        String updateValue = envOrDefault("APP_UPDATE_VALUE", "Robert-updated-this");
        String deleteKey = envOrDefault("APP_DELETE_KEY", "sample-user-delete");
        String deleteValue = envOrDefault("APP_DELETE_VALUE", "delete-me");

        System.out.println("=== ProtoGemCouch Sample CRUD App ===");
        System.out.println("Connecting to shim at " + host + ":" + port);
        System.out.println("Region: " + regionName);
        System.out.println();

        ClientCache cache = null;
        try {
            cache = createCache(host, port);
            Region<String, Object> region = createRegion(cache, regionName);

            runCreate(region, createKey, createValue);
            runRead(region, createKey, createValue);
            runUpdate(region, createKey, updateValue);
            runContainsEquivalent(region, createKey, true);

            runCreate(region, deleteKey, deleteValue);
            runDelete(region, deleteKey);

            cache.close();

            cache = createCache(host, port);
            region = createRegion(cache, regionName);

            runContainsEquivalent(region, deleteKey, false);

            System.out.println();
            System.out.println("=== Sample app completed successfully ===");
            System.out.println("Expected surviving Couchbase document:");
            System.out.println("  document id: /" + regionName + "::" + createKey);
            System.out.println("  value field: " + updateValue);
            System.out.println();
            System.out.println("Deleted document should be absent:");
            System.out.println("  document id: /" + regionName + "::" + deleteKey);
            System.out.println();
            System.out.println("Check Couchbase UI:");
            System.out.println("  Bucket: test");
            System.out.println("  Scope: _default");
            System.out.println("  Collection: _default");
        } finally {
            if (cache != null) {
                cache.close();
            }
        }
    }

    private static ClientCache createCache(String host, int port) {
        return new ClientCacheFactory()
                .addPoolServer(host, port)
                .setPoolSubscriptionEnabled(false)
                .set("log-level", "warn")
                .create();
    }

    private static Region<String, Object> createRegion(ClientCache cache, String regionName) {
        return cache
                .<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    private static void runCreate(Region<String, Object> region, String key, String value) {
        System.out.println("[CREATE] key=" + key + " value=" + value);
        region.put(key, value);
        System.out.println("  OK");
    }

    private static void runRead(Region<String, Object> region, String key, String expectedValue) {
        System.out.println("[READ] key=" + key);
        Object actualRaw = region.get(key);
        String actual = normalizeValue(actualRaw);
        System.out.println("  Returned value=" + actual + " (" + describeType(actualRaw) + ")");

        if (!expectedValue.equals(actual)) {
            throw new IllegalStateException("READ failed. Expected [" + expectedValue + "] but got [" + actual + "]");
        }

        System.out.println("  OK");
    }

    private static void runUpdate(Region<String, Object> region, String key, String updatedValue) {
        System.out.println("[UPDATE] key=" + key + " value=" + updatedValue);
        region.put(key, updatedValue);

        Object actualRaw = region.get(key);
        String actual = normalizeValue(actualRaw);
        System.out.println("  Returned updated value=" + actual + " (" + describeType(actualRaw) + ")");

        if (!updatedValue.equals(actual)) {
            throw new IllegalStateException("UPDATE failed. Expected [" + updatedValue + "] but got [" + actual + "]");
        }

        System.out.println("  OK");
    }

    private static void runDelete(Region<String, Object> region, String key) {
        System.out.println("[DELETE] key=" + key);

        try {
            region.remove(key);
            System.out.println("  Remove call returned normally");
        } catch (Exception e) {
            System.out.println("  Remove call threw expected protocol-gap exception: " + e.getClass().getName());
            System.out.println("  Continuing to verify backend state...");
        }
    }

    private static void runContainsEquivalent(Region<String, Object> region, String key, boolean expected) {
        System.out.println("[EXISTS_CHECK] key=" + key);

        Object actualRaw = region.get(key);
        String actual = normalizeValue(actualRaw);
        boolean exists = actualRaw != null && actual != null;

        System.out.println("  Exists=" + exists + " (" + describeType(actualRaw) + ")");

        if (exists != expected) {
            throw new IllegalStateException("EXISTS_CHECK failed. Expected [" + expected + "] but got [" + exists + "]");
        }

        System.out.println("  OK");
    }

    private static String normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof byte[] bytes) {
            return decodeGeodeStringLike(bytes);
        }
        return String.valueOf(value);
    }

    private static String decodeGeodeStringLike(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        String direct = new String(bytes, StandardCharsets.UTF_8);

        if (bytes.length >= 3 && bytes[0] == 0x57) {
            int declaredLen = bytes[1] & 0xFF;

            if (declaredLen == bytes.length - 2) {
                return new String(bytes, 2, declaredLen, StandardCharsets.UTF_8);
            }

            if (bytes.length > 2) {
                return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_8);
            }
        }

        return direct;
    }

    private static String describeType(Object value) {
        return value == null ? "null" : value.getClass().getName();
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