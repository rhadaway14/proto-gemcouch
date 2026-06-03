package com.protogemcouch.shim;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Tracks the connection lifecycle and enforces the max-connections cap.
 *
 * <p>This must be the first handler in the pipeline and must stay there for the connection's whole
 * lifetime, so that {@link #channelInactive} always fires and the {@link ConnectionLimiter} slot is
 * released and the close counted. (The handshake handler removes itself after the handshake, so
 * connection accounting cannot live there — doing so leaks limiter slots and miscounts closes.)
 *
 * <p>On {@code channelActive} it acquires a slot; if the cap is exceeded the connection is rejected
 * and closed without propagating activation downstream. Otherwise it counts the open and forwards
 * the event so the rest of the pipeline (idle/first-request timers, handshake) initializes.
 */
public class ConnectionTrackingHandler extends ChannelInboundHandlerAdapter {

    private final ConnectionLimiter limiter;
    private final ConnectionTrackingListener listener;

    private boolean acquired;

    public ConnectionTrackingHandler(ConnectionLimiter limiter, ConnectionTrackingListener listener) {
        this.limiter = limiter;
        this.listener = listener == null ? ConnectionTrackingListener.NO_OP : listener;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (!limiter.tryAcquire()) {
            listener.onConnectionRejected(ctx.channel().remoteAddress(), limiter.maxConnections());
            ctx.close();
            return; // do not propagate activation: rejected connections are not handshaked
        }

        acquired = true;
        listener.onConnectionOpened(ctx.channel().remoteAddress(), limiter.activeConnections());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (acquired) {
            acquired = false;
            limiter.release();
            listener.onConnectionClosed(ctx.channel().remoteAddress());
        }
        super.channelInactive(ctx);
    }
}
