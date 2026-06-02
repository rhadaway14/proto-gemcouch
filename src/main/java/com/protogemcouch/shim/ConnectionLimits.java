package com.protogemcouch.shim;

/**
 * Connection lifecycle guards that protect the shim from resource exhaustion by misbehaving,
 * dead, or excessive client connections.
 *
 * <ul>
 *   <li>{@code idleTimeoutSeconds}: close a connection that has had no read or write activity for
 *       this long (reaps dead peers and leaked/idle connections). {@code 0} disables reaping.</li>
 *   <li>{@code maxConnections}: maximum concurrent client connections; new connections beyond this
 *       are rejected and closed. {@code 0} means unlimited.</li>
 * </ul>
 */
public final class ConnectionLimits {

    /** Default idle timeout (seconds) before an inactive connection is reaped. */
    public static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 300;

    /** Default max connections ({@code 0} = unlimited). */
    public static final int DEFAULT_MAX_CONNECTIONS = 0;

    private final int idleTimeoutSeconds;
    private final int maxConnections;

    public ConnectionLimits(int idleTimeoutSeconds, int maxConnections) {
        if (idleTimeoutSeconds < 0) {
            throw new IllegalArgumentException("idleTimeoutSeconds must not be negative, but was: " + idleTimeoutSeconds);
        }
        if (maxConnections < 0) {
            throw new IllegalArgumentException("maxConnections must not be negative, but was: " + maxConnections);
        }
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.maxConnections = maxConnections;
    }

    public static ConnectionLimits defaults() {
        return new ConnectionLimits(DEFAULT_IDLE_TIMEOUT_SECONDS, DEFAULT_MAX_CONNECTIONS);
    }

    /**
     * Build from {@code CONNECTION_IDLE_TIMEOUT_SECONDS} and {@code MAX_CONNECTIONS}, falling back
     * to defaults for unset, blank, non-numeric, or negative values.
     */
    public static ConnectionLimits fromEnv() {
        return new ConnectionLimits(
                parseNonNegativeOrDefault(System.getenv("CONNECTION_IDLE_TIMEOUT_SECONDS"), DEFAULT_IDLE_TIMEOUT_SECONDS),
                parseNonNegativeOrDefault(System.getenv("MAX_CONNECTIONS"), DEFAULT_MAX_CONNECTIONS)
        );
    }

    public int idleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public int maxConnections() {
        return maxConnections;
    }

    public boolean idleReapingEnabled() {
        return idleTimeoutSeconds > 0;
    }

    static int parseNonNegativeOrDefault(String rawValue, int defaultValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            return parsed >= 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public String toString() {
        return "ConnectionLimits{idleTimeoutSeconds=" + idleTimeoutSeconds
                + ", maxConnections=" + maxConnections + '}';
    }
}
