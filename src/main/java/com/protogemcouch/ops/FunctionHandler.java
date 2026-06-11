package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gracefully rejects server-side function execution. The shim cannot run user {@code Function} code
 * (it has none of the user's classes), so rather than crashing or hanging a client it replies to the
 * function opcodes — GET_FUNCTION_ATTRIBUTES (91), EXECUTE_FUNCTION (62), EXECUTE_REGION_FUNCTION (59)
 * and its single-hop variant (79) — with a REQUESTDATAERROR carrying a "function is not registered"
 * message, exactly as a real server does for an unregistered function id. The client raises a clean
 * {@code ServerOperationException}.
 *
 * <p>{@code FunctionService.onServer(..)/onRegion(..).execute(id)} probes attributes (opcode 91,
 * part[0] = function id) before sending EXECUTE_FUNCTION, so handling 91 makes {@code execute()} fail
 * fast; the execute opcodes are handled too for clients that skip the probe.
 */
public class FunctionHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(FunctionHandler.class);

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String functionId = extractFunctionId(frame);
        String message = "The function is not registered for function id " + functionId;

        log.info(StructuredLog.event(
                "handler_function_rejected",
                "opcode", frame.getMessageType(),
                "functionId", functionId,
                "txId", frame.getTransactionId()));

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildFunctionErrorReply(frame.getTransactionId(), message)));
    }

    /**
     * Best-effort function id for the error message: the first raw (non-serialized) part that decodes
     * to a printable string. For GET_FUNCTION_ATTRIBUTES that is part[0]; the execute opcodes carry it
     * among their parts. Falls back to {@code "<unknown>"} when no part is a plain string.
     */
    private static String extractFunctionId(GemFrame frame) {
        for (GemPart part : frame.getParts()) {
            if (part.getTypeCode() != 0) {
                continue;
            }
            byte[] payload = part.getPayload();
            if (payload == null || payload.length == 0) {
                continue;
            }
            String text = ByteUtils.bytesToString(payload);
            if (isPrintable(text)) {
                return text;
            }
        }
        return "<unknown>";
    }

    private static boolean isPrintable(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return !text.isEmpty();
    }
}
