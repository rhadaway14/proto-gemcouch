package com.protogemcouch.observability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

public class MetricsRegistry {

    private final LongAdder connectionsOpened = new LongAdder();
    private final LongAdder connectionsClosed = new LongAdder();
    private final LongAdder connectionsRejected = new LongAdder();
    private final LongAdder idleConnectionsClosed = new LongAdder();
    private final LongAdder firstRequestTimeouts = new LongAdder();
    private final LongAdder requestsShed = new LongAdder();
    private final LongAdder handshakeRequests = new LongAdder();
    private final LongAdder handshakeVersionRejected = new LongAdder();
    private final LongAdder unknownOpcodes = new LongAdder();
    private final LongAdder requestErrors = new LongAdder();
    private final LongAdder malformedFrames = new LongAdder();
    private final LongAdder pdxRegistryRejected = new LongAdder();

    private final Map<Integer, OpMetrics> perOpcode = new ConcurrentHashMap<>();

    /**
     * Sampled-at-scrape gauges for live, non-cumulative state (the in-memory registry sizes). Each is a
     * supplier read when metrics are rendered, so the value is always current without the registries
     * having to push updates. Registered once at startup via {@link #registerGauge}.
     */
    private final List<Gauge> gauges = new CopyOnWriteArrayList<>();

    private record Gauge(String name, String help, LongSupplier supplier) {
    }

    /**
     * Cumulative histogram bucket upper bounds for operation latency, expressed in nanoseconds for
     * cheap integer comparison against observed latencies. The matching {@code le} labels in
     * {@link #LATENCY_BUCKET_LE} are expressed in seconds, following Prometheus base-unit convention.
     * An implicit {@code +Inf} bucket captures everything above the largest finite bound.
     */
    private static final long[] LATENCY_BUCKET_BOUNDS_NANOS = {
            500_000L,        // 0.5 ms
            1_000_000L,      // 1 ms
            2_500_000L,      // 2.5 ms
            5_000_000L,      // 5 ms
            10_000_000L,     // 10 ms
            25_000_000L,     // 25 ms
            50_000_000L,     // 50 ms
            100_000_000L,    // 100 ms
            250_000_000L,    // 250 ms
            500_000_000L,    // 500 ms
            1_000_000_000L   // 1 s
    };

    private static final String[] LATENCY_BUCKET_LE = {
            "0.0005", "0.001", "0.0025", "0.005", "0.01", "0.025",
            "0.05", "0.1", "0.25", "0.5", "1"
    };

    public void recordConnectionOpened() {
        connectionsOpened.increment();
    }

    public void recordConnectionClosed() {
        connectionsClosed.increment();
    }

    public void recordConnectionRejected() {
        connectionsRejected.increment();
    }

    public void recordIdleConnectionClosed() {
        idleConnectionsClosed.increment();
    }

    public void recordFirstRequestTimeout() {
        firstRequestTimeouts.increment();
    }

    public void recordRequestShed() {
        requestsShed.increment();
    }

    public void recordHandshakeRequest() {
        handshakeRequests.increment();
    }

    public void recordHandshakeVersionRejected() {
        handshakeVersionRejected.increment();
    }

    public void recordMalformedFrame() {
        malformedFrames.increment();
    }

    /** A PDX type/enum registration was rejected because the registry was at its configured cap. */
    public void recordPdxRegistryRejected() {
        pdxRegistryRejected.increment();
    }

