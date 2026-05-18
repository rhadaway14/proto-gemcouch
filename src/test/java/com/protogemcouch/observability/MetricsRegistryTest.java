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
}
