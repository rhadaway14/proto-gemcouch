package com.protogemcouch.shim;

import com.protogemcouch.observability.OperationNames;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Graceful error-response policy: reply with a Geode EXCEPTION frame and keep the connection open,
 * so the client receives a structured server error and can continue using the connection (the
 * request frame was fully decoded, so the stream is still frame-aligned).
 *
 * <p>This is opt-in (via configuration) and not yet the default, because the EXCEPTION wire shape
 * must be validated against a live Geode client (robustness Phase 6). If building or writing the
 * error frame fails for any reason, this policy falls back to closing the connection so the failure
 * path can never itself fail open.
 */
public final class ExceptionFrameErrorPolicy implements ErrorResponsePolicy {

    private static final Logger log = LoggerFactory.getLogger(ExceptionFrameErrorPolicy.class);

    @Override
    public void onFailure(ChannelHandlerContext ctx, int opcode, int txId, Throwable cause) {
        try {
            byte[] response = GemResponseWriter.buildExceptionResponse(txId, safeMessage(cause));
            ctx.writeAndFlush(Unpooled.wrappedBuffer(response));

            log.debug(StructuredLog.event(
                    "error_response_sent",
                    "opcode", opcode,
                    "operation", OperationNames.nameOf(opcode),
                    "txId", txId
            ));
        } catch (Throwable t) {
            // The error path must never fail open: if we cannot send a structured error, close.
            log.warn(StructuredLog.event(
                    "error_response_send_failed",
                    "opcode", opcode,
                    "operation", OperationNames.nameOf(opcode),
                    "txId", txId,
                    "error", t.getMessage()
            ), t);
            ctx.close();
        }
    }

    private static String safeMessage(Throwable cause) {
        if (cause == null) {
            return "ProtoGemCouch operation failed";
        }
        String type = cause.getClass().getSimpleName();
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? type : type + ": " + message;
    }
}
