package com.protogemcouch.shim;

import com.protogemcouch.observability.AuditLog;
import com.protogemcouch.observability.StructuredLog;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Hot-reloads the inbound TLS material (1.2.0-M3): polls the keystore (and the truststore, for mutual
 * TLS) for content changes and, when they change, rebuilds the Netty {@link SslContext} and hands it to
 * {@code onReload} — so the server can swap the context used for <em>new</em> connections without a
 * restart. Established TLS sessions are unaffected (their handshake already completed); only connections
 * accepted after the swap use the rotated material.
 *
 * <p>Polling a content fingerprint (not a file-watch) is deliberate: in Kubernetes a mounted Secret is
 * rotated by swapping the {@code ..data} symlink, which a {@code WatchService} on the file often misses,
 * whereas a content hash always sees it. A mid-rotation partial read (or a transient build failure) is
 * caught and the old context is kept, then retried on the next poll — TLS never breaks.
 *
 * <p>Decoupled from {@link TlsConfig} for testability: it takes the file paths to fingerprint and a
 * builder callable, so a test can drive {@link #checkOnce()} against real (or rotated) keystores.
 */
public final class TlsCertReloader {

    private static final Logger log = LoggerFactory.getLogger(TlsCertReloader.class);

    private final String keystorePath;
    private final String truststorePath; // null when not mutual TLS
    private final Callable<SslContext> contextBuilder;
    private final Consumer<SslContext> onReload;
    private final long intervalSeconds;

    private volatile String lastFingerprint;
    private ScheduledExecutorService scheduler;

    public TlsCertReloader(String keystorePath, String truststorePath,
                           Callable<SslContext> contextBuilder, Consumer<SslContext> onReload,
                           long intervalSeconds) {
        this.keystorePath = keystorePath;
        this.truststorePath = truststorePath;
        this.contextBuilder = contextBuilder;
        this.onReload = onReload;
        this.intervalSeconds = intervalSeconds;
    }

    /** Start polling at the configured interval. The initial fingerprint is captured so an unchanged
     *  cert never triggers a reload on the first tick. */
    public void start() {
        this.lastFingerprint = fingerprint();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pgc-tls-reload");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::checkOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info(StructuredLog.event("tls_cert_reload_watching",
                "keystore", keystorePath, "truststore", truststorePath, "intervalSeconds", intervalSeconds));
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * One reload check: if the keystore/truststore content changed since the last successful check,
     * rebuild the SslContext and hand it to {@code onReload}. Returns true iff a reload happened.
     * Package-private so a unit test can drive it deterministically. Never throws.
     */
    boolean checkOnce() {
        String current = fingerprint();
        if (current == null || current.equals(lastFingerprint)) {
            return false; // unreadable mid-rotation, or unchanged
        }
        try {
            SslContext rebuilt = contextBuilder.call();
            onReload.accept(rebuilt);
            lastFingerprint = current;
            log.info(StructuredLog.event("tls_cert_reloaded", "keystore", keystorePath));
            AuditLog.event("tls_cert_reloaded", "keystore", keystorePath,
                    "mutualTls", String.valueOf(truststorePath != null));
            return true;
        } catch (Exception e) {
            // Bad/partial material: keep the current context and retry on the next poll.
            log.warn(StructuredLog.event("tls_cert_reload_failed",
                    "keystore", keystorePath, "error", e.getMessage()));
            return false;
        }
    }

    /** SHA-256 over the keystore bytes (and truststore bytes, for mTLS); null if any file can't be read. */
    private String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(Path.of(keystorePath)));
            if (truststorePath != null && !truststorePath.isBlank()) {
                digest.update(Files.readAllBytes(Path.of(truststorePath)));
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return null; // file missing or mid-write — skip this tick
        }
    }
}
