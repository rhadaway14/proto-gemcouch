package com.protogemcouch.shim;

import com.protogemcouch.subscription.SubscriptionRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * Closes a connection when an upstream {@link io.netty.handler.timeout.IdleStateHandler} reports it
 * has been idle past the configured timeout. This reaps dead peers and leaked/idle connections so
 * they do not hold resources indefinitely.
 *
 * <p>It stays in the pipeline for the lifetime of the connection (unlike the handshake handler,
 * which removes itself), so idle events are handled both before and after the handshake.
 *
 * <p><b>Subscription feeds are exempt</b> (keepalive): a server&rarr;client feed is long-lived and is
 * legitimately idle while waiting for events, so reaping it would silently break a subscription or a
 * connected durable client. Dead feeds are instead detected at the TCP layer.
 */
public class IdleConnectionHandler extends ChannelInboundHandlerAdapter {

    private final IdleConnectionListener listener;

    public IdleConnectionHandler(IdleConnectionListener listener) {
        this.listener = listener == null ? IdleConnectionListener.NO_OP : listener;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            if (Boolean.TRUE.equals(ctx.channel().attr(SubscriptionRegistry.IS_FEED).get())) {
                return; // a subscription/durable feed: keep it alive while it waits for events
            }
            listener.onIdleClose(ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }
}
