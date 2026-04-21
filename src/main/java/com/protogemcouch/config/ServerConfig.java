package com.protogemcouch.config;

public class ServerConfig {

    private final int shimPort;
    private final String couchbaseConnectionString;
    private final String couchbaseUsername;
    private final String couchbasePassword;
    private final String couchbaseBucket;
    private final String couchbaseScope;
    private final String couchbaseCollection;

    public ServerConfig(int shimPort,
                        String couchbaseConnectionString,
                        String couchbaseUsername,
                        String couchbasePassword,
                        String couchbaseBucket,
                        String couchbaseScope,
                        String couchbaseCollection) {
        this.shimPort = shimPort;
        this.couchbaseConnectionString = couchbaseConnectionString;
        this.couchbaseUsername = couchbaseUsername;
        this.couchbasePassword = couchbasePassword;
        this.couchbaseBucket = couchbaseBucket;
        this.couchbaseScope = couchbaseScope;
        this.couchbaseCollection = couchbaseCollection;
    }

    public static ServerConfig fromEnv() {
        return new ServerConfig(
                envIntOrDefault("SHIM_PORT", 40405),
                envOrDefault("CB_CONNSTR", "couchbase://127.0.0.1"),
                envOrDefault("CB_USERNAME", "Administrator"),
                envOrDefault("CB_PASSWORD", "password"),
                envOrDefault("CB_BUCKET", "test"),
                envOrDefault("CB_SCOPE", "_default"),
                envOrDefault("CB_COLLECTION", "_default")
        );
    }

    public int getShimPort() {
        return shimPort;
    }

    public String getCouchbaseConnectionString() {
        return couchbaseConnectionString;
    }

    public String getCouchbaseUsername() {
        return couchbaseUsername;
    }

    public String getCouchbasePassword() {
        return couchbasePassword;
    }

    public String getCouchbaseBucket() {
        return couchbaseBucket;
    }

    public String getCouchbaseScope() {
        return couchbaseScope;
    }

    public String getCouchbaseCollection() {
        return couchbaseCollection;
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
            System.out.println("Invalid integer for env var " + name + ": " + value + ". Using default " + fallback);
            return fallback;
        }
    }
}