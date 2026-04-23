package com.protogemcouch.observability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class MetricsRegistry {

    private final LongAdder connectionsOpened = new LongAdder();
    private final LongAdder connectionsClosed = new LongAdder();
    private final LongAdder handshakeRequests = new LongAdder();
    private final LongAdder unknownOpcodes = new LongAdder();
    private final LongAdder requestErrors = new LongAdder();

    private final Map<Integer, OpMetrics> perOpcode = new ConcurrentHashMap<>();

    public void recordConnectionOpened() {
        connectionsOpened.increment();
    }

    public void recordConnectionClosed() {
        connectionsClosed.increment();
    }

    public void recordHandshakeRequest() {
        handshakeRequests.increment();
    }

    public void recordRequestStart(int opcode) {
        perOpcode.computeIfAbsent(opcode, ignored -> new OpMetrics()).requests.increment();
    }

    public void recordRequestSuccess(int opcode, long elapsedNanos) {
        OpMetrics metrics = perOpcode.computeIfAbsent(opcode, ignored -> new OpMetrics());
        metrics.successes.increment();
        metrics.totalLatencyNanos.add(elapsedNanos);
        updateMax(metrics, elapsedNanos);
    }

    public void recordRequestError(int opcode, long elapsedNanos) {
        OpMetrics metrics = perOpcode.computeIfAbsent(opcode, ignored -> new OpMetrics());
        metrics.errors.increment();
        metrics.totalLatencyNanos.add(elapsedNanos);
        updateMax(metrics, elapsedNanos);
        requestErrors.increment();
    }

    public void recordUnknownOpcode(int opcode) {
        perOpcode.computeIfAbsent(opcode, ignored -> new OpMetrics()).unknown.increment();
        unknownOpcodes.increment();
    }

    public List<String> snapshotLines() {
        List<String> lines = new ArrayList<>();

        lines.add(StructuredLog.event(
                "metrics_summary",
                "connections_opened", connectionsOpened.sum(),
                "connections_closed", connectionsClosed.sum(),
                "handshake_requests", handshakeRequests.sum(),
                "unknown_opcodes", unknownOpcodes.sum(),
                "request_errors", requestErrors.sum()
        ));

        perOpcode.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .forEach(entry -> {
                    int opcode = entry.getKey();
                    OpMetrics m = entry.getValue();

                    long requestCount = m.requests.sum();
                    long successCount = m.successes.sum();
                    long errorCount = m.errors.sum();
                    long unknownCount = m.unknown.sum();
                    long totalLatency = m.totalLatencyNanos.sum();
                    long maxLatency = m.maxLatencyNanos.sum();
                    long avgLatency = requestCount == 0 ? 0 : totalLatency / requestCount;

                    lines.add(StructuredLog.event(
                            "metrics_opcode",
                            "opcode", opcode,
                            "operation", OperationNames.nameOf(opcode),
                            "requests", requestCount,
                            "successes", successCount,
                            "errors", errorCount,
                            "unknown", unknownCount,
                            "avg_latency_ns", avgLatency,
                            "max_latency_ns", maxLatency
                    ));
                });

        return lines;
    }

    private void updateMax(OpMetrics metrics, long value) {
        while (true) {
            long current = metrics.maxLatencyNanos.sum();
            if (value <= current) {
                return;
            }
            longAdderSet(metrics.maxLatencyNanos, current, value);
            if (metrics.maxLatencyNanos.sum() >= value) {
                return;
            }
        }
    }

    private void longAdderSet(LongAdder adder, long current, long target) {
        long delta = target - current;
        if (delta > 0) {
            adder.add(delta);
        }
    }

    private static final class OpMetrics {
        private final LongAdder requests = new LongAdder();
        private final LongAdder successes = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder unknown = new LongAdder();
        private final LongAdder totalLatencyNanos = new LongAdder();
        private final LongAdder maxLatencyNanos = new LongAdder();
    }
}