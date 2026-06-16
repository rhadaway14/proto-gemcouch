package com.protogemcouch.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed-tracing bootstrap. <strong>Off by default</strong>: the SDK is only built when tracing
 * is configured via the standard {@code OTEL_*} environment (an OTLP endpoint, or a non-{@code none}
 * traces exporter), and never when {@code OTEL_SDK_DISABLED=true}. When unconfigured, {@link #tracer()}
 * returns a no-op tracer, so the instrumentation in the request path and the {@code TracingRepository}
 * decorator add no measurable overhead.
 *
 * <p>When enabled, the shim emits a span per Geode operation (created in the request dispatch) with the
 * backend Couchbase calls nested under it (via the repository decorator), exported over OTLP — set
 * {@code OTEL_SERVICE_NAME}, {@code OTEL_EXPORTER_OTLP_ENDPOINT}, etc. as usual.
 */
public final class Tracing {

    private static final Logger log = LoggerFactory.getLogger(Tracing.class);
    private static final String INSTRUMENTATION_NAME = "com.protogemcouch";

    private static volatile Tracer tracer = OpenTelemetry.noop().getTracer(INSTRUMENTATION_NAME);
    private static volatile boolean enabled = false;
    private static boolean initialized = false;

    private Tracing() {
    }

    /** Initialize tracing from {@code OTEL_*} env, once. A no-op when tracing is not configured. */
    public static synchronized void initFromEnv() {
        if (initialized) {
            return;
        }
        initialized = true;

        if (!configuredFromEnv()) {
            log.info(StructuredLog.event("tracing_disabled", "reason", "no OTEL exporter/endpoint configured"));
            return;
        }

        try {
            OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk();
            tracer = sdk.getTracer(INSTRUMENTATION_NAME);
            enabled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(sdk::close, "otel-shutdown"));
            log.info(StructuredLog.event(
                    "tracing_enabled",
                    "serviceName", envOrDefault("OTEL_SERVICE_NAME", "unset"),
                    "endpoint", envOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT",
                            envOrDefault("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "default"))));
        } catch (Throwable t) {
            // Never let a tracing-init problem take down the shim; stay no-op.
            log.warn(StructuredLog.event("tracing_init_failed", "error", t.getMessage()), t);
        }
    }

    /** True once the real tracing SDK is installed (gates the repository decorator). */
    public static boolean enabled() {
        return enabled;
    }

    /** The active tracer — a no-op tracer until/unless tracing is enabled. */
    public static Tracer tracer() {
        return tracer;
    }

    /** Tracing is on when an OTLP endpoint or a non-{@code none} traces exporter is set (and not disabled). */
    static boolean configuredFromEnv() {
        return configuredFrom(System::getenv);
    }

    /** Testable core of {@link #configuredFromEnv()} against an arbitrary env lookup. */
    static boolean configuredFrom(java.util.function.Function<String, String> env) {
        if ("true".equalsIgnoreCase(env.apply("OTEL_SDK_DISABLED"))) {
            return false;
        }
        boolean hasEndpoint = notBlank(env.apply("OTEL_EXPORTER_OTLP_ENDPOINT"))
                || notBlank(env.apply("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"));
        String exporter = env.apply("OTEL_TRACES_EXPORTER");
        boolean hasExporter = notBlank(exporter) && !"none".equalsIgnoreCase(exporter.trim());
        return hasEndpoint || hasExporter;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
