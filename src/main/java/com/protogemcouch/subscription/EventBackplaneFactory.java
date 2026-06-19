package com.protogemcouch.subscription;

/**
 * Selects the cross-replica {@link EventBackplane} from the environment. The default is
 * {@link NoOpEventBackplane} (single-instance, no dependency); set {@code EVENT_BACKPLANE=redis} to
 * enable the opt-in Redis pub/sub transport.
 *
 * <ul>
 *   <li>{@code EVENT_BACKPLANE} — {@code none} (default) or {@code redis}</li>
 *   <li>{@code REDIS_HOST} (default {@code 127.0.0.1}), {@code REDIS_PORT} (default {@code 6379})</li>
 *   <li>{@code EVENT_BACKPLANE_CHANNEL} (default {@code protogemcouch-events})</li>
 * </ul>
 */
public final class EventBackplaneFactory {

    private EventBackplaneFactory() {
    }

    public static EventBackplane fromEnvironment() {
        String mode = System.getenv("EVENT_BACKPLANE");
        if (mode != null && mode.trim().equalsIgnoreCase("redis")) {
            String host = envOr("REDIS_HOST", "127.0.0.1");
            int port = parsePort(System.getenv("REDIS_PORT"), 6379);
            String channel = envOr("EVENT_BACKPLANE_CHANNEL", "protogemcouch-events");
            return new RedisEventBackplane(host, port, channel);
        }
        return new NoOpEventBackplane();
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
