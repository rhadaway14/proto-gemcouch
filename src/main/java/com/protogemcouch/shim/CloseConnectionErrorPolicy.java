package com.protogemcouch.shim;

import io.netty.channel.ChannelHandlerContext;

/**
 * Default error-response policy: close the connection on failure.
 *
 * <p>This preserves the shim's long-standing behavior. It is safe and unambiguous, but the client
 * sees a dropped socket rather than a structured error. The opt-in {@link ExceptionFrameErrorPolicy}
 * is the more graceful alternative, pending validation against a live Geode client.
 */
public final class CloseConnectionErrorPolicy implements ErrorResponsePolicy {

    @Override
    public void onFailure(ChannelHandlerContext ctx, int opcode, int txId, Throwable cause) {
        ctx.close();
    }
}
