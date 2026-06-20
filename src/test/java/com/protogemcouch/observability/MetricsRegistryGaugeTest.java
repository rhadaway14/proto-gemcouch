package com.protogemcouch.observability;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sampled gauges are read at scrape time (so the value is always current) and rendered as Prometheus
 * gauges + in the JSON snapshot; the PDX-registry-rejection counter is rendered as a counter.
 */
class MetricsRegistryGaugeTest {

    @Test
    void registeredGaugeIsSampledAtScrapeAndRenderedInPrometheus() {
        MetricsRegistry metrics = new MetricsRegistry();
        AtomicLong value = new AtomicLong(3);
        metrics.registerGauge("protogemcouch_test_gauge", "Test gauge.", value::get);

        String prometheus = metrics.snapshotPrometheus();
        assertTrue(prometheus.contains("# TYPE protogemcouch_test_gauge gauge"),
                "gauge declares its TYPE: " + prometheus);
        assertTrue(prometheus.contains("protogemcouch_test_gauge 3"), "gauge renders its current value");

        value.set(7); // changing the source must be reflected on the next scrape (no caching)
        assertTrue(metrics.snapshotPrometheus().contains("protogemcouch_test_gauge 7"),
                "gauge is sampled at scrape time");
        assertTrue(metrics.snapshotJson().contains("\"protogemcouch_test_gauge\":7"),
                "gauge appears in the JSON snapshot");
    }

    @Test
    void pdxRegistryRejectionIsRenderedAsACounter() {
        MetricsRegistry metrics = new MetricsRegistry();
        assertTrue(metrics.snapshotPrometheus().contains("protogemcouch_pdx_registry_rejected_total 0"),
                "counter present and starts at 0");

        metrics.recordPdxRegistryRejected();
        metrics.recordPdxRegistryRejected();
        String prometheus = metrics.snapshotPrometheus();
        assertTrue(prometheus.contains("# TYPE protogemcouch_pdx_registry_rejected_total counter"),
                "declares counter type");
        assertTrue(prometheus.contains("protogemcouch_pdx_registry_rejected_total 2"),
                "reflects the recorded rejections");
    }
}
