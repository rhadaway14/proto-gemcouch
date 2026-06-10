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
 * Handles UNREGISTER_INTEREST (22) and UNREGISTER_INTEREST_LIST (25): removes the client's interest in
 * the region (part[0]) so its feed stops receiving that region's events, and replies with a REPLY ack.
 */
public class UnregisterInterestHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(UnregisterInterestHandler.class);

    private final SubscriptionRegistry subscriptions;

    public UnregisterInterestHandler(SubscriptionRegistry subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().isEmpty()
                ? ""
                : ByteUtils.bytesToString(frame.getParts().get(0).getPayload());

        subscriptions.unregisterInterest(SubscriptionRegistry.clientId(ctx), region);

        log.info(StructuredLog.event(
                "handler_unregister_interest", "region", region, "txId", frame.getTransactionId()));

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildUnregisterInterestReply(frame.getTransactionId())));
    }
}
