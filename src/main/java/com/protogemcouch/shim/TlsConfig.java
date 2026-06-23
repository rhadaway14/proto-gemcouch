package com.protogemcouch.shim;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;

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

    /**
     * Default enabled TLS protocols: modern TLS only. Weak/legacy protocols (SSLv3, TLS 1.0, TLS 1.1)
     * are excluded by default; operators can narrow further (e.g. {@code TLSv1.3}) via TLS_PROTOCOLS.
     */
    static final List<String> DEFAULT_PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

    private final boolean enabled;
    private final boolean healthTlsEnabled;
    private final String keystorePath;
    private final String keystorePassword;
    private final String keystoreType;
    private final boolean requireClientAuth;
    private final String truststorePath;
    private final String truststorePassword;
    private final String truststoreType;
    private final List<String> protocols;
    private final List<String> cipherSuites;

    public TlsConfig(boolean enabled,
                     String keystorePath, String keystorePassword, String keystoreType,
                     boolean requireClientAuth,
                     String truststorePath, String truststorePassword, String truststoreType) {
        this(enabled, false, keystorePath, keystorePassword, keystoreType,
                requireClientAuth, truststorePath, truststorePassword, truststoreType);
    }

    public TlsConfig(boolean enabled, boolean healthTlsEnabled,
                     String keystorePath, String keystorePassword, String keystoreType,
                     boolean requireClientAuth,
                     String truststorePath, String truststorePassword, String truststoreType) {
        this(enabled, healthTlsEnabled, keystorePath, keystorePassword, keystoreType,
                requireClientAuth, truststorePath, truststorePassword, truststoreType,
                DEFAULT_PROTOCOLS, List.of());
    }

    public TlsConfig(boolean enabled, boolean healthTlsEnabled,
                     String keystorePath, String keystorePassword, String keystoreType,
                     boolean requireClientAuth,
                     String truststorePath, String truststorePassword, String truststoreType,
                     List<String> protocols, List<String> cipherSuites) {
        this.enabled = enabled;
        this.healthTlsEnabled = healthTlsEnabled;
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.keystoreType = (keystoreType == null || keystoreType.isBlank()) ? "PKCS12" : keystoreType;
        this.requireClientAuth = requireClientAuth;
        this.truststorePath = truststorePath;
        this.truststorePassword = truststorePassword;
        this.truststoreType = (truststoreType == null || truststoreType.isBlank()) ? "PKCS12" : truststoreType;
        this.protocols = (protocols == null || protocols.isEmpty()) ? DEFAULT_PROTOCOLS : List.copyOf(protocols);
        this.cipherSuites = cipherSuites == null ? List.of() : List.copyOf(cipherSuites);
    }

    public static TlsConfig fromEnv() {
        boolean enabled = Boolean.parseBoolean(envOrDefault("TLS_ENABLED", "false"));
        boolean healthTlsEnabled = Boolean.parseBoolean(envOrDefault("HEALTH_TLS_ENABLED", "false"));
        boolean requireClientAuth = "require".equalsIgnoreCase(envOrDefault("TLS_CLIENT_AUTH", "none"));
        return new TlsConfig(
                enabled,
                healthTlsEnabled,
                System.getenv("TLS_KEYSTORE_PATH"),
                System.getenv("TLS_KEYSTORE_PASSWORD"),
                System.getenv("TLS_KEYSTORE_TYPE"),
                requireClientAuth,
                System.getenv("TLS_TRUSTSTORE_PATH"),
                System.getenv("TLS_TRUSTSTORE_PASSWORD"),
                System.getenv("TLS_TRUSTSTORE_TYPE"),
                parseList(System.getenv("TLS_PROTOCOLS")),
                parseList(System.getenv("TLS_CIPHERS")));
    }

    /** Parse a comma-separated list into trimmed, non-empty entries (null/blank -> empty list). */
    static List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** The enabled TLS protocols (never empty; defaults to {@link #DEFAULT_PROTOCOLS}). */
    public String[] enabledProtocols() {
        return protocols.toArray(new String[0]);
    }

    /** The enabled cipher suites, or {@code null} to use the provider's defaults (none configured). */
    public String[] enabledCipherSuites() {
        return cipherSuites.isEmpty() ? null : cipherSuites.toArray(new String[0]);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean requireClientAuth() {
        return requireClientAuth;
    }

    public boolean healthTlsEnabled() {
        return healthTlsEnabled;
    }

    public String keystorePath() {
        return keystorePath;
    }

    public String truststorePath() {
        return truststorePath;
    }

    /**
     * Poll interval (seconds) for hot-reloading the keystore/truststore without a restart (1.2.0-M3),
     * from {@code TLS_RELOAD_SECONDS}. {@code 0} (default) disables hot reload — rotation is then a
     * rolling restart, as before.
     */
    public static long reloadIntervalSecondsFromEnv() {
        String raw = System.getenv("TLS_RELOAD_SECONDS");
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
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

        // Pin the protocol set (modern TLS only) and, when configured, the cipher allowlist, instead of
        // relying on the JVM defaults — an explicit, auditable, operator-controllable policy.
        builder.protocols(enabledProtocols());
        if (!cipherSuites.isEmpty()) {
            builder.ciphers(cipherSuites);
        }

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

    /**
     * Build a JDK {@link SSLContext} from the configured server keystore, for serving the
     * health/admin endpoints over HTTPS. Fails fast if no keystore is configured.
     */
    public SSLContext buildJdkSslContext() throws Exception {
        if (keystorePath == null || keystorePath.isBlank()) {
            throw new IllegalStateException("HEALTH_TLS_ENABLED is set but TLS_KEYSTORE_PATH is not set");
        }
        char[] pass = keystorePassword == null ? new char[0] : keystorePassword.toCharArray();
        KeyStore keyStore = KeyStore.getInstance(keystoreType);
        try (InputStream in = Files.newInputStream(Path.of(keystorePath))) {
            keyStore.load(in, pass);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, pass);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public String toString() {
        return "TlsConfig{enabled=" + enabled
                + ", keystoreType=" + keystoreType
                + ", requireClientAuth=" + requireClientAuth
                + ", protocols=" + protocols
                + ", cipherSuites=" + (cipherSuites.isEmpty() ? "<provider-default>" : cipherSuites) + '}';
    }
}
