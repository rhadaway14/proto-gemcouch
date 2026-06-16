package com.protogemcouch.observability;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tracing is off unless explicitly configured via {@code OTEL_*}, and the no-op tracer is always safe
 * to use (so the request-path / repository instrumentation costs nothing when tracing is disabled).
 */
class TracingTest {

    private static Function<String, String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    void disabledWhenNoOtelConfig() {
        assertFalse(Tracing.configuredFrom(env(Map.of())));
    }

    @Test
    void enabledByOtlpEndpoint() {
        assertTrue(Tracing.configuredFrom(env(Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4317"))));
        assertTrue(Tracing.configuredFrom(env(Map.of("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://collector:4318/v1/traces"))));
    }

    @Test
    void enabledByNonNoneExporter() {
        assertTrue(Tracing.configuredFrom(env(Map.of("OTEL_TRACES_EXPORTER", "otlp"))));
        assertFalse(Tracing.configuredFrom(env(Map.of("OTEL_TRACES_EXPORTER", "none"))));
    }

    @Test
    void disabledFlagWinsOverEndpoint() {
        assertFalse(Tracing.configuredFrom(env(Map.of(
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4317",
                "OTEL_SDK_DISABLED", "true"))));
    }

    @Test
    void noopTracerIsAlwaysUsableWhenDisabled() {
        // Default state (no OTEL env in the test JVM): not enabled, but the tracer is a safe no-op.
        assertFalse(Tracing.enabled());
        assertNotNull(Tracing.tracer());
        // Building/using a span on the no-op tracer must not throw.
        Tracing.tracer().spanBuilder("test.span").startSpan().end();
    }
}
