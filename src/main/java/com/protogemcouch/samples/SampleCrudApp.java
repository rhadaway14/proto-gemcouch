package com.protogemcouch.samples;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

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

            runContainsKeyOnServer(region, createKey, true);
            runContainsValueForKeyOnServer(region, createKey, true);

            runCreate(region, deleteKey, deleteValue);
            runContainsKeyOnServer(region, deleteKey, true);
            runContainsValueForKeyOnServer(region, deleteKey, true);

            runDelete(region, deleteKey);

            runContainsKeyOnServer(region, deleteKey, false);
            runContainsValueForKeyOnServer(region, deleteKey, false);

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
        region.remove(key);
        System.out.println("  OK");
    }

    private static void runContainsKeyOnServer(Region<String, Object> region, String key, boolean expected) {
        System.out.println("[CONTAINS_KEY_ON_SERVER] key=" + key);

        boolean exists = region.containsKeyOnServer(key);

        System.out.println("  Exists=" + exists);

        if (exists != expected) {
            throw new IllegalStateException(
                    "CONTAINS_KEY_ON_SERVER failed. Expected [" + expected + "] but got [" + exists + "]"
            );
        }

        System.out.println("  OK");
    }

    private static void runContainsValueForKeyOnServer(Region<String, Object> region, String key, boolean expected) {
        System.out.println("[CONTAINS_VALUE_FOR_KEY_ON_SERVER] key=" + key);

        boolean exists = containsValueForKeyOnServer(region, key);

        System.out.println("  Has value for key=" + exists);

        if (exists != expected) {
            throw new IllegalStateException(
                    "CONTAINS_VALUE_FOR_KEY_ON_SERVER failed. Expected [" + expected + "] but got [" + exists + "]"
            );
        }

        System.out.println("  OK");
    }

    private static boolean containsValueForKeyOnServer(Region<String, Object> region, String key) {
        try {
            Object serverProxy = region.getClass()
                    .getMethod("getServerProxy")
                    .invoke(region);

            if (serverProxy == null) {
                throw new IllegalStateException("Region does not have a server proxy");
            }

            Object result = serverProxy.getClass()
                    .getMethod("containsValueForKey", Object.class)
                    .invoke(serverProxy, key);

            if (!(result instanceof Boolean boolResult)) {
                throw new IllegalStateException(
                        "containsValueForKey returned unexpected type: "
                                + (result == null ? "null" : result.getClass().getName())
                );
            }

            return boolResult;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to invoke server-side containsValueForKey through Geode server proxy. "
                            + "Region type was: " + region.getClass().getName(),
                    e
            );
        }
    }

    private static String normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            if (s.length() >= 2 && s.charAt(0) == 'W') {
                return s.substring(2);
            }
            return s;
        }
        return String.valueOf(value);
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