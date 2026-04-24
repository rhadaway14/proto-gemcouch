package com.protogemcouch.health;

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
    private HttpServer server;

    public HealthHttpServer(int port, HealthState healthState) {
        this.port = port;
        this.healthState = healthState;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/live", this::handleLive);
        server.createContext("/ready", this::handleReady);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();

        log.info(StructuredLog.event(
                "health_server_started",
                "port", port
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
        int statusCode = healthState.isLive() ? 200 : 503;
        String body = json("live", healthState.isLive(), healthState.getStatus());
        write(exchange, statusCode, body);
    }

    private void handleReady(HttpExchange exchange) throws IOException {
        int statusCode = healthState.isReady() ? 200 : 503;
        String body = json("ready", healthState.isReady(), healthState.getStatus());
        write(exchange, statusCode, body);
    }

    private void write(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
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