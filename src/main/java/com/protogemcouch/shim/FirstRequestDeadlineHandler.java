package com.protogemcouch.shim;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.concurrent.TimeUnit;

/**
 * Closes a connection that does not complete its handshake and first request within a deadline.
 *
 * <p>This complements {@link IdleConnectionHandler}: the idle reaper resets on any read or write, so
 * a slowloris-style client that trickles bytes keeps resetting it and is never reaped. This deadline
 * is a one-shot timer started at connection open and cancelled only when the first request is fully
 * decoded ({@link FirstRequestCompletedEvent}); trickled bytes do not reset it, so a connection that
 * never completes a real request is closed regardless.
 *
 * <p>Must be in the pipeline at {@code channelActive} (i.e. added at connection setup), upstream of
 * the frame decoder, so the deadline starts when the connection opens. It removes itself once the
 * first request completes.
 */
public class FirstRequestDeadlineHandler extends ChannelInboundHandlerAdapter {

    private final long timeoutMillis;
    private final FirstRequestTimeoutListener listener;

    private ScheduledFuture<?> deadlineFuture;
    private boolean firstRequestSeen;

    public FirstRequestDeadlineHandler(long timeoutMillis, FirstRequestTimeoutListener listener) {
        this.timeoutMillis = timeoutMillis;
        this.listener = listener == null ? FirstRequestTimeoutListener.NO_OP : listener;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        scheduleDeadline(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        // If the channel is already active when this handler is added, start the deadline now too.
        if (ctx.channel().isActive() && deadlineFuture == null) {
            scheduleDeadline(ctx);
        }
    }

    private void scheduleDeadline(ChannelHandlerContext ctx) {
        if (deadlineFuture != null) {
            return;
        }
        deadlineFuture = ctx.executor().schedule(
                () -> enforceFirstRequestDeadline(ctx), timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == FirstRequestCompletedEvent.INSTANCE) {
            firstRequestSeen = true;
            cancelDeadline();
            // Job done: this handler is no longer needed for the lifetime of the connection.
            if (ctx.pipeline().context(this) != null) {
                ctx.pipeline().remove(this);
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelDeadline();
        super.channelInactive(ctx);
    }

    /**
     * Invoked when the deadline elapses. Closes the connection if it has not yet completed a first
     * request. Package-private so the decision logic can be unit-tested without relying on scheduler
     * timing.
     */
    void enforceFirstRequestDeadline(ChannelHandlerContext ctx) {
        if (firstRequestSeen || !ctx.channel().isActive()) {
            return;
        }
        listener.onFirstRequestTimeout(ctx.channel().remoteAddress());
        ctx.close();
    }

    private void cancelDeadline() {
        if (deadlineFuture != null) {
            deadlineFuture.cancel(false);
            deadlineFuture = null;
        }
    }
}
