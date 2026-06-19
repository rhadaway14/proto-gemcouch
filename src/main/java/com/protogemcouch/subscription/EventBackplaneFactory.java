package com.protogemcouch.subscription;

/**
 * Selects the cross-replica {@link EventBackplane} from the environment. The default is
 * {@link NoOpEventBackplane} (single-instance, no dependency).
 *
 * <ul>
 *   <li>{@code EVENT_BACKPLANE} — {@code none} (default), {@code mesh}, or {@code redis}</li>
 *   <li><b>mesh</b> (self-contained, no broker): {@code MESH_PORT} (default {@code 40406}); peers from
 *       {@code MESH_PEER_DNS} (a k8s headless Service name, resolved on the mesh port) or
 *       {@code MESH_PEERS} ({@code host:port,host:port}); {@code MESH_DISCOVERY_INTERVAL_SECONDS}
 *       (default {@code 10}).</li>
 *   <li><b>redis</b>: {@code REDIS_HOST} (default {@code 127.0.0.1}), {@code REDIS_PORT}
 *       (default {@code 6379}), {@code EVENT_BACKPLANE_CHANNEL} (default {@code protogemcouch-events}).</li>
 * </ul>
 */
public final class EventBackplaneFactory {

    private EventBackplaneFactory() {
    }

    public static EventBackplane fromEnvironment() {
        String mode = System.getenv("EVENT_BACKPLANE");
        if (mode == null) {
            return new NoOpEventBackplane();
        }
        switch (mode.trim().toLowerCase()) {
            case "mesh":
                return meshFromEnvironment();
            case "redis":
                return new RedisEventBackplane(
                        envOr("REDIS_HOST", "127.0.0.1"),
                        parsePort(System.getenv("REDIS_PORT"), 6379),
                        envOr("EVENT_BACKPLANE_CHANNEL", "protogemcouch-events"));
            default:
                return new NoOpEventBackplane();
        }
    }

    private static EventBackplane meshFromEnvironment() {
        int listenPort = parsePort(System.getenv("MESH_PORT"), 40406);
        long refresh = parsePort(System.getenv("MESH_DISCOVERY_INTERVAL_SECONDS"), 10);
        String dns = System.getenv("MESH_PEER_DNS");
        var peerSource = (dns != null && !dns.isBlank())
                ? MeshPeers.dns(dns.trim(), listenPort)
                : MeshPeers.staticList(System.getenv("MESH_PEERS"));
        return new MeshEventBackplane(listenPort, peerSource, refresh);
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int parsePort(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
