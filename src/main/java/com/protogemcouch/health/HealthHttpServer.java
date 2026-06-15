package com.protogemcouch.health;

import com.protogemcouch.observability.MetricsRegistry;
import com.protogemcouch.observability.StructuredLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
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
    private final String bindAddress;
    private final SSLContext sslContext;
    private final String[] tlsProtocols;
    private final String[] tlsCipherSuites;
    private HttpServer server;

    public HealthHttpServer(int port, HealthState healthState) {
        this(port, healthState, null);
    }

    public HealthHttpServer(int port, HealthState healthState, MetricsRegistry metricsRegistry) {
        this(port, healthState, metricsRegistry, null, null);
    }

    /**
     * @param bindAddress interface to bind to; {@code null}/blank binds all interfaces
     * @param sslContext  when non-null, the admin endpoints are served over HTTPS
     */
    public HealthHttpServer(int port, HealthState healthState, MetricsRegistry metricsRegistry,
                            String bindAddress, SSLContext sslContext) {
        this(port, healthState, metricsRegistry, bindAddress, sslContext, null, null);
    }

    /**
     * @param tlsProtocols    enabled TLS protocols for the HTTPS endpoint ({@code null} = provider default)
     * @param tlsCipherSuites enabled cipher suites ({@code null} = provider default)
     */
    public HealthHttpServer(int port, HealthState healthState, MetricsRegistry metricsRegistry,
                            String bindAddress, SSLContext sslContext,
                            String[] tlsProtocols, String[] tlsCipherSuites) {
        this.port = port;
        this.healthState = healthState;
        this.metricsRegistry = metricsRegistry;
        this.bindAddress = bindAddress;
        this.sslContext = sslContext;
        this.tlsProtocols = tlsProtocols == null ? null : tlsProtocols.clone();
        this.tlsCipherSuites = tlsCipherSuites == null ? null : tlsCipherSuites.clone();
    }

    public void start() throws IOException {
        InetSocketAddress address = (bindAddress == null || bindAddress.isBlank())
                ? new InetSocketAddress(port)
                : new InetSocketAddress(bindAddress, port);

        if (sslContext != null) {
            HttpsServer https = HttpsServer.create(address, 0);
            // Pin the HTTPS endpoint's protocols/ciphers to the same policy as the Geode listener
            // (instead of the JDK defaults the bare HttpsConfigurator would use).
            https.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters sslParameters = sslContext.getDefaultSSLParameters();
                    if (tlsProtocols != null) {
                        sslParameters.setProtocols(tlsProtocols);
                    }
                    if (tlsCipherSuites != null) {
                        sslParameters.setCipherSuites(tlsCipherSuites);
                    }
                    params.setSSLParameters(sslParameters);
                }
            });
            server = https;
        } else {
            server = HttpServer.create(address, 0);
        }

        server.createContext("/live", this::handleLive);
        server.createContext("/ready", this::handleReady);
        server.createContext("/metrics/json", this::handleMetricsJson);
        server.createContext("/metrics", this::handleMetricsPrometheus);
        server.setExecutor(Executors.newFixedThreadPool(3));
        server.start();

        log.info(StructuredLog.event(
                "health_server_started",
                "port", port,
                "bindAddress", (bindAddress == null || bindAddress.isBlank()) ? "0.0.0.0" : bindAddress,
                "scheme", sslContext != null ? "https" : "http",
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
