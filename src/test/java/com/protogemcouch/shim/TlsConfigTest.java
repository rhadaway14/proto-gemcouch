package com.protogemcouch.shim;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TlsConfigTest {

    private static final String KEYSTORE = "certs/server-keystore.p12";
    private static final String TRUSTSTORE = "certs/client-truststore.p12";
    private static final String PASS = "changeit";

    @Test
    void disabledByDefault() {
        TlsConfig config = new TlsConfig(false, null, null, null, false, null, null, null);
        assertFalse(config.enabled());
        assertFalse(config.requireClientAuth());
    }

    @Test
    void buildsServerContextFromKeystore() throws Exception {
        assumeTrue(Files.exists(Path.of(KEYSTORE)), "test keystore present");

        TlsConfig config = new TlsConfig(true, KEYSTORE, PASS, "PKCS12", false, null, null, null);
        SslContext context = config.buildServerSslContext();

        assertNotNull(context);
        assertTrue(context.isServer(), "built context must be a server SSL context");
    }

    @Test
    void failsFastWhenEnabledWithoutKeystore() {
        TlsConfig config = new TlsConfig(true, null, null, null, false, null, null, null);
        assertThrows(IllegalStateException.class, config::buildServerSslContext);
    }

    @Test
    void mutualTlsRequiresTruststore() {
        assumeTrue(Files.exists(Path.of(KEYSTORE)), "test keystore present");

        TlsConfig config = new TlsConfig(true, KEYSTORE, PASS, "PKCS12", true, null, null, null);
        assertThrows(IllegalStateException.class, config::buildServerSslContext);
    }

    @Test
    void mutualTlsBuildsWithTruststore() throws Exception {
        assumeTrue(Files.exists(Path.of(KEYSTORE)) && Files.exists(Path.of(TRUSTSTORE)),
                "test keystore and truststore present");

        TlsConfig config = new TlsConfig(true, KEYSTORE, PASS, "PKCS12", true, TRUSTSTORE, PASS, "PKCS12");
        SslContext context = config.buildServerSslContext();

        assertNotNull(context);
        assertTrue(context.isServer());
        assertTrue(config.requireClientAuth());
    }
}
