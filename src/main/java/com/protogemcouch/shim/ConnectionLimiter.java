package com.protogemcouch.shim;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the number of active client connections and enforces an optional maximum.
 *
 * <p>A connection calls {@link #tryAcquire()} when it becomes active and {@link #release()} when it
 * closes, but only if it was successfully acquired. With a max of {@code 0}, connections are never
 * rejected (the count is still tracked, which is useful for observability).
 */
public final class ConnectionLimiter {

    private final int maxConnections;
    private final AtomicInteger active = new AtomicInteger();

    public ConnectionLimiter(int maxConnections) {
        if (maxConnections < 0) {
            throw new IllegalArgumentException("maxConnections must not be negative, but was: " + maxConnections);
        }
        this.maxConnections = maxConnections;
    }

    /**
     * Attempt to admit a new connection.
     *
     * @return {@code true} if admitted (caller must later call {@link #release()}); {@code false} if
     *         the connection would exceed the configured maximum (caller must NOT call release).
     */
    public boolean tryAcquire() {
        int now = active.incrementAndGet();
        if (maxConnections > 0 && now > maxConnections) {
            active.decrementAndGet();
            return false;
        }
        return true;
    }

    /** Release a previously acquired connection slot. */
    public void release() {
        active.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    public int activeConnections() {
        return active.get();
    }

    public int maxConnections() {
        return maxConnections;
    }
}
