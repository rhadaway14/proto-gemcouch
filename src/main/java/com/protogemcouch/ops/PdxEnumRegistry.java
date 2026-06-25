package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PdxEnumRegistry {

    private final ConcurrentMap<String, Integer> enumIdsByFingerprint = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, byte[]> encodedById = new ConcurrentHashMap<>();
    private final AtomicInteger nextEnumId = new AtomicInteger(1);

    /** Maximum distinct PDX enums to register; {@code 0} = unlimited (the default). */
    private final int maxEnums;
    /** Invoked once when a new-enum registration is rejected for hitting the cap (metric + audit). */
    private final Runnable onCapExceeded;
    /** Optional persistence backend (1.3.0-M2); see {@code PdxTypeRegistry}. Null/off ⇒ local counter. */
    private final Repository repository;

    public PdxEnumRegistry() {
        this(0, () -> { }, null);
    }

    public PdxEnumRegistry(int maxEnums, Runnable onCapExceeded) {
        this(maxEnums, onCapExceeded, null);
    }

    public PdxEnumRegistry(int maxEnums, Runnable onCapExceeded, Repository repository) {
        this.maxEnums = Math.max(0, maxEnums);
        this.onCapExceeded = onCapExceeded == null ? () -> { } : onCapExceeded;
        this.repository = repository;
    }

    public int getOrCreateEnumId(byte[] encodedEnumInfo) {
        if (encodedEnumInfo == null || encodedEnumInfo.length == 0) {
            throw new IllegalArgumentException("encodedEnumInfo must not be null or empty");
        }

        String fingerprint = sha256Hex(encodedEnumInfo);

        Integer known = enumIdsByFingerprint.get(fingerprint);
        if (known != null) {
            return known; // an already-registered enum is always served, regardless of the cap
        }
        if (maxEnums > 0 && enumIdsByFingerprint.size() >= maxEnums) {
            onCapExceeded.run();
            throw new PdxRegistryCapExceededException(
                    "PDX enum registry cap reached (" + maxEnums + "); set MAX_PDX_ENUMS to raise it");
        }

        // With persistence on, allocate the id cluster-wide and durably (consistent across replicas +
        // restart); otherwise fall back to the local counter — single-instance behavior unchanged.
        int enumId;
        OptionalInt persisted = repository == null
                ? OptionalInt.empty()
                : repository.allocatePdxEnumId(fingerprint, encodedEnumInfo);
        if (persisted.isPresent()) {
            enumId = persisted.getAsInt();
            enumIdsByFingerprint.put(fingerprint, enumId);
        } else {
            enumId = enumIdsByFingerprint.computeIfAbsent(fingerprint, ignored -> nextEnumId.getAndIncrement());
        }
        // Keep the client's serialized EnumInfo so it can be served back verbatim for the reverse
        // (GET_PDX_ENUM_BY_ID) and bulk (GET_PDX_ENUMS) registry-discovery replies — the object the
        // client sends to register an enum is exactly the object those replies must carry.
        encodedById.putIfAbsent(enumId, encodedEnumInfo.clone());
        return enumId;
    }

    /**
     * The serialized {@code EnumInfo} kept for {@code enumId}, or {@code null} if unknown. When persistence
     * is on and the id is not in memory (a restart, or another replica's registration), it loads from
     * Couchbase and caches — so any replica can serve the reverse (GET_PDX_ENUM_BY_ID) lookup.
     */
    public byte[] serializedEnum(int enumId) {
        byte[] kept = encodedById.get(enumId);
        if (kept != null) {
            return kept.clone();
        }
        if (repository == null) {
            return null;
        }
        byte[] loaded = repository.loadPdxEnum(enumId);
        if (loaded == null) {
            return null;
        }
        encodedById.putIfAbsent(enumId, loaded.clone());
        return loaded.clone();
    }

    /**
     * Every registered enum id mapped to its serialized {@code EnumInfo}, for the GET_PDX_ENUMS reply.
     * With persistence on, unions this instance's enums with the whole persisted registry so a fresh
     * replica serves the complete cluster-wide set (loading any it has not seen).
     */
    public java.util.Map<Integer, byte[]> allSerializedEnums() {
        java.util.LinkedHashMap<Integer, byte[]> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<Integer, byte[]> e : encodedById.entrySet()) {
            out.put(e.getKey(), e.getValue().clone());
        }
        if (repository != null) {
            for (Integer id : repository.loadAllPdxEnums().keySet()) {
                out.computeIfAbsent(id, this::serializedEnum);
            }
        }
        return out;
    }

    public int size() {
        return enumIdsByFingerprint.size();
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