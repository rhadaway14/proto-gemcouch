package com.protogemcouch.ops;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PdxEnumRegistry {

    private final ConcurrentMap<String, Integer> enumIdsByFingerprint = new ConcurrentHashMap<>();
    private final AtomicInteger nextEnumId = new AtomicInteger(1);

    public int getOrCreateEnumId(byte[] encodedEnumInfo) {
        if (encodedEnumInfo == null || encodedEnumInfo.length == 0) {
            throw new IllegalArgumentException("encodedEnumInfo must not be null or empty");
        }

        String fingerprint = sha256Hex(encodedEnumInfo);

        return enumIdsByFingerprint.computeIfAbsent(
                fingerprint,
                ignored -> nextEnumId.getAndIncrement()
        );
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