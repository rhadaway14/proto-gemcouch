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
 *   <li>{@code firstRequestTimeoutSeconds}: a connection must complete its handshake and first
 *       request within this long or it is closed. Unlike the idle timeout, this deadline is not
 *       reset by trickled bytes, so it bounds slowloris-style connections that stay technically
 *       "active" without ever completing a request. {@code 0} disables it.</li>
 * </ul>
 */
public final class ConnectionLimits {

    /** Default idle timeout (seconds) before an inactive connection is reaped. */
    public static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 300;

    /** Default max connections ({@code 0} = unlimited). */
    public static final int DEFAULT_MAX_CONNECTIONS = 0;

    /** Default deadline (seconds) for a connection to complete its handshake and first request. */
    public static final int DEFAULT_FIRST_REQUEST_TIMEOUT_SECONDS = 10;

    private final int idleTimeoutSeconds;
    private final int maxConnections;
    private final int firstRequestTimeoutSeconds;

    public ConnectionLimits(int idleTimeoutSeconds, int maxConnections, int firstRequestTimeoutSeconds) {
        if (idleTimeoutSeconds < 0) {
            throw new IllegalArgumentException("idleTimeoutSeconds must not be negative, but was: " + idleTimeoutSeconds);
        }
        if (maxConnections < 0) {
            throw new IllegalArgumentException("maxConnections must not be negative, but was: " + maxConnections);
        }
        if (firstRequestTimeoutSeconds < 0) {
            throw new IllegalArgumentException(
                    "firstRequestTimeoutSeconds must not be negative, but was: " + firstRequestTimeoutSeconds);
        }
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.maxConnections = maxConnections;
        this.firstRequestTimeoutSeconds = firstRequestTimeoutSeconds;
    }

    public static ConnectionLimits defaults() {
        return new ConnectionLimits(
                DEFAULT_IDLE_TIMEOUT_SECONDS, DEFAULT_MAX_CONNECTIONS, DEFAULT_FIRST_REQUEST_TIMEOUT_SECONDS);
    }

    /**
     * Build from {@code CONNECTION_IDLE_TIMEOUT_SECONDS}, {@code MAX_CONNECTIONS}, and
     * {@code FIRST_REQUEST_TIMEOUT_SECONDS}, falling back to defaults for unset, blank, non-numeric,
     * or negative values.
     */
    public static ConnectionLimits fromEnv() {
        return new ConnectionLimits(
                parseNonNegativeOrDefault(System.getenv("CONNECTION_IDLE_TIMEOUT_SECONDS"), DEFAULT_IDLE_TIMEOUT_SECONDS),
                parseNonNegativeOrDefault(System.getenv("MAX_CONNECTIONS"), DEFAULT_MAX_CONNECTIONS),
                parseNonNegativeOrDefault(System.getenv("FIRST_REQUEST_TIMEOUT_SECONDS"), DEFAULT_FIRST_REQUEST_TIMEOUT_SECONDS)
        );
    }

    public int idleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public int maxConnections() {
        return maxConnections;
    }

    public int firstRequestTimeoutSeconds() {
        return firstRequestTimeoutSeconds;
    }

    public boolean idleReapingEnabled() {
        return idleTimeoutSeconds > 0;
    }

    public boolean firstRequestDeadlineEnabled() {
        return firstRequestTimeoutSeconds > 0;
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
                + ", maxConnections=" + maxConnections
                + ", firstRequestTimeoutSeconds=" + firstRequestTimeoutSeconds + '}';
    }
}
