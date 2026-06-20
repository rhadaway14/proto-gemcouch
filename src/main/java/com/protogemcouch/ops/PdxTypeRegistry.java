package com.protogemcouch.ops;

import org.apache.geode.DataSerializer;
import org.apache.geode.pdx.internal.PdxType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    public int getOrCreateTypeId(byte[] encodedPdxType) {
        if (encodedPdxType == null || encodedPdxType.length == 0) {
            throw new IllegalArgumentException("encodedPdxType must not be null or empty");
        }

        String fingerprint = sha256Hex(encodedPdxType);
        int typeId = typeIdsByFingerprint.computeIfAbsent(
                fingerprint, ignored -> nextTypeId.getAndIncrement());

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

    /** The PdxType registered for an id, or {@code null} if unknown / not parseable. */
    public PdxType getPdxType(int typeId) {
        return typesById.get(typeId);
    }

    /**
     * The kept {@link PdxType} for {@code typeId} re-serialized in DataSerializer form (for the
     * GET_PDX_TYPE_BY_ID reply), or {@code null} when the id is unknown / cannot be serialized.
     */
    public byte[] serializedPdxType(int typeId) {
        PdxType type = typesById.get(typeId);
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
     * the bulk GET_PDX_TYPES registry-discovery reply. Types that cannot be re-serialized are skipped.
     */
    public java.util.Map<Integer, byte[]> allSerializedTypes() {
        java.util.LinkedHashMap<Integer, byte[]> out = new java.util.LinkedHashMap<>();
        for (Integer id : typesById.keySet()) {
            byte[] serialized = serializedPdxType(id);
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
