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

    public void recordRequestBytes(int opcode, long requestBytes) {
        if (requestBytes < 0) {
            return;
        }

        OpMetrics metrics = perOpcode.computeIfAbsent(opcode, ignored -> new OpMetrics());
        metrics.requestBytesTotal.add(requestBytes);
        metrics.requestBytesLast.set(requestBytes);
        updateMax(metrics.requestBytesMax, requestBytes);
    }

    public void recordResponseBytes(int opcode, long responseBytes) {
        if (responseBytes < 0) {
            return;
        }

        OpMetrics metrics = perOpcode.computeIfAbsent(opcode, ignored -> new OpMetrics());
        metrics.responseBytesTotal.add(responseBytes);
        metrics.responseBytesLast.set(responseBytes);
        updateMax(metrics.responseBytesMax, responseBytes);
    }

    public void recordRequestSuccess(int opcode, long elapsedNanos) {
        OpMetrics metrics = perOpcode.computeIfAbsent(opcode, ignored -> new OpMetrics());
        metrics.successes.increment();
        metrics.totalLatencyNanos.add(elapsedNanos);
        metrics.lastLatencyNanos.set(elapsedNanos);
        metrics.lastUpdatedEpochMs.set(System.currentTimeMillis());
        metrics.lastError.set(null);
        updateMin(metrics.minLatencyNanos, elapsedNanos);
        updateMax(metrics.maxLatencyNanos, elapsedNanos);
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
        updateMin(metrics.minLatencyNanos, elapsedNanos);
        updateMax(metrics.maxLatencyNanos, elapsedNanos);
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

        sortedOpcodeEntries().forEach(entry -> {
            int opcode = entry.getKey();
            OpMetrics m = entry.getValue();

            long requestCount = m.requests.sum();
            long successCount = m.successes.sum();
            long errorCount = m.errors.sum();
            long unknownCount = m.unknown.sum();
            long totalLatency = m.totalLatencyNanos.sum();
            long minLatency = normalizedMin(m.minLatencyNanos.get());
            long maxLatency = m.maxLatencyNanos.get();
            long lastLatency = m.lastLatencyNanos.get();
            long avgLatency = requestCount == 0 ? 0 : totalLatency / requestCount;
            long requestBytesTotal = m.requestBytesTotal.sum();
            long responseBytesTotal = m.responseBytesTotal.sum();
            long avgRequestBytes = requestCount == 0 ? 0 : requestBytesTotal / requestCount;
            long avgResponseBytes = requestCount == 0 ? 0 : responseBytesTotal / requestCount;

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
                    "request_bytes_total", requestBytesTotal,
                    "request_bytes_last", m.requestBytesLast.get(),
                    "request_bytes_max", m.requestBytesMax.get(),
                    "request_bytes_avg", avgRequestBytes,
                    "response_bytes_total", responseBytesTotal,
                    "response_bytes_last", m.responseBytesLast.get(),
                    "response_bytes_max", m.responseBytesMax.get(),
                    "response_bytes_avg", avgResponseBytes,
                    "last_error", m.lastError.get()
            ));
        });

        return lines;
    }

    public String snapshotJson() {
        StringBuilder out = new StringBuilder(2048);

        out.append('{');
        out.append("\"connections\":{");
        out.append("\"opened\":").append(connectionsOpened.sum()).append(',');
        out.append("\"closed\":").append(connectionsClosed.sum());
        out.append("},");

        out.append("\"requests\":{");
        out.append("\"handshakeRequests\":").append(handshakeRequests.sum()).append(',');
        out.append("\"unknownOpcodes\":").append(unknownOpcodes.sum()).append(',');
        out.append("\"requestErrors\":").append(requestErrors.sum());
        out.append("},");

        out.append("\"operations\":[");

        boolean first = true;
        for (Map.Entry<Integer, OpMetrics> entry : sortedOpcodeEntries()) {
            if (!first) {
                out.append(',');
            }
            first = false;

            int opcode = entry.getKey();
            OpMetrics m = entry.getValue();
            long requests = m.requests.sum();
            long totalLatency = m.totalLatencyNanos.sum();
            long avgLatency = requests == 0 ? 0 : totalLatency / requests;
            long requestBytesTotal = m.requestBytesTotal.sum();
            long responseBytesTotal = m.responseBytesTotal.sum();
            long avgRequestBytes = requests == 0 ? 0 : requestBytesTotal / requests;
            long avgResponseBytes = requests == 0 ? 0 : responseBytesTotal / requests;

            out.append('{');
            out.append("\"opcode\":").append(opcode).append(',');
            out.append("\"operation\":\"").append(jsonEscape(OperationNames.nameOf(opcode))).append("\",");
            out.append("\"requests\":").append(requests).append(',');
            out.append("\"successes\":").append(m.successes.sum()).append(',');
            out.append("\"errors\":").append(m.errors.sum()).append(',');
            out.append("\"unknown\":").append(m.unknown.sum()).append(',');
            out.append("\"avgLatencyNs\":").append(avgLatency).append(',');
            out.append("\"minLatencyNs\":").append(normalizedMin(m.minLatencyNanos.get())).append(',');
            out.append("\"maxLatencyNs\":").append(m.maxLatencyNanos.get()).append(',');
            out.append("\"lastLatencyNs\":").append(m.lastLatencyNanos.get()).append(',');
            out.append("\"requestBytesTotal\":").append(requestBytesTotal).append(',');
            out.append("\"requestBytesLast\":").append(m.requestBytesLast.get()).append(',');
            out.append("\"requestBytesMax\":").append(m.requestBytesMax.get()).append(',');
            out.append("\"requestBytesAvg\":").append(avgRequestBytes).append(',');
            out.append("\"responseBytesTotal\":").append(responseBytesTotal).append(',');
            out.append("\"responseBytesLast\":").append(m.responseBytesLast.get()).append(',');
            out.append("\"responseBytesMax\":").append(m.responseBytesMax.get()).append(',');
            out.append("\"responseBytesAvg\":").append(avgResponseBytes).append(',');
            out.append("\"lastUpdatedEpochMs\":").append(m.lastUpdatedEpochMs.get()).append(',');

            String lastError = m.lastError.get();
            if (lastError == null) {
                out.append("\"lastError\":null");
            } else {
                out.append("\"lastError\":\"").append(jsonEscape(lastError)).append("\"");
            }

            out.append('}');
        }

        out.append(']');
        out.append('}');

        return out.toString();
    }

    public String snapshotPrometheus() {
        StringBuilder out = new StringBuilder(4096);

        appendMetricHelp(out, "protogemcouch_connections_opened_total", "Total client connections opened.");
        appendMetricType(out, "protogemcouch_connections_opened_total", "counter");
        appendMetric(out, "protogemcouch_connections_opened_total", connectionsOpened.sum());

        appendMetricHelp(out, "protogemcouch_connections_closed_total", "Total client connections closed.");
        appendMetricType(out, "protogemcouch_connections_closed_total", "counter");
        appendMetric(out, "protogemcouch_connections_closed_total", connectionsClosed.sum());

        appendMetricHelp(out, "protogemcouch_handshake_requests_total", "Total Geode handshake requests received.");
        appendMetricType(out, "protogemcouch_handshake_requests_total", "counter");
        appendMetric(out, "protogemcouch_handshake_requests_total", handshakeRequests.sum());

        appendMetricHelp(out, "protogemcouch_unknown_opcodes_total", "Total unknown opcodes received.");
        appendMetricType(out, "protogemcouch_unknown_opcodes_total", "counter");
        appendMetric(out, "protogemcouch_unknown_opcodes_total", unknownOpcodes.sum());

        appendMetricHelp(out, "protogemcouch_request_errors_total", "Total request errors across all opcodes.");
        appendMetricType(out, "protogemcouch_request_errors_total", "counter");
        appendMetric(out, "protogemcouch_request_errors_total", requestErrors.sum());

        appendOperationMetricHeaders(out);

        for (Map.Entry<Integer, OpMetrics> entry : sortedOpcodeEntries()) {
            int opcode = entry.getKey();
            String operation = OperationNames.nameOf(opcode);
            OpMetrics m = entry.getValue();

            long requests = m.requests.sum();
            long successes = m.successes.sum();
            long errors = m.errors.sum();
            long unknown = m.unknown.sum();
            long totalLatency = m.totalLatencyNanos.sum();
            long avgLatency = requests == 0 ? 0 : totalLatency / requests;
            long requestBytesTotal = m.requestBytesTotal.sum();
            long responseBytesTotal = m.responseBytesTotal.sum();
            long avgRequestBytes = requests == 0 ? 0 : requestBytesTotal / requests;
            long avgResponseBytes = requests == 0 ? 0 : responseBytesTotal / requests;

            String labels = labels(opcode, operation);

            appendMetric(out, "protogemcouch_operation_requests_total", labels, requests);
            appendMetric(out, "protogemcouch_operation_successes_total", labels, successes);
            appendMetric(out, "protogemcouch_operation_errors_total", labels, errors);
            appendMetric(out, "protogemcouch_operation_unknown_total", labels, unknown);
            appendMetric(out, "protogemcouch_operation_latency_total_ns", labels, totalLatency);
            appendMetric(out, "protogemcouch_operation_latency_avg_ns", labels, avgLatency);
            appendMetric(out, "protogemcouch_operation_latency_min_ns", labels, normalizedMin(m.minLatencyNanos.get()));
            appendMetric(out, "protogemcouch_operation_latency_max_ns", labels, m.maxLatencyNanos.get());
            appendMetric(out, "protogemcouch_operation_latency_last_ns", labels, m.lastLatencyNanos.get());
            appendMetric(out, "protogemcouch_operation_request_bytes_total", labels, requestBytesTotal);
            appendMetric(out, "protogemcouch_operation_request_bytes_last", labels, m.requestBytesLast.get());
            appendMetric(out, "protogemcouch_operation_request_bytes_max", labels, m.requestBytesMax.get());
            appendMetric(out, "protogemcouch_operation_request_bytes_avg", labels, avgRequestBytes);
            appendMetric(out, "protogemcouch_operation_response_bytes_total", labels, responseBytesTotal);
            appendMetric(out, "protogemcouch_operation_response_bytes_last", labels, m.responseBytesLast.get());
            appendMetric(out, "protogemcouch_operation_response_bytes_max", labels, m.responseBytesMax.get());
            appendMetric(out, "protogemcouch_operation_response_bytes_avg", labels, avgResponseBytes);
            appendMetric(out, "protogemcouch_operation_last_updated_epoch_ms", labels, m.lastUpdatedEpochMs.get());
        }

        return out.toString();
    }

    private List<Map.Entry<Integer, OpMetrics>> sortedOpcodeEntries() {
        return perOpcode.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .toList();
    }

    private static void updateMin(AtomicLong target, long value) {
        while (true) {
            long current = target.get();
            if (value >= current) {
                return;
            }
            if (target.compareAndSet(current, value)) {
                return;
            }
        }
    }

    private static void updateMax(AtomicLong target, long value) {
        while (true) {
            long current = target.get();
            if (value <= current) {
                return;
            }
            if (target.compareAndSet(current, value)) {
                return;
            }
        }
    }

    private static long normalizedMin(long value) {
        return value == Long.MAX_VALUE ? 0 : value;
    }

    private static String safeError(Throwable error) {
        String type = error.getClass().getSimpleName();
        String message = error.getMessage();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    private static void appendOperationMetricHeaders(StringBuilder out) {
        appendMetricHelp(out, "protogemcouch_operation_requests_total", "Total requests by opcode and operation.");
        appendMetricType(out, "protogemcouch_operation_requests_total", "counter");
        appendMetricHelp(out, "protogemcouch_operation_successes_total", "Total successful requests by opcode and operation.");
        appendMetricType(out, "protogemcouch_operation_successes_total", "counter");
        appendMetricHelp(out, "protogemcouch_operation_errors_total", "Total failed requests by opcode and operation.");
        appendMetricType(out, "protogemcouch_operation_errors_total", "counter");
        appendMetricHelp(out, "protogemcouch_operation_unknown_total", "Total unknown requests by opcode and operation.");
        appendMetricType(out, "protogemcouch_operation_unknown_total", "counter");
        appendMetricHelp(out, "protogemcouch_operation_latency_total_ns", "Total request latency by opcode and operation in nanoseconds.");
        appendMetricType(out, "protogemcouch_operation_latency_total_ns", "counter");
        appendMetricHelp(out, "protogemcouch_operation_latency_avg_ns", "Average request latency by opcode and operation in nanoseconds.");
        appendMetricType(out, "protogemcouch_operation_latency_avg_ns", "gauge");
        appendMetricHelp(out, "protogemcouch_operation_latency_min_ns", "Minimum request latency by opcode and operation in nanoseconds.");
        appendMetricType(out, "protogemcouch_operation_latency_min_ns", "gauge");
        appendMetricHelp(out, "protogemcouch_operation_latency_max_ns", "Maximum request latency by opcode and operation in nanoseconds.");
        appendMetricType(out, "protogemcouch_operation_latency_max_ns", "gauge");
        appendMetricHelp(out, "protogemcouch_operation_latency_last_ns", "Last request latency by opcode and operation in nanoseconds.");
        appendMetricType(out, "protogemcouch_operation_latency_last_ns", "gauge");

        appendMetricHelp(out, "protogemcouch_operation_request_bytes_total", "Total decoded request bytes by opcode and operation.");
        appendMetricType(out, "protogemcouch_operation_request_bytes_total", "counter");
        appendMetricHelp(out, "protogemcouch_operation_request_bytes_last", "Last decoded request byte size by opcode and operation.");
        appendMetricType(out, "protogemcouch_operation_request_bytes_last", "gauge");
        appendMetricHelp(out, "protogemcouch_operation_request_bytes_max", "Maximum decoded request byte size by opcode and operation.");
        appendMetricType(out, "protogemcouch_operation_request_bytes_max", "gauge");
        appendMetricHelp(out, "protogemcouch_operation_request_bytes_avg", "Average decoded request byte size by opcode and operation.");
        appendMetricType(out, "protogemcouch_operation_request_bytes_avg", "gauge");

        appendMetricHelp(out, "protogemcouch_operation_response_bytes_total", "Total response bytes recorded by opcode and operation.");
        appendMetricType(out, "protogemcouch_operation_response_bytes_total", "counter");
        appendMetricHelp(out, "protogemcouch_operation_response_bytes_last", "Last response byte size recorded by opcode and operation.");
        appendMetricType(out, "protogemcouch_operation_response_bytes_last", "gauge");
        appendMetricHelp(out, "protogemcouch_operation_response_bytes_max", "Maximum response byte size recorded by opcode and operation.");
        appendMetricType(out, "protogemcouch_operation_response_bytes_max", "gauge");
        appendMetricHelp(out, "protogemcouch_operation_response_bytes_avg", "Average response byte size recorded by opcode and operation.");
        appendMetricType(out, "protogemcouch_operation_response_bytes_avg", "gauge");

        appendMetricHelp(out, "protogemcouch_operation_last_updated_epoch_ms", "Last update time by opcode and operation in epoch milliseconds.");
        appendMetricType(out, "protogemcouch_operation_last_updated_epoch_ms", "gauge");
    }

    private static void appendMetricHelp(StringBuilder out, String metric, String help) {
        out.append("# HELP ").append(metric).append(' ').append(help).append('\n');
    }

    private static void appendMetricType(StringBuilder out, String metric, String type) {
        out.append("# TYPE ").append(metric).append(' ').append(type).append('\n');
    }

    private static void appendMetric(StringBuilder out, String metric, long value) {
        out.append(metric).append(' ').append(value).append('\n');
    }

    private static void appendMetric(StringBuilder out, String metric, String labels, long value) {
        out.append(metric).append(labels).append(' ').append(value).append('\n');
    }

    private static String labels(int opcode, String operation) {
        return "{opcode=\"" + prometheusEscape(String.valueOf(opcode))
                + "\",operation=\"" + prometheusEscape(operation) + "\"}";
    }

    private static String prometheusEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static final class OpMetrics {
        private final LongAdder requests = new LongAdder();
        private final LongAdder successes = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder unknown = new LongAdder();
        private final LongAdder totalLatencyNanos = new LongAdder();

        private final LongAdder requestBytesTotal = new LongAdder();
        private final AtomicLong requestBytesLast = new AtomicLong(0L);
        private final AtomicLong requestBytesMax = new AtomicLong(0L);

        private final LongAdder responseBytesTotal = new LongAdder();
        private final AtomicLong responseBytesLast = new AtomicLong(0L);
        private final AtomicLong responseBytesMax = new AtomicLong(0L);

        private final AtomicLong minLatencyNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxLatencyNanos = new AtomicLong(0L);
        private final AtomicLong lastLatencyNanos = new AtomicLong(0L);
        private final AtomicLong lastUpdatedEpochMs = new AtomicLong(0L);
        private final AtomicReference<String> lastError = new AtomicReference<>();
    }
}
