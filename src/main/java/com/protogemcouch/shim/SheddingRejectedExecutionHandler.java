package com.protogemcouch.shim;

import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.SingleThreadEventExecutor;

import java.util.concurrent.RejectedExecutionException;

/**
 * Rejection policy for the handler executor's bounded queue: when the queue is full, record the shed
 * via the supplied callback and reject the task. Rejecting (rather than silently dropping) surfaces
 * as a pipeline exception that closes the offending connection, so an overloaded shim fails fast and
 * predictably instead of accumulating an unbounded backlog.
 */
public final class SheddingRejectedExecutionHandler implements RejectedExecutionHandler {

    private final Runnable onShed;

    public SheddingRejectedExecutionHandler(Runnable onShed) {
        this.onShed = onShed == null ? () -> { } : onShed;
    }

    @Override
    public void rejected(Runnable task, SingleThreadEventExecutor executor) {
        onShed.run();
        throw new RejectedExecutionException("Handler queue full; request shed under load");
    }
}
