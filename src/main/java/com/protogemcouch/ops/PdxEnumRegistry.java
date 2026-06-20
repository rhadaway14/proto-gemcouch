package com.protogemcouch.ops;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PdxEnumRegistry {

    private final ConcurrentMap<String, Integer> enumIdsByFingerprint = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, byte[]> encodedById = new ConcurrentHashMap<>();
    private final AtomicInteger nextEnumId = new AtomicInteger(1);

    public int getOrCreateEnumId(byte[] encodedEnumInfo) {
        if (encodedEnumInfo == null || encodedEnumInfo.length == 0) {
            throw new IllegalArgumentException("encodedEnumInfo must not be null or empty");
        }

        String fingerprint = sha256Hex(encodedEnumInfo);

        int enumId = enumIdsByFingerprint.computeIfAbsent(
                fingerprint,
                ignored -> nextEnumId.getAndIncrement()
        );
        // Keep the client's serialized EnumInfo so it can be served back verbatim for the reverse
        // (GET_PDX_ENUM_BY_ID) and bulk (GET_PDX_ENUMS) registry-discovery replies — the object the
        // client sends to register an enum is exactly the object those replies must carry.
        encodedById.putIfAbsent(enumId, encodedEnumInfo.clone());
        return enumId;
    }

    /** The serialized {@code EnumInfo} kept for {@code enumId}, or {@code null} if unknown. */
    public byte[] serializedEnum(int enumId) {
        byte[] kept = encodedById.get(enumId);
        return kept == null ? null : kept.clone();
    }

    /** Every registered enum id mapped to its serialized {@code EnumInfo}, for the GET_PDX_ENUMS reply. */
    public java.util.Map<Integer, byte[]> allSerializedEnums() {
        java.util.LinkedHashMap<Integer, byte[]> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<Integer, byte[]> e : encodedById.entrySet()) {
            out.put(e.getKey(), e.getValue().clone());
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