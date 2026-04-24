package com.protogemcouch.benchmark;

import java.util.EnumMap;
import java.util.Map;

public class BenchmarkResult {

    private final long startMillis;
    private final long endMillis;
    private final Map<OperationType, LatencyStats> perOperation;
    private final String phase;

    public BenchmarkResult(long startMillis, long endMillis, Map<OperationType, LatencyStats> perOperation, String phase) {
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.perOperation = new EnumMap<>(perOperation);
        this.phase = phase;
    }

    public long getStartMillis() {
        return startMillis;
    }

    public long getEndMillis() {
        return endMillis;
    }

    public long getDurationMillis() {
        return endMillis - startMillis;
    }

    public Map<OperationType, LatencyStats> getPerOperation() {
        return new EnumMap<>(perOperation);
    }

    public String getPhase() {
        return phase;
    }

    public long totalOperations() {
        long total = 0L;
        for (LatencyStats stats : perOperation.values()) {
            total += stats.getTotalCount();
        }
        return total;
    }

    public long totalSuccesses() {
        long total = 0L;
        for (LatencyStats stats : perOperation.values()) {
            total += stats.getSuccessCount();
        }
        return total;
    }

    public long totalErrors() {
        long total = 0L;
        for (LatencyStats stats : perOperation.values()) {
            total += stats.getErrorCount();
        }
        return total;
    }

    public double opsPerSecond() {
        double seconds = getDurationMillis() / 1000.0;
        if (seconds <= 0.0) {
            return 0.0;
        }
        return totalOperations() / seconds;
    }
}