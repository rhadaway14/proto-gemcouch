package com.protogemcouch.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public class LatencyStats {

    private final List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());
    private final LongAdder successCount = new LongAdder();
    private final LongAdder errorCount = new LongAdder();

    public void recordSuccess(long latencyNanos) {
        successCount.increment();
        latenciesNanos.add(latencyNanos);
    }

    public void recordError() {
        errorCount.increment();
    }

    public long getSuccessCount() {
        return successCount.sum();
    }

    public long getErrorCount() {
        return errorCount.sum();
    }

    public long getTotalCount() {
        return getSuccessCount() + getErrorCount();
    }

    public long percentile(double percentile) {
        List<Long> copy;
        synchronized (latenciesNanos) {
            if (latenciesNanos.isEmpty()) {
                return 0L;
            }
            copy = new ArrayList<>(latenciesNanos);
        }

        copy.sort(Long::compareTo);
        int index = (int) Math.ceil((percentile / 100.0) * copy.size()) - 1;
        index = Math.max(0, Math.min(index, copy.size() - 1));
        return copy.get(index);
    }

    public double averageNanos() {
        List<Long> copy;
        synchronized (latenciesNanos) {
            if (latenciesNanos.isEmpty()) {
                return 0.0;
            }
            copy = new ArrayList<>(latenciesNanos);
        }

        long sum = 0L;
        for (Long value : copy) {
            sum += value;
        }
        return (double) sum / copy.size();
    }
}