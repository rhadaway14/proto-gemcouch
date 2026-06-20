package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.subscription.SubscriptionRegistry;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles CLIENT_READY (53) — a durable client's {@code readyForEvents()}. Replays the client's queued
 * events down its (re)connected feed and resumes live delivery, then acks. For a non-durable client (no
 * durable id) it is a harmless ack.
 */
public class ClientReadyHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(ClientReadyHandler.class);

    private final SubscriptionRegistry subscriptions;

    public ClientReadyHandler(SubscriptionRegistry subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String clientId = SubscriptionRegistry.clientId(ctx);
        subscriptions.onClientReady(clientId);
        log.info(StructuredLog.event("handler_client_ready", "clientId", clientId,
                "txId", frame.getTransactionId()));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildSimpleAck(frame.getTransactionId())));
    }
}
