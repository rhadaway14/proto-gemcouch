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
 * Handles REGISTER_INTEREST (20) and REGISTER_INTEREST_LIST (24) on the subscription-control
 * connection. P1 first cut: records interest in the region (part[0]) so the event feed knows to push
 * its mutations, and replies with the NONE-policy chunked ack (no initial image). The
 * InterestResultPolicy KEYS / KEYS_VALUES initial image (GII) is a later phase.
 */
public class RegisterInterestHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(RegisterInterestHandler.class);

    private final SubscriptionRegistry subscriptions;

    public RegisterInterestHandler(SubscriptionRegistry subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().isEmpty()
                ? ""
                : ByteUtils.bytesToString(frame.getParts().get(0).getPayload());

        subscriptions.registerInterest(region);

        log.info(StructuredLog.event(
                "handler_register_interest",
                "region", region,
                "parts", frame.getNumberOfParts(),
                "feeds", subscriptions.feedCount(),
                "txId", frame.getTransactionId()));

        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildRegisterInterestReply()));
    }
}
