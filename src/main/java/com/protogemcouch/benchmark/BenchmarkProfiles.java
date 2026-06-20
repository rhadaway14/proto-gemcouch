package com.protogemcouch.benchmark;

import java.util.EnumMap;
import java.util.Map;

public final class BenchmarkProfiles {

    private BenchmarkProfiles() {
    }

    public static Map<OperationType, Integer> forName(String profileName) {
        return switch (profileName.toLowerCase()) {
            case "read-heavy" -> readHeavy();
            case "write-heavy" -> writeHeavy();
            case "bulk-heavy" -> bulkHeavy();
            case "mixed" -> mixed();
            case "metadata-heavy" -> metadataHeavy();
            case "full-surface" -> fullSurface();
            default -> mixed();
        };
    }

    /**
     * Exercises the whole request/response surface together — CRUD, bulk, metadata, OQL queries,
     * transactions, the in-transaction getEntry, and PDX writes — so a soak stresses the query engine,
     * transaction registry/commit, and PDX registry under concurrent load, not just the CRUD path.
     * (Subscription/CQ event delivery is driven separately by the soak's subscription consumer.)
     */
    private static Map<OperationType, Integer> fullSurface() {
        Map<OperationType, Integer> weights = new EnumMap<>(OperationType.class);
        weights.put(OperationType.GET, 28);
        weights.put(OperationType.PUT, 18);
        weights.put(OperationType.REMOVE, 4);
        weights.put(OperationType.CONTAINS_KEY, 6);
        weights.put(OperationType.GET_ALL, 6);
        weights.put(OperationType.PUT_ALL, 4);
        weights.put(OperationType.SIZE, 3);
        weights.put(OperationType.KEY_SET, 3);
        weights.put(OperationType.QUERY, 5);   // OQL is the heaviest surface (full region scan, no index)
        weights.put(OperationType.TRANSACTION, 8);
        weights.put(OperationType.GET_ENTRY, 5);
        weights.put(OperationType.PDX_PUT, 5);
        return weights;
    }

    private static Map<OperationType, Integer> mixed() {
        Map<OperationType, Integer> weights = new EnumMap<>(OperationType.class);
        weights.put(OperationType.GET, 40);
        weights.put(OperationType.PUT, 20);
        weights.put(OperationType.REMOVE, 5);
        weights.put(OperationType.CONTAINS_KEY, 10);
        weights.put(OperationType.GET_ALL, 10);
        weights.put(OperationType.PUT_ALL, 5);
        weights.put(OperationType.SIZE, 5);
        weights.put(OperationType.KEY_SET, 5);
        return weights;
    }

    private static Map<OperationType, Integer> metadataHeavy() {
        Map<OperationType, Integer> weights = new EnumMap<>(OperationType.class);
        weights.put(OperationType.SIZE, 10);
        weights.put(OperationType.KEY_SET, 5);
        return weights;
    }

    private static Map<OperationType, Integer> readHeavy() {
        Map<OperationType, Integer> weights = new EnumMap<>(OperationType.class);
        weights.put(OperationType.GET, 60);
        weights.put(OperationType.CONTAINS_KEY, 15);
        weights.put(OperationType.GET_ALL, 10);
        return weights;
    }

    private static Map<OperationType, Integer> writeHeavy() {
        Map<OperationType, Integer> weights = new EnumMap<>(OperationType.class);
        weights.put(OperationType.PUT, 55);
        weights.put(OperationType.REMOVE, 15);
        weights.put(OperationType.GET, 15);
        weights.put(OperationType.CONTAINS_KEY, 10);
        weights.put(OperationType.PUT_ALL, 5);
        return weights;
    }

    private static Map<OperationType, Integer> bulkHeavy() {
        Map<OperationType, Integer> weights = new EnumMap<>(OperationType.class);
        weights.put(OperationType.GET_ALL, 40);
        weights.put(OperationType.PUT_ALL, 30);
        weights.put(OperationType.GET, 10);
        weights.put(OperationType.PUT, 10);
        weights.put(OperationType.SIZE, 5);
        weights.put(OperationType.KEY_SET, 5);
        return weights;
    }
}