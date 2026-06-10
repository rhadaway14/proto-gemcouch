package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.subscription.SubscriptionRegistry;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles STOPCQ (44) and CLOSECQ (45): deregisters the named CQ (part[0]) for the client so it stops
 * receiving its events, and replies with a REPLY ack. Stop and close are treated the same in this
 * first cut.
 */
public class CloseCqHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(CloseCqHandler.class);

    private final SubscriptionRegistry subscriptions;

    public CloseCqHandler(SubscriptionRegistry subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String cqName = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload()) : "";

        subscriptions.closeCq(SubscriptionRegistry.clientId(ctx), cqName);
        log.info(StructuredLog.event(
                "handler_close_cq", "cq", cqName, "txId", frame.getTransactionId()));

        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildCqAck(frame.getTransactionId())));
    }
}
