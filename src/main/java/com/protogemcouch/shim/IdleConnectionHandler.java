package com.protogemcouch.shim;

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
 */
public class IdleConnectionHandler extends ChannelInboundHandlerAdapter {

    private final IdleConnectionListener listener;

    public IdleConnectionHandler(IdleConnectionListener listener) {
        this.listener = listener == null ? IdleConnectionListener.NO_OP : listener;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            listener.onIdleClose(ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }
}
