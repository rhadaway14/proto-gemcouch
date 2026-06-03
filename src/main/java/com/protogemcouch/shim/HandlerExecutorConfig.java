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
 * Size {@code threads} for the expected number of concurrent in-flight requests.
 *
 * <p>{@code maxPendingTasks} bounds the per-thread work queue. Under sustained backend slowness,
 * requests would otherwise accumulate without limit; once the queue is full, further requests are
 * shed (the connection is closed) rather than letting the backlog grow until the process melts down.
 * A value {@code <= 0} means unbounded.
 */
public final class HandlerExecutorConfig {

    /** Default handler-thread count. */
    public static final int DEFAULT_THREADS = 64;

    /** Default per-thread pending-task bound before requests are shed. */
    public static final int DEFAULT_MAX_PENDING_TASKS = 10_000;

    private final int threads;
    private final int maxPendingTasks; // effective value; Integer.MAX_VALUE means unbounded

    public HandlerExecutorConfig(int threads, int maxPendingTasks) {
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be positive, but was: " + threads);
        }
        this.threads = threads;
        this.maxPendingTasks = maxPendingTasks <= 0 ? Integer.MAX_VALUE : maxPendingTasks;
    }

    public static HandlerExecutorConfig defaults() {
        return new HandlerExecutorConfig(DEFAULT_THREADS, DEFAULT_MAX_PENDING_TASKS);
    }

    /**
     * Build from the {@code HANDLER_THREADS} and {@code HANDLER_MAX_PENDING_TASKS} environment
     * variables, falling back to the defaults for an unset, blank, or non-numeric value.
     */
    public static HandlerExecutorConfig fromEnv() {
        return new HandlerExecutorConfig(
                parsePositiveOrDefault(System.getenv("HANDLER_THREADS"), DEFAULT_THREADS),
                parseMaxPendingOrDefault(System.getenv("HANDLER_MAX_PENDING_TASKS"), DEFAULT_MAX_PENDING_TASKS));
    }

    public int threads() {
        return threads;
    }

    /** Effective per-thread pending-task bound ({@link Integer#MAX_VALUE} when unbounded). */
    public int maxPendingTasks() {
        return maxPendingTasks;
    }

    public boolean queueBounded() {
        return maxPendingTasks != Integer.MAX_VALUE;
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

    /** Like {@link #parsePositiveOrDefault} but a parsed value {@code <= 0} means "unbounded". */
    static int parseMaxPendingOrDefault(String rawValue, int defaultValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            return parsed <= 0 ? Integer.MAX_VALUE : parsed;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public String toString() {
        return "HandlerExecutorConfig{threads=" + threads
                + ", maxPendingTasks=" + (queueBounded() ? maxPendingTasks : "unbounded") + '}';
    }
}