    /**
     * Register a sampled gauge (current value read at scrape time). Use for live in-memory state such
     * as registry sizes. {@code name} is the full Prometheus metric name (e.g.
     * {@code protogemcouch_pdx_types}); call once at startup.
     */
    public void registerGauge(String name, String help, LongSupplier supplier) {
        gauges.add(new Gauge(name, help, supplier));
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
        metrics.observeLatency(elapsedNanos);
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
        metrics.observeLatency(elapsedNanos);
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
                "connections_rejected", connectionsRejected.sum(),
                "idle_connections_closed", idleConnectionsClosed.sum(),
                "first_request_timeouts", firstRequestTimeouts.sum(),
                "requests_shed", requestsShed.sum(),
                "handshake_requests", handshakeRequests.sum(),
                "handshake_version_rejected", handshakeVersionRejected.sum(),
                "unknown_opcodes", unknownOpcodes.sum(),
                "request_errors", requestErrors.sum(),
                "malformed_frames", malformedFrames.sum(),
                "pdx_registry_rejected", pdxRegistryRejected.sum()
        ));

        if (!gauges.isEmpty()) {
            Object[] kv = new Object[gauges.size() * 2];
            for (int i = 0; i < gauges.size(); i++) {
                Gauge gauge = gauges.get(i);
                kv[i * 2] = gauge.name().replaceFirst("^protogemcouch_", "");
                kv[i * 2 + 1] = gauge.supplier().getAsLong();
            }
            lines.add(StructuredLog.event("metrics_gauges", kv));
        }

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
        out.append("\"closed\":").append(connectionsClosed.sum()).append(',');
        out.append("\"rejected\":").append(connectionsRejected.sum()).append(',');
        out.append("\"idleClosed\":").append(idleConnectionsClosed.sum()).append(',');
        out.append("\"firstRequestTimeout\":").append(firstRequestTimeouts.sum());
        out.append("},");

        out.append("\"requests\":{");
        out.append("\"handshakeRequests\":").append(handshakeRequests.sum()).append(',');
        out.append("\"handshakeVersionRejected\":").append(handshakeVersionRejected.sum()).append(',');
        out.append("\"unknownOpcodes\":").append(unknownOpcodes.sum()).append(',');
        out.append("\"requestErrors\":").append(requestErrors.sum()).append(',');
        out.append("\"malformedFrames\":").append(malformedFrames.sum()).append(',');
        out.append("\"pdxRegistryRejected\":").append(pdxRegistryRejected.sum()).append(',');
        out.append("\"requestsShed\":").append(requestsShed.sum());
        out.append("},");

        out.append("\"gauges\":{");
        boolean firstGauge = true;
        for (Gauge gauge : gauges) {
            if (!firstGauge) {
                out.append(',');
            }
            firstGauge = false;
            out.append('"').append(jsonEscape(gauge.name())).append("\":").append(gauge.supplier().getAsLong());
        }
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

            appendJsonLatencyHistogram(out, m);

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

        appendMetricHelp(out, "protogemcouch_connections_rejected_total", "Total client connections rejected for exceeding the max-connections limit.");
        appendMetricType(out, "protogemcouch_connections_rejected_total", "counter");
        appendMetric(out, "protogemcouch_connections_rejected_total", connectionsRejected.sum());

        appendMetricHelp(out, "protogemcouch_idle_connections_closed_total", "Total client connections closed for being idle past the timeout.");
        appendMetricType(out, "protogemcouch_idle_connections_closed_total", "counter");
        appendMetric(out, "protogemcouch_idle_connections_closed_total", idleConnectionsClosed.sum());

        appendMetricHelp(out, "protogemcouch_connections_first_request_timeout_total", "Total client connections closed for not completing a first request within the deadline.");
        appendMetricType(out, "protogemcouch_connections_first_request_timeout_total", "counter");
        appendMetric(out, "protogemcouch_connections_first_request_timeout_total", firstRequestTimeouts.sum());

        appendMetricHelp(out, "protogemcouch_requests_shed_total", "Total requests shed because the handler queue was full (load shedding).");
        appendMetricType(out, "protogemcouch_requests_shed_total", "counter");
        appendMetric(out, "protogemcouch_requests_shed_total", requestsShed.sum());

        appendMetricHelp(out, "protogemcouch_handshake_requests_total", "Total Geode handshake requests received.");
        appendMetricType(out, "protogemcouch_handshake_requests_total", "counter");
        appendMetric(out, "protogemcouch_handshake_requests_total", handshakeRequests.sum());

        appendMetricHelp(out, "protogemcouch_handshake_version_rejected_total", "Total handshakes rejected for an unsupported Geode client protocol version.");
        appendMetricType(out, "protogemcouch_handshake_version_rejected_total", "counter");
        appendMetric(out, "protogemcouch_handshake_version_rejected_total", handshakeVersionRejected.sum());

        appendMetricHelp(out, "protogemcouch_unknown_opcodes_total", "Total unknown opcodes received.");
        appendMetricType(out, "protogemcouch_unknown_opcodes_total", "counter");
        appendMetric(out, "protogemcouch_unknown_opcodes_total", unknownOpcodes.sum());

        appendMetricHelp(out, "protogemcouch_request_errors_total", "Total request errors across all opcodes.");
        appendMetricType(out, "protogemcouch_request_errors_total", "counter");
        appendMetric(out, "protogemcouch_request_errors_total", requestErrors.sum());

        appendMetricHelp(out, "protogemcouch_malformed_frames_total", "Total inbound frames rejected as malformed or oversized.");
        appendMetricType(out, "protogemcouch_malformed_frames_total", "counter");
        appendMetric(out, "protogemcouch_malformed_frames_total", malformedFrames.sum());

        appendMetricHelp(out, "protogemcouch_pdx_registry_rejected_total", "Total PDX type/enum registrations rejected for exceeding the configured registry cap.");
        appendMetricType(out, "protogemcouch_pdx_registry_rejected_total", "counter");
        appendMetric(out, "protogemcouch_pdx_registry_rejected_total", pdxRegistryRejected.sum());

        // Sampled gauges for live in-memory registry sizes (read at scrape time).
        for (Gauge gauge : gauges) {
            appendMetricHelp(out, gauge.name(), gauge.help());
            appendMetricType(out, gauge.name(), "gauge");
            appendMetric(out, gauge.name(), gauge.supplier().getAsLong());
        }

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

            appendLatencyHistogram(out, opcode, operation, m);
        }

        return out.toString();
    }

    private static void appendLatencyHistogram(StringBuilder out, int opcode, String operation, OpMetrics m) {
        long cumulative = 0L;
        for (int i = 0; i < LATENCY_BUCKET_LE.length; i++) {
            cumulative += m.latencyBuckets[i].sum();
            out.append("protogemcouch_operation_latency_seconds_bucket")
                    .append(bucketLabels(opcode, operation, LATENCY_BUCKET_LE[i]))
                    .append(' ').append(cumulative).append('\n');
        }

        long total = cumulative + m.latencyBuckets[LATENCY_BUCKET_LE.length].sum();
        out.append("protogemcouch_operation_latency_seconds_bucket")
                .append(bucketLabels(opcode, operation, "+Inf"))
                .append(' ').append(total).append('\n');

        String labels = labels(opcode, operation);
        out.append("protogemcouch_operation_latency_seconds_sum")
                .append(labels).append(' ').append(secondsString(m.totalLatencyNanos.sum())).append('\n');
        out.append("protogemcouch_operation_latency_seconds_count")
                .append(labels).append(' ').append(total).append('\n');
    }

    private static void appendJsonLatencyHistogram(StringBuilder out, OpMetrics m) {
        out.append("\"latencyBucketsSeconds\":{");
        long cumulative = 0L;
        for (int i = 0; i < LATENCY_BUCKET_LE.length; i++) {
            cumulative += m.latencyBuckets[i].sum();
            out.append('"').append(LATENCY_BUCKET_LE[i]).append("\":").append(cumulative).append(',');
        }
        long total = cumulative + m.latencyBuckets[LATENCY_BUCKET_LE.length].sum();
        out.append("\"+Inf\":").append(total);
        out.append("},");
        out.append("\"latencySumSeconds\":").append(secondsString(m.totalLatencyNanos.sum())).append(',');
        out.append("\"latencyCount\":").append(total).append(',');
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

        appendMetricHelp(out, "protogemcouch_operation_latency_seconds", "Request latency histogram by opcode and operation in seconds.");
        appendMetricType(out, "protogemcouch_operation_latency_seconds", "histogram");
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

    private static String bucketLabels(int opcode, String operation, String le) {
        return "{opcode=\"" + prometheusEscape(String.valueOf(opcode))
                + "\",operation=\"" + prometheusEscape(operation)
                + "\",le=\"" + prometheusEscape(le) + "\"}";
    }

    private static String secondsString(long nanos) {
        return new java.math.BigDecimal(nanos).movePointLeft(9).toPlainString();
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

        /**
         * Per-bucket observation counts. Indices align with {@link #LATENCY_BUCKET_BOUNDS_NANOS};
         * the final slot is the implicit {@code +Inf} bucket. Counts are non-cumulative here and are
         * accumulated at render time.
         */
        private final LongAdder[] latencyBuckets = newLatencyBuckets();

        private void observeLatency(long elapsedNanos) {
            int index = LATENCY_BUCKET_BOUNDS_NANOS.length;
            for (int i = 0; i < LATENCY_BUCKET_BOUNDS_NANOS.length; i++) {
                if (elapsedNanos <= LATENCY_BUCKET_BOUNDS_NANOS[i]) {
                    index = i;
                    break;
                }
            }
            latencyBuckets[index].increment();
        }

        private static LongAdder[] newLatencyBuckets() {
            LongAdder[] buckets = new LongAdder[LATENCY_BUCKET_BOUNDS_NANOS.length + 1];
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
            return buckets;
        }
    }
}
