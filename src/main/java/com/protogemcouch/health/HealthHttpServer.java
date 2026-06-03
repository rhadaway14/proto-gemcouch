package com.protogemcouch.health;

import com.protogemcouch.observability.MetricsRegistry;
import com.protogemcouch.observability.StructuredLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class HealthHttpServer {

    private static final Logger log = LoggerFactory.getLogger(HealthHttpServer.class);

    private final int port;
    private final HealthState healthState;
    private final MetricsRegistry metricsRegistry;
    private HttpServer server;

    public HealthHttpServer(int port, HealthState healthState) {
        this(port, healthState, null);
    }

    public HealthHttpServer(int port, HealthState healthState, MetricsRegistry metricsRegistry) {
        this.port = port;
        this.healthState = healthState;
        this.metricsRegistry = metricsRegistry;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/live", this::handleLive);
        server.createContext("/ready", this::handleReady);
        server.createContext("/metrics/json", this::handleMetricsJson);
        server.createContext("/metrics", this::handleMetricsPrometheus);
        server.setExecutor(Executors.newFixedThreadPool(3));
        server.start();

        log.info(StructuredLog.event(
                "health_server_started",
                "port", port,
                "livePath", "/live",
                "readyPath", "/ready",
                "metricsJsonPath", "/metrics/json",
                "metricsPrometheusPath", "/metrics"
        ));
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info(StructuredLog.event(
                    "health_server_stopped",
                    "port", port
            ));
        }
    }

    private void handleLive(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            write(exchange, 405, json("live", false, "method_not_allowed"), "application/json; charset=utf-8");
            return;
        }

        int statusCode = healthState.isLive() ? 200 : 503;
        String body = json("live", healthState.isLive(), healthState.getStatus());
        write(exchange, statusCode, body, "application/json; charset=utf-8");
    }

    private void handleReady(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            write(exchange, 405, json("ready", false, "method_not_allowed"), "application/json; charset=utf-8");
            return;
        }

        int statusCode = healthState.isReady() ? 200 : 503;
        String body = json("ready", healthState.isReady(), healthState.getStatus());
        write(exchange, statusCode, body, "application/json; charset=utf-8");
    }

    private void handleMetricsJson(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            write(exchange, 405, json("metrics/json", false, "method_not_allowed"), "application/json; charset=utf-8");
            return;
        }

        if (metricsRegistry == null) {
            write(exchange, 503, json("metrics/json", false, "metrics_unavailable"), "application/json; charset=utf-8");
            return;
        }

        write(exchange, 200, metricsRegistry.snapshotJson(), "application/json; charset=utf-8");
    }

    private void handleMetricsPrometheus(HttpExchange exchange) throws IOException {
        if (!isGet(exchange)) {
            write(exchange, 405, "method_not_allowed\n", "text/plain; charset=utf-8");
            return;
        }

        if (metricsRegistry == null) {
            write(exchange, 503, "metrics_unavailable\n", "text/plain; charset=utf-8");
            return;
        }

        write(exchange, 200, metricsRegistry.snapshotPrometheus(), "text/plain; version=0.0.4; charset=utf-8");
    }

    private boolean isGet(HttpExchange exchange) {
        return "GET".equalsIgnoreCase(exchange.getRequestMethod());
    }

    private void write(HttpExchange exchange, int statusCode, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private String json(String endpoint, boolean ok, String status) {
        return "{"
                + "\"endpoint\":\"" + escape(endpoint) + "\","
                + "\"ok\":" + ok + ","
                + "\"status\":\"" + escape(status) + "\""
                + "}";
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
