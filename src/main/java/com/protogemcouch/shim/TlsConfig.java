package com.protogemcouch.shim;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * TLS configuration for the inbound Geode listener.
 *
 * <p>When enabled, the shim terminates TLS on the Geode port using a server keystore. Optionally it
 * can require client certificates (mutual TLS) as a transport-level client-authentication model,
 * verifying them against a truststore.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code TLS_ENABLED} (default {@code false})</li>
 *   <li>{@code TLS_KEYSTORE_PATH}, {@code TLS_KEYSTORE_PASSWORD}, {@code TLS_KEYSTORE_TYPE} (default PKCS12)</li>
 *   <li>{@code TLS_CLIENT_AUTH} ({@code none} default, or {@code require} for mutual TLS)</li>
 *   <li>{@code TLS_TRUSTSTORE_PATH}, {@code TLS_TRUSTSTORE_PASSWORD}, {@code TLS_TRUSTSTORE_TYPE} (required when client auth is {@code require})</li>
 * </ul>
 */
public final class TlsConfig {

    private final boolean enabled;
    private final String keystorePath;
    private final String keystorePassword;
    private final String keystoreType;
    private final boolean requireClientAuth;
    private final String truststorePath;
    private final String truststorePassword;
    private final String truststoreType;

    public TlsConfig(boolean enabled,
                     String keystorePath, String keystorePassword, String keystoreType,
                     boolean requireClientAuth,
                     String truststorePath, String truststorePassword, String truststoreType) {
        this.enabled = enabled;
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.keystoreType = (keystoreType == null || keystoreType.isBlank()) ? "PKCS12" : keystoreType;
        this.requireClientAuth = requireClientAuth;
        this.truststorePath = truststorePath;
        this.truststorePassword = truststorePassword;
        this.truststoreType = (truststoreType == null || truststoreType.isBlank()) ? "PKCS12" : truststoreType;
    }

    public static TlsConfig fromEnv() {
        boolean enabled = Boolean.parseBoolean(envOrDefault("TLS_ENABLED", "false"));
        boolean requireClientAuth = "require".equalsIgnoreCase(envOrDefault("TLS_CLIENT_AUTH", "none"));
        return new TlsConfig(
                enabled,
                System.getenv("TLS_KEYSTORE_PATH"),
                System.getenv("TLS_KEYSTORE_PASSWORD"),
                System.getenv("TLS_KEYSTORE_TYPE"),
                requireClientAuth,
                System.getenv("TLS_TRUSTSTORE_PATH"),
                System.getenv("TLS_TRUSTSTORE_PASSWORD"),
                System.getenv("TLS_TRUSTSTORE_TYPE"));
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean requireClientAuth() {
        return requireClientAuth;
    }

    public String keystorePath() {
        return keystorePath;
    }

    /**
     * Build the Netty server {@link SslContext} from the configured keystore (and truststore, if
     * mutual TLS is required). Fails fast with a clear message if required inputs are missing.
     */
    public SslContext buildServerSslContext() throws Exception {
        if (keystorePath == null || keystorePath.isBlank()) {
            throw new IllegalStateException("TLS is enabled but TLS_KEYSTORE_PATH is not set");
        }

        char[] keystorePass = keystorePassword == null ? new char[0] : keystorePassword.toCharArray();
        KeyStore keyStore = KeyStore.getInstance(keystoreType);
        try (InputStream in = Files.newInputStream(Path.of(keystorePath))) {
            keyStore.load(in, keystorePass);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePass);

        SslContextBuilder builder = SslContextBuilder.forServer(kmf).sslProvider(SslProvider.JDK);

        if (requireClientAuth) {
            if (truststorePath == null || truststorePath.isBlank()) {
                throw new IllegalStateException(
                        "TLS_CLIENT_AUTH=require but TLS_TRUSTSTORE_PATH is not set");
            }
            char[] truststorePass = truststorePassword == null ? new char[0] : truststorePassword.toCharArray();
            KeyStore trustStore = KeyStore.getInstance(truststoreType);
            try (InputStream in = Files.newInputStream(Path.of(truststorePath))) {
                trustStore.load(in, truststorePass);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            builder.trustManager(tmf).clientAuth(ClientAuth.REQUIRE);
        }

        return builder.build();
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public String toString() {
        return "TlsConfig{enabled=" + enabled
                + ", keystoreType=" + keystoreType
                + ", requireClientAuth=" + requireClientAuth + '}';
    }
}
