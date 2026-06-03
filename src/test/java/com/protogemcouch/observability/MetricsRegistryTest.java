package com.protogemcouch.observability;

import com.protogemcouch.wire.MessageTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsRegistryTest {

    @Test
    void snapshotLinesShouldIncludeSummaryLine() {
        MetricsRegistry registry = new MetricsRegistry();

        registry.recordConnectionOpened();
        registry.recordConnectionClosed();
        registry.recordHandshakeRequest();
        registry.recordUnknownOpcode(9999);

        List<String> lines = registry.snapshotLines();

        assertNotNull(lines);
        assertFalse(lines.isEmpty());

        String summary = lines.get(0);

        assertTrue(summary.contains("event=metrics_summary"));
        assertTrue(summary.contains("connections_opened=1"));
        assertTrue(summary.contains("connections_closed=1"));
        assertTrue(summary.contains("handshake_requests=1"));
        assertTrue(summary.contains("unknown_opcodes=1"));
    }

    @Test
    void snapshotLinesShouldIncludePerOpcodeMetrics() {
        MetricsRegistry registry = new MetricsRegistry();

        int opcode = MessageTypes.GET_ALL_70;

        registry.recordRequestStart(opcode);
        registry.recordRequestSuccess(opcode, 1_000_000L);

        List<String> lines = registry.snapshotLines();

        assertTrue(
                lines.stream().anyMatch(line ->
                        line.contains("event=metrics_opcode")
                                && line.contains("opcode=" + opcode)
                                && line.contains("operation=GET_ALL")
                                && line.contains("requests=1")
                                && line.contains("successes=1")
                ),
                "Expected snapshot lines to contain GET_ALL opcode metrics, actual lines: " + lines
        );
    }

    @Test
    void snapshotJsonShouldIncludeConnectionAndRequestCounters() {
        MetricsRegistry registry = new MetricsRegistry();

        registry.recordConnectionOpened();
        registry.recordConnectionOpened();
        registry.recordConnectionClosed();
        registry.recordHandshakeRequest();
        registry.recordUnknownOpcode(9999);

        String json = registry.snapshotJson();

        assertNotNull(json);
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertTrue(json.contains("\"connections\""));
        assertTrue(json.contains("\"opened\":2"));
        assertTrue(json.contains("\"closed\":1"));
        assertTrue(json.contains("\"requests\""));
        assertTrue(json.contains("\"handshakeRequests\":1"));
        assertTrue(json.contains("\"unknownOpcodes\":1"));
    }

    @Test
    void snapshotJsonShouldIncludePerOpcodeLatencyAndErrorDetails() {
        MetricsRegistry registry = new MetricsRegistry();

        int opcode = MessageTypes.GET_ALL_70;

        registry.recordRequestStart(opcode);
        registry.recordRequestSuccess(opcode, 1_000_000L);

        registry.recordRequestStart(opcode);
        registry.recordRequestError(opcode, 3_000_000L, new IllegalStateException("boom"));

        String json = registry.snapshotJson();

        assertTrue(json.contains("\"operations\""));
        assertTrue(json.contains("\"opcode\":" + opcode));
        assertTrue(json.contains("\"operation\":\"GET_ALL\""));
        assertTrue(json.contains("\"requests\":2"));
        assertTrue(json.contains("\"successes\":1"));
        assertTrue(json.contains("\"errors\":1"));
        assertTrue(json.contains("\"avgLatencyNs\":2000000"));
        assertTrue(json.contains("\"maxLatencyNs\":3000000"));
        assertTrue(json.contains("\"lastLatencyNs\":3000000"));
        assertTrue(json.contains("\"lastError\":\"IllegalStateException: boom\""));
        assertTrue(json.contains("\"lastUpdatedEpochMs\":"));
    }

    @Test
    void snapshotPrometheusShouldIncludeGlobalCounters() {
        MetricsRegistry registry = new MetricsRegistry();

        registry.recordConnectionOpened();
        registry.recordConnectionClosed();
        registry.recordHandshakeRequest();
        registry.recordUnknownOpcode(9999);

        String metrics = registry.snapshotPrometheus();

        assertNotNull(metrics);
        assertTrue(metrics.contains("# HELP protogemcouch_connections_opened_total"));
        assertTrue(metrics.contains("# TYPE protogemcouch_connections_opened_total counter"));
        assertTrue(metrics.contains("protogemcouch_connections_opened_total 1"));
        assertTrue(metrics.contains("protogemcouch_connections_closed_total 1"));
        assertTrue(metrics.contains("protogemcouch_handshake_requests_total 1"));
        assertTrue(metrics.contains("protogemcouch_unknown_opcodes_total 1"));
    }

    @Test
    void snapshotPrometheusShouldIncludePerOpcodeMetrics() {
        MetricsRegistry registry = new MetricsRegistry();

        int opcode = MessageTypes.GET_ALL_70;

        registry.recordRequestStart(opcode);
        registry.recordRequestSuccess(opcode, 1_500_000L);

        registry.recordRequestStart(opcode);
        registry.recordRequestError(opcode, 2_500_000L, new RuntimeException("failure"));

        String metrics = registry.snapshotPrometheus();

        String labels = "{opcode=\"" + opcode + "\",operation=\"GET_ALL\"}";

        assertTrue(metrics.contains("protogemcouch_operation_requests_total" + labels + " 2"));
        assertTrue(metrics.contains("protogemcouch_operation_successes_total" + labels + " 1"));
        assertTrue(metrics.contains("protogemcouch_operation_errors_total" + labels + " 1"));
        assertTrue(metrics.contains("protogemcouch_operation_latency_avg_ns" + labels + " 2000000"));
        assertTrue(metrics.contains("protogemcouch_operation_latency_max_ns" + labels + " 2500000"));
        assertTrue(metrics.contains("protogemcouch_operation_latency_last_ns" + labels + " 2500000"));
    }

    @Test
    void emptyRegistryShouldHaveEmptyOperationsInJson() {
        MetricsRegistry registry = new MetricsRegistry();

        String json = registry.snapshotJson();

        assertTrue(json.contains("\"operations\":[]"));
    }

    @Test
    void requestSuccessShouldNotIncrementRequestCountByItself() {
        MetricsRegistry registry = new MetricsRegistry();

        int opcode = MessageTypes.GET;

        registry.recordRequestSuccess(opcode, 100L);

        String json = registry.snapshotJson();

        assertTrue(json.contains("\"successes\":1"));
        assertTrue(json.contains("\"requests\":0"));
        assertTrue(json.contains("\"avgLatencyNs\":0"));
    }
    @Test
    void snapshotJsonShouldIncludeRequestAndResponseByteMetrics() {
        MetricsRegistry registry = new MetricsRegistry();

        int opcode = MessageTypes.GET_ALL_70;

        registry.recordRequestStart(opcode);
        registry.recordRequestBytes(opcode, 128L);
        registry.recordResponseBytes(opcode, 4096L);
        registry.recordRequestSuccess(opcode, 1_000_000L);

        registry.recordRequestStart(opcode);
        registry.recordRequestBytes(opcode, 256L);
        registry.recordResponseBytes(opcode, 8192L);
        registry.recordRequestSuccess(opcode, 2_000_000L);

        String json = registry.snapshotJson();

        assertTrue(json.contains("\"requestBytesTotal\":384"));
        assertTrue(json.contains("\"requestBytesLast\":256"));
        assertTrue(json.contains("\"requestBytesMax\":256"));
        assertTrue(json.contains("\"requestBytesAvg\":192"));

        assertTrue(json.contains("\"responseBytesTotal\":12288"));
        assertTrue(json.contains("\"responseBytesLast\":8192"));
        assertTrue(json.contains("\"responseBytesMax\":8192"));
        assertTrue(json.contains("\"responseBytesAvg\":6144"));
    }

    @Test
    void snapshotPrometheusShouldIncludeRequestAndResponseByteMetrics() {
        MetricsRegistry registry = new MetricsRegistry();

        int opcode = MessageTypes.GET_ALL_70;
        String labels = "{opcode=\"" + opcode + "\",operation=\"GET_ALL\"}";

        registry.recordRequestStart(opcode);
        registry.recordRequestBytes(opcode, 150L);
        registry.recordResponseBytes(opcode, 10_000L);
        registry.recordRequestSuccess(opcode, 1_500_000L);

        String metrics = registry.snapshotPrometheus();

        assertTrue(metrics.contains("# HELP protogemcouch_operation_request_bytes_total"));
        assertTrue(metrics.contains("# TYPE protogemcouch_operation_request_bytes_total counter"));
        assertTrue(metrics.contains("protogemcouch_operation_request_bytes_total" + labels + " 150"));
        assertTrue(metrics.contains("protogemcouch_operation_request_bytes_last" + labels + " 150"));
        assertTrue(metrics.contains("protogemcouch_operation_request_bytes_max" + labels + " 150"));
        assertTrue(metrics.contains("protogemcouch_operation_request_bytes_avg" + labels + " 150"));

        assertTrue(metrics.contains("# HELP protogemcouch_operation_response_bytes_total"));
        assertTrue(metrics.contains("# TYPE protogemcouch_operation_response_bytes_total counter"));
        assertTrue(metrics.contains("protogemcouch_operation_response_bytes_total" + labels + " 10000"));
        assertTrue(metrics.contains("protogemcouch_operation_response_bytes_last" + labels + " 10000"));
        assertTrue(metrics.contains("protogemcouch_operation_response_bytes_max" + labels + " 10000"));
        assertTrue(metrics.contains("protogemcouch_operation_response_bytes_avg" + labels + " 10000"));
    }

    @Test
    void negativeByteMetricsShouldBeIgnored() {
        MetricsRegistry registry = new MetricsRegistry();

        int opcode = MessageTypes.GET;

        registry.recordRequestStart(opcode);
        registry.recordRequestBytes(opcode, -1L);
        registry.recordResponseBytes(opcode, -1L);
        registry.recordRequestSuccess(opcode, 100L);

        String json = registry.snapshotJson();

        assertTrue(json.contains("\"requestBytesTotal\":0"));
        assertTrue(json.contains("\"requestBytesLast\":0"));
        assertTrue(json.contains("\"requestBytesMax\":0"));
        assertTrue(json.contains("\"responseBytesTotal\":0"));
        assertTrue(json.contains("\"responseBytesLast\":0"));
        assertTrue(json.contains("\"responseBytesMax\":0"));
    }

    @Test
    void malformedFrameCounterShouldAppearInAllRenderings() {
        MetricsRegistry registry = new MetricsRegistry();

        registry.recordMalformedFrame();
        registry.recordMalformedFrame();

        String json = registry.snapshotJson();
        assertTrue(json.contains("\"malformedFrames\":2"));

        String prometheus = registry.snapshotPrometheus();
        assertTrue(prometheus.contains("# TYPE protogemcouch_malformed_frames_total counter"));
        assertTrue(prometheus.contains("protogemcouch_malformed_frames_total 2"));

        List<String> lines = registry.snapshotLines();
        assertTrue(lines.get(0).contains("malformed_frames=2"));
    }

    @Test
    void connectionGuardCountersShouldAppearInAllRenderings() {
        MetricsRegistry registry = new MetricsRegistry();

        registry.recordConnectionRejected();
        registry.recordConnectionRejected();
        registry.recordConnectionRejected();
        registry.recordIdleConnectionClosed();

        String json = registry.snapshotJson();
        assertTrue(json.contains("\"rejected\":3"));
        assertTrue(json.contains("\"idleClosed\":1"));

        String prometheus = registry.snapshotPrometheus();
        assertTrue(prometheus.contains("# TYPE protogemcouch_connections_rejected_total counter"));
        assertTrue(prometheus.contains("protogemcouch_connections_rejected_total 3"));
        assertTrue(prometheus.contains("# TYPE protogemcouch_idle_connections_closed_total counter"));
        assertTrue(prometheus.contains("protogemcouch_idle_connections_closed_total 1"));

        List<String> lines = registry.snapshotLines();
        assertTrue(lines.get(0).contains("connections_rejected=3"));
        assertTrue(lines.get(0).contains("idle_connections_closed=1"));
    }

    @Test
    void slowlorisAndLoadSheddingCountersShouldAppearInAllRenderings() {
        MetricsRegistry registry = new MetricsRegistry();

        registry.recordFirstRequestTimeout();
        registry.recordFirstRequestTimeout();
        registry.recordRequestShed();

        String json = registry.snapshotJson();
        assertTrue(json.contains("\"firstRequestTimeout\":2"));
        assertTrue(json.contains("\"requestsShed\":1"));

        String prometheus = registry.snapshotPrometheus();
        assertTrue(prometheus.contains("# TYPE protogemcouch_connections_first_request_timeout_total counter"));
        assertTrue(prometheus.contains("protogemcouch_connections_first_request_timeout_total 2"));
        assertTrue(prometheus.contains("# TYPE protogemcouch_requests_shed_total counter"));
        assertTrue(prometheus.contains("protogemcouch_requests_shed_total 1"));

        List<String> lines = registry.snapshotLines();
        assertTrue(lines.get(0).contains("first_request_timeouts=2"));
        assertTrue(lines.get(0).contains("requests_shed=1"));
    }

    @Test
    void snapshotPrometheusShouldIncludeLatencyHistogram() {
        MetricsRegistry registry = new MetricsRegistry();

        int opcode = MessageTypes.GET_ALL_70;
        String labelPrefix = "{opcode=\"" + opcode + "\",operation=\"GET_ALL\"";
        String summaryLabels = labelPrefix + "}";

        // 0.4 ms -> le 0.0005, 0.8 ms -> le 0.001, 3 ms -> le 0.005
        registry.recordRequestStart(opcode);
        registry.recordRequestSuccess(opcode, 400_000L);
        registry.recordRequestStart(opcode);
        registry.recordRequestSuccess(opcode, 800_000L);
        registry.recordRequestStart(opcode);
        registry.recordRequestError(opcode, 3_000_000L, new RuntimeException("boom"));

        String metrics = registry.snapshotPrometheus();

        assertTrue(metrics.contains("# TYPE protogemcouch_operation_latency_seconds histogram"));

        // Buckets are cumulative.
        assertTrue(metrics.contains("protogemcouch_operation_latency_seconds_bucket" + labelPrefix + ",le=\"0.0005\"} 1"));
        assertTrue(metrics.contains("protogemcouch_operation_latency_seconds_bucket" + labelPrefix + ",le=\"0.001\"} 2"));
        assertTrue(metrics.contains("protogemcouch_operation_latency_seconds_bucket" + labelPrefix + ",le=\"0.0025\"} 2"));
        assertTrue(metrics.contains("protogemcouch_operation_latency_seconds_bucket" + labelPrefix + ",le=\"0.005\"} 3"));
        assertTrue(metrics.contains("protogemcouch_operation_latency_seconds_bucket" + labelPrefix + ",le=\"+Inf\"} 3"));

        assertTrue(metrics.contains("protogemcouch_operation_latency_seconds_count" + summaryLabels + " 3"));
        // (400000 + 800000 + 3000000) ns = 0.004200000 s
        assertTrue(metrics.contains("protogemcouch_operation_latency_seconds_sum" + summaryLabels + " 0.004200000"));
    }

    @Test
    void snapshotJsonShouldIncludeLatencyHistogram() {
        MetricsRegistry registry = new MetricsRegistry();

        int opcode = MessageTypes.GET_ALL_70;

        registry.recordRequestStart(opcode);
        registry.recordRequestSuccess(opcode, 400_000L);
        registry.recordRequestStart(opcode);
        registry.recordRequestSuccess(opcode, 3_000_000L);

        String json = registry.snapshotJson();

        assertTrue(json.contains("\"latencyBucketsSeconds\":{"));
        assertTrue(json.contains("\"0.0005\":1"));
        assertTrue(json.contains("\"0.005\":2"));
        assertTrue(json.contains("\"+Inf\":2"));
        assertTrue(json.contains("\"latencyCount\":2"));
        assertTrue(json.contains("\"latencySumSeconds\":0.003400000"));
    }

}
