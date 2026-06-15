package com.protogemcouch.shim;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void defaultsToModernProtocolsAndProviderDefaultCiphers() {
        TlsConfig config = new TlsConfig(true, KEYSTORE, PASS, "PKCS12", false, null, null, null);
        assertArrayEquals(new String[] {"TLSv1.3", "TLSv1.2"}, config.enabledProtocols(),
                "weak/legacy protocols are excluded by default");
        assertNull(config.enabledCipherSuites(), "no cipher restriction unless configured");
    }

    @Test
    void honorsConfiguredProtocolsAndCiphers() {
        TlsConfig config = new TlsConfig(true, false, KEYSTORE, PASS, "PKCS12", false, null, null, null,
                List.of("TLSv1.3"), List.of("TLS_AES_256_GCM_SHA384"));
        assertArrayEquals(new String[] {"TLSv1.3"}, config.enabledProtocols());
        assertArrayEquals(new String[] {"TLS_AES_256_GCM_SHA384"}, config.enabledCipherSuites());
    }

    @Test
    void parseListTrimsAndDropsBlanks() {
        assertEquals(List.of(), TlsConfig.parseList(null));
        assertEquals(List.of(), TlsConfig.parseList("  "));
        assertEquals(List.of("TLSv1.3", "TLSv1.2"), TlsConfig.parseList(" TLSv1.3 , ,TLSv1.2 "));
    }

    @Test
    void blankProtocolsFallBackToDefault() {
        TlsConfig config = new TlsConfig(true, false, KEYSTORE, PASS, "PKCS12", false, null, null, null,
                List.of(), null);
        assertArrayEquals(new String[] {"TLSv1.3", "TLSv1.2"}, config.enabledProtocols());
        assertNull(config.enabledCipherSuites());
    }
}
