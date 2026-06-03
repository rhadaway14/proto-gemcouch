package com.protogemcouch.shim;

import io.netty.channel.ChannelHandlerContext;

/**
 * Decides how the shim responds to a client when an operation fails after the request frame was
 * fully and validly decoded (i.e. a backend/handler failure, not a malformed frame).
 *
 * <p>This is the single seam through which all post-decode operation failures flow, so the
 * client-facing behavior on failure can evolve in one place without touching dispatch logic.
 * Metric recording and structured logging happen in the dispatch loop before this is invoked; the
 * policy is responsible only for the client-facing response.
 */
public interface ErrorResponsePolicy {

    /**
     * @param ctx    the channel context for the failed request
     * @param opcode the request opcode
     * @param txId   the request transaction id, echoed into any error response
     * @param cause  the failure
     */
    void onFailure(ChannelHandlerContext ctx, int opcode, int txId, Throwable cause);
}
