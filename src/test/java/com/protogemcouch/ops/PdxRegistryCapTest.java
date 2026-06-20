package com.protogemcouch.ops;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The optional PDX type/enum registry caps bound in-memory growth: a new type/enum past the cap is
 * rejected (with the reject callback fired for the metric + audit), an already-registered one is still
 * served regardless of the cap, and the default (0 = unlimited) never rejects.
 */
class PdxRegistryCapTest {

    // Each payload is distinct (so it registers as a distinct type/enum by fingerprint) but starts with
    // the Geode NULL marker (0x29) so the registry's best-effort DataSerializer.readObject returns null
    // immediately — the cap logic under test is independent of whether the payload is a real PdxType.
    private static byte[] bytes(int n) {
        return new byte[] {0x29, (byte) n, (byte) (n >>> 8), (byte) (n >>> 16)};
    }

    @Test
    void typeRegistryRejectsNewTypesAtCapButStillServesKnownOnes() {
        AtomicInteger rejects = new AtomicInteger();
        PdxTypeRegistry registry = new PdxTypeRegistry(2, rejects::incrementAndGet);

        int id1 = registry.getOrCreateTypeId(bytes(1));
        registry.getOrCreateTypeId(bytes(2)); // now at the cap of 2

        // A known type is always served, even at the cap, and keeps its id.
        assertEquals(id1, registry.getOrCreateTypeId(bytes(1)));
        assertEquals(0, rejects.get());

        // A brand-new type past the cap is rejected and the callback fires once.
        assertThrows(PdxRegistryCapExceededException.class, () -> registry.getOrCreateTypeId(bytes(3)));
        assertEquals(1, rejects.get());
        assertEquals(2, registry.size(), "the rejected type is not registered");
    }

    @Test
    void unlimitedTypeRegistryNeverRejects() {
        PdxTypeRegistry registry = new PdxTypeRegistry(); // 0 = unlimited
        for (int i = 0; i < 50; i++) {
            int n = i;
            assertDoesNotThrow(() -> registry.getOrCreateTypeId(bytes(n)));
        }
        assertEquals(50, registry.size());
    }

    @Test
    void enumRegistryRejectsNewEnumsAtCapButStillServesKnownOnes() {
        AtomicInteger rejects = new AtomicInteger();
        PdxEnumRegistry registry = new PdxEnumRegistry(1, rejects::incrementAndGet);

        int id1 = registry.getOrCreateEnumId(bytes(1)); // at the cap of 1
        assertEquals(id1, registry.getOrCreateEnumId(bytes(1))); // known enum still served
        assertEquals(0, rejects.get());

        assertThrows(PdxRegistryCapExceededException.class, () -> registry.getOrCreateEnumId(bytes(2)));
        assertEquals(1, rejects.get());
        assertEquals(1, registry.size());
    }

    @Test
    void unlimitedEnumRegistryNeverRejects() {
        PdxEnumRegistry registry = new PdxEnumRegistry();
        for (int i = 0; i < 20; i++) {
            int n = i;
            assertDoesNotThrow(() -> registry.getOrCreateEnumId(bytes(n)));
        }
        assertEquals(20, registry.size());
    }
}
