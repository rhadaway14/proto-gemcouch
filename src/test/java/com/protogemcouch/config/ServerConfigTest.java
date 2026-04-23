package com.protogemcouch.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    @Test
    void constructor_accepts_valid_values() {
        ServerConfig config = new ServerConfig(
                "couchbase://127.0.0.1",
                "Administrator",
                "password",
                "test",
                "_default",
                "_default",
                40405
        );

        assertEquals("couchbase://127.0.0.1", config.getCouchbaseConnectionString());
        assertEquals("Administrator", config.getCouchbaseUsername());
        assertEquals("password", config.getCouchbasePassword());
        assertEquals("test", config.getCouchbaseBucket());
        assertEquals("_default", config.getCouchbaseScope());
        assertEquals("_default", config.getCouchbaseCollection());
        assertEquals(40405, config.getShimPort());
    }

    @Test
    void constructor_rejects_blank_connection_string() {
        ConfigException ex = assertThrows(ConfigException.class, () -> new ServerConfig(
                "   ",
                "Administrator",
                "password",
                "test",
                "_default",
                "_default",
                40405
        ));

        assertTrue(ex.getMessage().contains("CB_CONNSTR"));
    }

    @Test
    void constructor_rejects_blank_username() {
        ConfigException ex = assertThrows(ConfigException.class, () -> new ServerConfig(
                "couchbase://127.0.0.1",
                "",
                "password",
                "test",
                "_default",
                "_default",
                40405
        ));

        assertTrue(ex.getMessage().contains("CB_USERNAME"));
    }

    @Test
    void constructor_rejects_blank_password() {
        ConfigException ex = assertThrows(ConfigException.class, () -> new ServerConfig(
                "couchbase://127.0.0.1",
                "Administrator",
                "",
                "test",
                "_default",
                "_default",
                40405
        ));

        assertTrue(ex.getMessage().contains("CB_PASSWORD"));
    }

    @Test
    void constructor_rejects_invalid_low_port() {
        ConfigException ex = assertThrows(ConfigException.class, () -> new ServerConfig(
                "couchbase://127.0.0.1",
                "Administrator",
                "password",
                "test",
                "_default",
                "_default",
                0
        ));

        assertTrue(ex.getMessage().contains("SHIM_PORT"));
    }

    @Test
    void constructor_rejects_invalid_high_port() {
        ConfigException ex = assertThrows(ConfigException.class, () -> new ServerConfig(
                "couchbase://127.0.0.1",
                "Administrator",
                "password",
                "test",
                "_default",
                "_default",
                70000
        ));

        assertTrue(ex.getMessage().contains("SHIM_PORT"));
    }

    @Test
    void toSafeLogString_redacts_secret_values() {
        ServerConfig config = new ServerConfig(
                "couchbase://127.0.0.1",
                "Administrator",
                "password",
                "test",
                "_default",
                "_default",
                40405
        );

        String safe = config.toSafeLogString();

        assertTrue(safe.contains("connstr=couchbase://127.0.0.1"));
        assertTrue(safe.contains("bucket=test"));
        assertTrue(safe.contains("password=***"));
        assertFalse(safe.contains("password=password"));
    }
}