package com.protogemcouch.ops;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PdxTypeRegistry {

    private final ConcurrentMap<String, Integer> typeIdsByFingerprint = new ConcurrentHashMap<>();
    private final AtomicInteger nextTypeId = new AtomicInteger(1);

    public int getOrCreateTypeId(byte[] encodedPdxType) {
        if (encodedPdxType == null || encodedPdxType.length == 0) {
            throw new IllegalArgumentException("encodedPdxType must not be null or empty");
        }

        String fingerprint = sha256Hex(encodedPdxType);

        return typeIdsByFingerprint.computeIfAbsent(
                fingerprint,
                ignored -> nextTypeId.getAndIncrement()
        );
    }

    public int size() {
        return typeIdsByFingerprint.size();
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