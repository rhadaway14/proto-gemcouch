package com.protogemcouch.shim;

/**
 * Sizing for the dedicated executor group that runs request handlers off the Netty event loop.
 *
 * <p>Handlers make blocking Couchbase calls. Running them directly on the I/O event loop means one
 * slow backend call stalls every other connection sharing that loop (head-of-line blocking). By
 * running handlers on a separate bounded thread pool, the event loops stay responsive — they keep
 * accepting connections and serving fast operations while slow backend calls occupy handler
 * threads instead.
 *
 * <p>Each channel is pinned to a single handler thread, so requests on one connection stay ordered.
 * Size the pool for the expected number of concurrent in-flight requests.
 */
public final class HandlerExecutorConfig {

    /** Default handler-thread count. */
    public static final int DEFAULT_THREADS = 64;

    private final int threads;

    public HandlerExecutorConfig(int threads) {
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be positive, but was: " + threads);
        }
        this.threads = threads;
    }

    public static HandlerExecutorConfig defaults() {
        return new HandlerExecutorConfig(DEFAULT_THREADS);
    }

    /**
     * Build from the {@code HANDLER_THREADS} environment variable, falling back to the default for
     * an unset, blank, non-numeric, or non-positive value.
     */
    public static HandlerExecutorConfig fromEnv() {
        return new HandlerExecutorConfig(parsePositiveOrDefault(System.getenv("HANDLER_THREADS"), DEFAULT_THREADS));
    }

    public int threads() {
        return threads;
    }

    static int parsePositiveOrDefault(String rawValue, int defaultValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public String toString() {
        return "HandlerExecutorConfig{threads=" + threads + '}';
    }
}
