package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import org.apache.geode.DataSerializer;
import org.apache.geode.pdx.internal.PdxType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Assigns stable ids to PDX types (by content fingerprint) and keeps the deserialized
 * {@link PdxType} per id so queries can resolve PDX instance fields by name. The id is what the
 * client embeds in PDX instances it later writes, so an instance's type id maps back to its layout.
 */
public class PdxTypeRegistry {

    private final ConcurrentMap<String, Integer> typeIdsByFingerprint = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, PdxType> typesById = new ConcurrentHashMap<>();
    private final AtomicInteger nextTypeId = new AtomicInteger(1);

    /** Maximum distinct PDX types to register; {@code 0} = unlimited (the default). */
    private final int maxTypes;
    /** Invoked once when a new-type registration is rejected for hitting the cap (metric + audit). */
    private final Runnable onCapExceeded;
    /**
     * Optional persistence backend (1.3.0-M2). When non-null and {@code PDX_PERSISTENCE} is on, ids are
     * allocated from a cluster-wide durable counter and types load on a local miss, so ids are consistent
     * across replicas and survive a restart. When null (or the flag is off) the local {@link #nextTypeId}
     * counter is used — single-instance behavior is byte-identical.
     */
    private final Repository repository;

    public PdxTypeRegistry() {
        this(0, () -> { }, null);
    }

    public PdxTypeRegistry(int maxTypes, Runnable onCapExceeded) {
        this(maxTypes, onCapExceeded, null);
    }

    public PdxTypeRegistry(int maxTypes, Runnable onCapExceeded, Repository repository) {
        this.maxTypes = Math.max(0, maxTypes);
        this.onCapExceeded = onCapExceeded == null ? () -> { } : onCapExceeded;
        this.repository = repository;
    }

    public int getOrCreateTypeId(byte[] encodedPdxType) {
        if (encodedPdxType == null || encodedPdxType.length == 0) {
            throw new IllegalArgumentException("encodedPdxType must not be null or empty");
        }

        String fingerprint = sha256Hex(encodedPdxType);
        Integer known = typeIdsByFingerprint.get(fingerprint);
        if (known != null) {
            return known; // an already-registered type is always served, regardless of the cap
        }
        if (maxTypes > 0 && typeIdsByFingerprint.size() >= maxTypes) {
            onCapExceeded.run();
            throw new PdxRegistryCapExceededException(
                    "PDX type registry cap reached (" + maxTypes + "); set MAX_PDX_TYPES to raise it");
        }

        // With persistence on, allocate the id from the cluster-wide durable registry so it is consistent
        // across replicas and survives a restart; the same fingerprint always resolves to the same id.
        // When persistence is off (or no backend), allocatePdxTypeId returns empty and we fall back to the
        // local counter — single-instance behavior unchanged. The id-doc stored here is the client's raw
        // encoded type; the assigned id is stamped onto the PdxType at read time (see localOrLoaded).
        int typeId;
        OptionalInt persisted =
                repository == null ? OptionalInt.empty() : repository.allocatePdxTypeId(fingerprint, encodedPdxType);
        if (persisted.isPresent()) {
            typeId = persisted.getAsInt();
            typeIdsByFingerprint.put(fingerprint, typeId);
        } else {
            typeId = typeIdsByFingerprint.computeIfAbsent(fingerprint, ignored -> nextTypeId.getAndIncrement());
        }

        // Keep the parsed type so query field access can read instance fields by name (best-effort:
        // if the PdxType cannot be deserialized, field access for that type simply degrades). Stamp it
        // with the assigned id so it round-trips correctly when served back via GET_PDX_TYPE_BY_ID.
        PdxType parsed = deserialize(encodedPdxType);
        if (parsed != null) {
            parsed.setTypeId(typeId);
            typesById.putIfAbsent(typeId, parsed);
        }
        return typeId;
    }

    /**
     * The PdxType registered for an id, or {@code null} if unknown / not parseable. When persistence is on
     * and the id is not in this instance's memory (a restart, or a type another replica registered), the
     * raw type is loaded from Couchbase, stamped with the id, and cached — so any replica resolves any id.
     */
    public PdxType getPdxType(int typeId) {
        PdxType local = typesById.get(typeId);
        if (local != null || repository == null) {
            return local;
        }
        byte[] raw = repository.loadPdxType(typeId);
        if (raw == null) {
            return null;
        }
        PdxType parsed = deserialize(raw);
        if (parsed == null) {
            return null;
        }
        parsed.setTypeId(typeId);
        PdxType existing = typesById.putIfAbsent(typeId, parsed);
        return existing != null ? existing : parsed;
    }

    /**
     * The kept {@link PdxType} for {@code typeId} re-serialized in DataSerializer form (for the
     * GET_PDX_TYPE_BY_ID reply), or {@code null} when the id is unknown / cannot be serialized.
     */
    public byte[] serializedPdxType(int typeId) {
        PdxType type = getPdxType(typeId); // load-on-miss when persistence is on (cross-replica / post-restart)
        if (type == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataSerializer.writeObject(type, new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /**
     * Every registered type id mapped to its {@link PdxType} re-serialized in DataSerializer form, for
     * the bulk GET_PDX_TYPES registry-discovery reply. With persistence on, this unions the ids this
     * instance has seen with the whole persisted registry, so a fresh replica (or one after a restart)
     * serves the complete cluster-wide registry — not just what it happens to hold in memory. Types that
     * cannot be re-serialized are skipped.
     */
    public java.util.Map<Integer, byte[]> allSerializedTypes() {
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>(typesById.keySet());
        if (repository != null) {
            ids.addAll(repository.loadAllPdxTypes().keySet());
        }
        java.util.LinkedHashMap<Integer, byte[]> out = new java.util.LinkedHashMap<>();
        for (Integer id : ids) {
            byte[] serialized = serializedPdxType(id); // load-on-miss + stamp for persisted-only ids
            if (serialized != null) {
                out.put(id, serialized);
            }
        }
        return out;
    }

    public int size() {
        return typeIdsByFingerprint.size();
    }

    private static PdxType deserialize(byte[] encodedPdxType) {
        try {
            Object object = DataSerializer.readObject(
                    new DataInputStream(new ByteArrayInputStream(encodedPdxType)));
            return object instanceof PdxType ? (PdxType) object : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }
}
