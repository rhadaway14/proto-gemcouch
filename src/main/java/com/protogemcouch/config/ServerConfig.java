package com.protogemcouch.config;

public class ServerConfig {

    private final String couchbaseConnectionString;
    private final String couchbaseUsername;
    private final String couchbasePassword;
    private final String couchbaseBucket;
    private final String couchbaseScope;
    private final String couchbaseCollection;
    private final int shimPort;
    private final int healthPort;

    public ServerConfig(String couchbaseConnectionString,
                        String couchbaseUsername,
                        String couchbasePassword,
                        String couchbaseBucket,
                        String couchbaseScope,
                        String couchbaseCollection,
                        int shimPort,
                        int healthPort) {
        this.couchbaseConnectionString = requireNonBlank("CB_CONNSTR", couchbaseConnectionString);
        this.couchbaseUsername = requireNonBlank("CB_USERNAME", couchbaseUsername);
        this.couchbasePassword = requireNonBlank("CB_PASSWORD", couchbasePassword);
        this.couchbaseBucket = requireNonBlank("CB_BUCKET", couchbaseBucket);
        this.couchbaseScope = requireNonBlank("CB_SCOPE", couchbaseScope);
        this.couchbaseCollection = requireNonBlank("CB_COLLECTION", couchbaseCollection);
        this.shimPort = validatePort("SHIM_PORT", shimPort);
        this.healthPort = validatePort("HEALTH_PORT", healthPort);

        if (this.shimPort == this.healthPort) {
            throw new ConfigException("HEALTH_PORT must be different from SHIM_PORT");
        }
    }

    public static ServerConfig fromEnv() {
        return new ServerConfig(
                env("CB_CONNSTR"),
                env("CB_USERNAME"),
                env("CB_PASSWORD"),
                env("CB_BUCKET"),
                env("CB_SCOPE"),
                env("CB_COLLECTION"),
                parsePort("SHIM_PORT", envOrDefault("SHIM_PORT", "40405")),
                parsePort("HEALTH_PORT", envOrDefault("HEALTH_PORT", "8081"))
        );
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

    public int getShimPort() {
        return shimPort;
    }

    public int getHealthPort() {
        return healthPort;
    }

    public String toSafeLogString() {
        return "connstr=" + couchbaseConnectionString
                + " bucket=" + couchbaseBucket
                + " scope=" + couchbaseScope
                + " collection=" + couchbaseCollection
                + " username=" + redact(couchbaseUsername)
                + " password=" + redactSecret(couchbasePassword)
                + " shimPort=" + shimPort
                + " healthPort=" + healthPort;
    }

    private static String env(String name) {
        return System.getenv(name);
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }

    private static String requireNonBlank(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new ConfigException("Missing required configuration: " + name);
        }
        return value.trim();
    }

    private static int parsePort(String name, String rawValue) {
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (Exception e) {
            throw new ConfigException("Invalid " + name + " value: " + rawValue, e);
        }
    }

    private static int validatePort(String name, int port) {
        if (port < 1 || port > 65535) {
            throw new ConfigException(name + " must be between 1 and 65535, but was: " + port);
        }
        return port;
    }

    private static String redact(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        if (value.length() <= 2) {
            return "**";
        }
        return value.charAt(0) + "***" + value.charAt(value.length() - 1);
    }

    private static String redactSecret(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        return "***";
    }
}