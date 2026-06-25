package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.testsupport.FakeRepository;
import com.protogemcouch.util.ByteUtils;
import org.apache.geode.DataSerializer;
import org.apache.geode.pdx.internal.PdxType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the reverse PDX lookup the shim serves for GET_PDX_TYPE_BY_ID (92): the kept type must
 * re-serialize to a {@link PdxType} stamped with the assigned id and the original field layout, so a
 * second client can decode a PDX value (e.g. a CQ event value) it did not itself write. Uses real
 * PdxType bytes captured from a Geode 1.15 server (a {@code demo.Order} with String {@code status} and
 * int {@code amount}) — the same blob as {@link PdxFieldAccessorTest}.
 */
class PdxTypeRegistryTest {

    private static final byte[] PDX_TYPE = ByteUtils.hex(
            "2d2b5700256f72672e6170616368652e67656f64652e7064782e696e7465726e616c2e5064785479706557000a64656d6f"
                    + "2e4f726465720000000000000000000257000673746174757300000000000000000900000000ffffffff0057000661"
                    + "6d6f756e74000000010000000004fffffffcffffffff00");

    @Test
    void serializedTypeRoundTripsWithAssignedIdAndFields() throws Exception {
        PdxTypeRegistry registry = new PdxTypeRegistry();
        int typeId = registry.getOrCreateTypeId(PDX_TYPE);

        byte[] serialized = registry.serializedPdxType(typeId);
        assertNotNull(serialized, "the kept type re-serializes for the reverse-lookup reply");

        PdxType decoded = (PdxType) DataSerializer.readObject(
                new DataInputStream(new ByteArrayInputStream(serialized)));
        assertEquals(typeId, decoded.getTypeId(),
                "the served type carries the shim-assigned id so the client caches it correctly");
        assertEquals("demo.Order", decoded.getClassName());
        assertNotNull(decoded.getPdxField("status"), "field layout survives the round-trip");
        assertNotNull(decoded.getPdxField("amount"));
    }

    @Test
    void serializedTypeIsNullForUnknownId() {
        PdxTypeRegistry registry = new PdxTypeRegistry();
        registry.getOrCreateTypeId(PDX_TYPE);
        assertNull(registry.serializedPdxType(999), "unknown type id has no serialized form");
    }

    // --- 1.3.0-M2: persistence-backed id allocation + cross-instance (multi-replica/restart) resolution ---

    @Test
    void usesTheClusterWideIdWhenTheRepositoryAllocates() {
        FakePdxRepository repo = new FakePdxRepository(4242);
        PdxTypeRegistry registry = new PdxTypeRegistry(0, () -> { }, repo);

        int id = registry.getOrCreateTypeId(PDX_TYPE);
        assertEquals(4242, id, "the id comes from the durable cluster-wide registry, not the local counter");
        assertEquals(4242, registry.getOrCreateTypeId(PDX_TYPE), "the same type is idempotent");
        assertEquals("demo.Order", registry.getPdxType(4242).getClassName());
    }

    @Test
    void resolvesAnIdRegisteredByAnotherInstance() throws Exception {
        // Replica A registers; the shared (Couchbase-like) backend persists it. Replica B, which never
        // saw the registration, resolves the type by id via load-on-miss — the multi-replica fix.
        FakePdxRepository shared = new FakePdxRepository(7);
        PdxTypeRegistry replicaA = new PdxTypeRegistry(0, () -> { }, shared);
        int id = replicaA.getOrCreateTypeId(PDX_TYPE);

        PdxTypeRegistry replicaB = new PdxTypeRegistry(0, () -> { }, shared);
        PdxType resolved = replicaB.getPdxType(id);
        assertNotNull(resolved, "replica B loads the type it never registered");
        assertEquals(id, resolved.getTypeId(), "the loaded type is stamped with the cluster-wide id");
        assertEquals("demo.Order", resolved.getClassName());

        byte[] serialized = replicaB.serializedPdxType(id);
        assertNotNull(serialized, "GET_PDX_TYPE_BY_ID resolves on replica B via load-on-miss");
        PdxType decoded = (PdxType) DataSerializer.readObject(
                new DataInputStream(new ByteArrayInputStream(serialized)));
        assertEquals(id, decoded.getTypeId());
    }

    @Test
    void fallsBackToLocalCounterWhenPersistenceIsOff() {
        // A repository with persistence off returns empty from allocate / null from load (the default
        // no-op behavior), so the registry uses its local counter — single-instance behavior unchanged.
        Repository off = new FakeRepository();
        PdxTypeRegistry registry = new PdxTypeRegistry(0, () -> { }, off);
        assertEquals(1, registry.getOrCreateTypeId(PDX_TYPE), "local counter starts at 1 when persistence is off");
        assertTrue(registry.serializedPdxType(1).length > 0);
    }

    /** A minimal in-memory stand-in for the Couchbase-backed PDX persistence (the only methods used). */
    private static final class FakePdxRepository extends FakeRepository {
        private final Map<String, Integer> idByFingerprint = new LinkedHashMap<>();
        private final Map<Integer, byte[]> bytesById = new LinkedHashMap<>();
        private final AtomicInteger seq;

        FakePdxRepository(int firstId) {
            this.seq = new AtomicInteger(firstId);
        }

        @Override
        public synchronized OptionalInt allocatePdxTypeId(String fingerprint, byte[] serializedType) {
            int id = idByFingerprint.computeIfAbsent(fingerprint, fp -> seq.getAndIncrement());
            bytesById.putIfAbsent(id, serializedType.clone());
            return OptionalInt.of(id);
        }

        @Override
        public synchronized byte[] loadPdxType(int typeId) {
            byte[] b = bytesById.get(typeId);
            return b == null ? null : b.clone();
        }
    }
}
