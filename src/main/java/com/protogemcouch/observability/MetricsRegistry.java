package com.protogemcouch.observability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
        metrics.lastLatencyNanos.set(elapsedNanos);
        metrics.lastUpdatedEpochMs.set(System.currentTimeMillis());
        metrics.lastError.set(null);

        updateMin(metrics, elapsedNanos);
        updateMax(metrics, elapsedNanos);
    }

    public void recordRequestError(int opcode, long elapsedNanos) {
        recordRequestError(opcode, elapsedNanos, null);
    }

    public void recordRequestError(int opcode, long elapsedNanos, Throwable error) {
        OpMetrics metrics = perOpcode.computeIfAbsent(opcode, ignored -> new OpMetrics());

        metrics.errors.increment();
        metrics.totalLatencyNanos.add(elapsedNanos);
        metrics.lastLatencyNanos.set(elapsedNanos);
        metrics.lastUpdatedEpochMs.set(System.currentTimeMillis());
        metrics.lastError.set(error == null ? null : safeError(error));

        updateMin(metrics, elapsedNanos);
        updateMax(metrics, elapsedNanos);

        requestErrors.increment();
    }

    public void recordUnknownOpcode(int opcode) {
        OpMetrics metrics = perOpcode.computeIfAbsent(opcode, ignored -> new OpMetrics());
        metrics.unknown.increment();
        metrics.lastUpdatedEpochMs.set(System.currentTimeMillis());

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
                    long minLatency = normalizedMinLatency(m);
                    long maxLatency = m.maxLatencyNanos.get();
                    long lastLatency = m.lastLatencyNanos.get();
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
                            "min_latency_ns", minLatency,
                            "max_latency_ns", maxLatency,
                            "last_latency_ns", lastLatency,
                            "last_error", m.lastError.get()
                    ));
                });

        return lines;
    }

    public String snapshotJson() {
        StringBuilder json = new StringBuilder();

        json.append('{');

        json.append("\"connections\":{")
                .append("\"opened\":").append(connectionsOpened.sum()).append(',')
                .append("\"closed\":").append(connectionsClosed.sum())
                .append("},");

        json.append("\"requests\":{")
                .append("\"handshakeRequests\":").append(handshakeRequests.sum()).append(',')
                .append("\"unknownOpcodes\":").append(unknownOpcodes.sum()).append(',')
                .append("\"requestErrors\":").append(requestErrors.sum())
                .append("},");

        json.append("\"operations\":[");

        boolean first = true;

        List<Map.Entry<Integer, OpMetrics>> entries = perOpcode.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .toList();

        for (Map.Entry<Integer, OpMetrics> entry : entries) {
            if (!first) {
                json.append(',');
            }

            first = false;

            int opcode = entry.getKey();
            OpMetrics m = entry.getValue();

            long requestCount = m.requests.sum();
            long successCount = m.successes.sum();
            long errorCount = m.errors.sum();
            long unknownCount = m.unknown.sum();
            long totalLatency = m.totalLatencyNanos.sum();
            long avgLatency = requestCount == 0 ? 0 : totalLatency / requestCount;
            long minLatency = normalizedMinLatency(m);
            long maxLatency = m.maxLatencyNanos.get();
            long lastLatency = m.lastLatencyNanos.get();

            json.append('{')
                    .append("\"opcode\":").append(opcode).append(',')
                    .append("\"operation\":\"").append(escapeJson(OperationNames.nameOf(opcode))).append("\",")
                    .append("\"requests\":").append(requestCount).append(',')
                    .append("\"successes\":").append(successCount).append(',')
                    .append("\"errors\":").append(errorCount).append(',')
                    .append("\"unknown\":").append(unknownCount).append(',')
                    .append("\"avgLatencyNs\":").append(avgLatency).append(',')
                    .append("\"minLatencyNs\":").append(minLatency).append(',')
                    .append("\"maxLatencyNs\":").append(maxLatency).append(',')
                    .append("\"lastLatencyNs\":").append(lastLatency).append(',')
                    .append("\"lastUpdatedEpochMs\":").append(m.lastUpdatedEpochMs.get()).append(',')
                    .append("\"lastError\":");

            String lastError = m.lastError.get();
            if (lastError == null) {
                json.append("null");
            } else {
                json.append('"').append(escapeJson(lastError)).append('"');
            }

            json.append('}');
        }

        json.append(']');
        json.append('}');

        return json.toString();
    }

    private void updateMin(OpMetrics metrics, long value) {
        while (true) {
            long current = metrics.minLatencyNanos.get();

            if (value >= current) {
                return;
            }

            if (metrics.minLatencyNanos.compareAndSet(current, value)) {
                return;
            }
        }
    }

    private void updateMax(OpMetrics metrics, long value) {
        while (true) {
            long current = metrics.maxLatencyNanos.get();

            if (value <= current) {
                return;
            }

            if (metrics.maxLatencyNanos.compareAndSet(current, value)) {
                return;
            }
        }
    }

    private long normalizedMinLatency(OpMetrics metrics) {
        long value = metrics.minLatencyNanos.get();
        return value == Long.MAX_VALUE ? 0 : value;
    }

    private String safeError(Throwable error) {
        String type = error.getClass().getSimpleName();
        String message = error.getMessage();

        if (message == null || message.isBlank()) {
            return type;
        }

        return type + ": " + message;
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder out = new StringBuilder(value.length() + 16);

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }

        return out.toString();
    }

    private static final class OpMetrics {
        private final LongAdder requests = new LongAdder();
        private final LongAdder successes = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder unknown = new LongAdder();
        private final LongAdder totalLatencyNanos = new LongAdder();

        private final AtomicLong minLatencyNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxLatencyNanos = new AtomicLong(0);
        private final AtomicLong lastLatencyNanos = new AtomicLong(0);
        private final AtomicLong lastUpdatedEpochMs = new AtomicLong(0);
        private final AtomicReference<String> lastError = new AtomicReference<>();
    }
}
