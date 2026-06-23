package com.protogemcouch.shim;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the hot TLS cert reloader (1.2.0-M3) against REAL PKCS12 keystores generated with keytool:
 * a content change rebuilds + swaps the SslContext, an unchanged keystore does not, and bad/partial
 * material is ignored (the old context is kept). No container or live server needed.
 */
class TlsCertReloaderTest {

    private static final String PASS = "changeit";

    @Test
    void rotatingTheKeystoreRebuildsAndSwapsTheContext(@TempDir Path dir) throws Exception {
        String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        Assumptions.assumeTrue(new File(keytool).exists() || new File(keytool + ".exe").exists(),
                "keytool required to generate test keystores");

        Path ksA = dir.resolve("a.p12");
        Path ksB = dir.resolve("b.p12");
        genKeystore(keytool, ksA, "CN=alpha");
        genKeystore(keytool, ksB, "CN=bravo");

        // The "live" keystore the server reads; starts as A.
        Path live = dir.resolve("server.p12");
        Files.copy(ksA, live, StandardCopyOption.REPLACE_EXISTING);

        TlsConfig config = new TlsConfig(true, live.toString(), PASS, "PKCS12",
                false, null, null, null);
        AtomicReference<SslContext> current = new AtomicReference<>();
        TlsCertReloader reloader = new TlsCertReloader(
                live.toString(), null, config::buildServerSslContext, current::set, 9999);

        // First check: no prior fingerprint -> loads A.
        assertTrue(reloader.checkOnce(), "initial material should build a context");
        assertNotNull(current.get());

        // No change -> no reload.
        assertFalse(reloader.checkOnce(), "unchanged keystore must not reload");

        // Rotate to B -> reload with a fresh context.
        SslContext beforeRotate = current.get();
        Files.copy(ksB, live, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(reloader.checkOnce(), "a rotated keystore must trigger a reload");
        assertNotSame(beforeRotate, current.get(), "the swapped context must be a new instance");

        // Corrupt/partial material -> no reload, keep the last good context.
        SslContext afterRotate = current.get();
        Files.write(live, new byte[] {0x00, 0x01, 0x02, 0x03});
        assertFalse(reloader.checkOnce(), "unreadable material must not reload");
        assertSame(afterRotate, current.get(), "the last good context is kept on a failed reload");
    }

    private static void genKeystore(String keytool, Path path, String dname) throws Exception {
        Process p = new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "shim", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", path.toString(),
                "-storepass", PASS, "-keypass", PASS, "-dname", dname)
                .redirectErrorStream(true)
                .start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("keytool timed out generating " + path);
        }
        if (p.exitValue() != 0 || !Files.exists(path)) {
            throw new IllegalStateException("keytool failed (exit " + p.exitValue() + ") for " + path);
        }
    }
}
