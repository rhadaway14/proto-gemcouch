package com.protogemcouch.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerConfigSecretTest {

    @Test
    void usesDirectValueWhenNoFile() {
        assertEquals("direct", ServerConfig.resolveSecret("direct", null));
        assertEquals("direct", ServerConfig.resolveSecret("direct", "  "));
        assertNull(ServerConfig.resolveSecret(null, null));
    }

    @Test
    void readsFromFileWhenFilePathSet(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("cb-password");
        Files.writeString(secret, "s3cr3t-from-file\n");

        // The file value takes precedence and trailing whitespace/newline is stripped.
        assertEquals("s3cr3t-from-file", ServerConfig.resolveSecret("ignored-direct", secret.toString()));
    }

    @Test
    void missingSecretFileFailsFast() {
        assertThrows(ConfigException.class,
                () -> ServerConfig.resolveSecret("direct", "/no/such/secret/file"));
    }
}
