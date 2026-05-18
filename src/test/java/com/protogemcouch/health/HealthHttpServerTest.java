package com.protogemcouch.health;

import com.protogemcouch.observability.MetricsRegistry;
import com.protogemcouch.wire.MessageTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthHttpServerTest {

    private HealthHttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void liveShouldReturn200WhenLive() throws Exception {
        HealthState healthState = new HealthState();
        healthState.markStarting();

        int port = freePort();
        server = new HealthHttpServer(port, healthState, new MetricsRegistry());
        server.start();

        HttpResponse<String> response = get(port, "/live");

        assertEquals(200, response.statusCode());
        assertJson(response);
        assertTrue(response.body().contains("\"endpoint\":\"live\""));
        assertTrue(response.body().contains("\"ok\":true"));
        assertTrue(response.body().contains("\"status\":\"starting\""));
    }

    @Test
    void liveShouldReturn503WhenNotLive() throws Exception {
        HealthState healthState = new HealthState();
        healthState.markStopped();

        int port = freePort();
        server = new HealthHttpServer(port, healthState, new MetricsRegistry());
        server.start();

        HttpResponse<String> response = get(port, "/live");

        assertEquals(503, response.statusCode());
        assertJson(response);
        assertTrue(response.body().contains("\"endpoint\":\"live\""));
        assertTrue(response.body().contains("\"ok\":false"));
        assertTrue(response.body().contains("\"status\":\"stopped\""));
    }

    @Test
    void readyShouldReturn200WhenReady() throws Exception {
        HealthState healthState = new HealthState();
        healthState.markServerBound();

        int port = freePort();
        server = new HealthHttpServer(port, healthState, new MetricsRegistry());
        server.start();

        HttpResponse<String> response = get(port, "/ready");

        assertEquals(200, response.statusCode());
        assertJson(response);
        assertTrue(response.body().contains("\"endpoint\":\"ready\""));
        assertTrue(response.body().contains("\"ok\":true"));
        assertTrue(response.body().contains("\"status\":\"ready\""));
    }

    @Test
    void readyShouldReturn503WhenNotReady() throws Exception {
        HealthState healthState = new HealthState();
        healthState.markStarting();

        int port = freePort();
        server = new HealthHttpServer(port, healthState, new MetricsRegistry());
        server.start();

        HttpResponse<String> response = get(port, "/ready");

        assertEquals(503, response.statusCode());
        assertJson(response);
        assertTrue(response.body().contains("\"endpoint\":\"ready\""));
        assertTrue(response.body().contains("\"ok\":false"));
        assertTrue(response.body().contains("\"status\":\"starting\""));
    }

    @Test
    void metricsJsonShouldReturnRuntimeMetrics() throws Exception {
        HealthState healthState = new HealthState();
        healthState.markServerBound();

        MetricsRegistry metrics = new MetricsRegistry();
        metrics.recordConnectionOpened();
        metrics.recordHandshakeRequest();
        metrics.recordRequestStart(MessageTypes.GET_ALL_70);
        metrics.recordRequestSuccess(MessageTypes.GET_ALL_70, 123_456L);

        int port = freePort();
        server = new HealthHttpServer(port, healthState, metrics);
        server.start();

        HttpResponse<String> response = get(port, "/metrics/json");

        assertEquals(200, response.statusCode());
        assertJson(response);
        assertTrue(response.body().contains("\"connections\""));
        assertTrue(response.body().contains("\"opened\":1"));
        assertTrue(response.body().contains("\"handshakeRequests\":1"));
        assertTrue(response.body().contains("\"operations\""));
        assertTrue(response.body().contains("\"operation\":\"GET_ALL\""));
        assertTrue(response.body().contains("\"requests\":1"));
        assertTrue(response.body().contains("\"successes\":1"));
    }

    @Test
    void prometheusMetricsShouldReturnTextFormatMetrics() throws Exception {
        HealthState healthState = new HealthState();
        healthState.markServerBound();

        MetricsRegistry metrics = new MetricsRegistry();
        metrics.recordConnectionOpened();
        metrics.recordRequestStart(MessageTypes.GET);
        metrics.recordRequestSuccess(MessageTypes.GET, 987_654L);

        int port = freePort();
        server = new HealthHttpServer(port, healthState, metrics);
        server.start();

        HttpResponse<String> response = get(port, "/metrics");

        assertEquals(200, response.statusCode());
        assertPrometheusText(response);
        assertTrue(response.body().contains("# HELP protogemcouch_connections_opened_total"));
        assertTrue(response.body().contains("# TYPE protogemcouch_connections_opened_total counter"));
        assertTrue(response.body().contains("protogemcouch_connections_opened_total 1"));
        assertTrue(response.body().contains("protogemcouch_operation_requests_total"));
        assertTrue(response.body().contains("operation=\"GET\""));
    }

    @Test
    void nonGetLiveShouldReturn405() throws Exception {
        HealthState healthState = new HealthState();
        healthState.markStarting();

        int port = freePort();
        server = new HealthHttpServer(port, healthState, new MetricsRegistry());
        server.start();

        HttpResponse<String> response = post(port, "/live");

        assertEquals(405, response.statusCode());
    }

    @Test
    void nonGetMetricsShouldReturn405() throws Exception {
        HealthState healthState = new HealthState();
        healthState.markServerBound();

        int port = freePort();
        server = new HealthHttpServer(port, healthState, new MetricsRegistry());
        server.start();

        HttpResponse<String> response = post(port, "/metrics");

        assertEquals(405, response.statusCode());
    }

    @Test
    void unknownPathShouldReturn404() throws Exception {
        HealthState healthState = new HealthState();
        healthState.markServerBound();

        int port = freePort();
        server = new HealthHttpServer(port, healthState, new MetricsRegistry());
        server.start();

        HttpResponse<String> response = get(port, "/does-not-exist");

        assertEquals(404, response.statusCode());
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();

        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static void assertJson(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(
                contentType.toLowerCase().contains("application/json"),
                "Expected JSON Content-Type but got: " + contentType
        );
    }

    private static void assertPrometheusText(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(
                contentType.toLowerCase().contains("text/plain"),
                "Expected Prometheus text Content-Type but got: " + contentType
        );
    }
}
