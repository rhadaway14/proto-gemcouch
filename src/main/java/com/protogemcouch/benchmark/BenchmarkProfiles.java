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
            default -> mixed();
        };
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