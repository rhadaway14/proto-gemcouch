package com.protogemcouch.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StartupValidatorTest {

    @Test
    void validate_accepts_normal_config() {
        ServerConfig config = new ServerConfig(
                "couchbase://127.0.0.1",
                "Administrator",
                "password",
                "test",
                "_default",
                "_default",
                40415,
                40416
        );

        assertDoesNotThrow(() -> StartupValidator.validate(config));
    }

    @Test
    void validate_rejects_backticks_in_bucket() {
        ServerConfig config = new ServerConfig(
                "couchbase://127.0.0.1",
                "Administrator",
                "password",
                "bad`bucket",
                "_default",
                "_default",
                40415,
                40416
        );

        assertThrows(ConfigException.class, () -> StartupValidator.validate(config));
    }

    @Test
    void validate_rejects_backticks_in_scope() {
        ServerConfig config = new ServerConfig(
                "couchbase://127.0.0.1",
                "Administrator",
                "password",
                "test",
                "bad`scope",
                "_default",
                40415,
                40416
        );

        assertThrows(ConfigException.class, () -> StartupValidator.validate(config));
    }

    @Test
    void validate_rejects_backticks_in_collection() {
        ServerConfig config = new ServerConfig(
                "couchbase://127.0.0.1",
                "Administrator",
                "password",
                "test",
                "_default",
                "bad`collection",
                40415,
                40416
        );

        assertThrows(ConfigException.class, () -> StartupValidator.validate(config));
    }
}