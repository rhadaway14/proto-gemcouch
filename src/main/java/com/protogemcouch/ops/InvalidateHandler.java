package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.subscription.SubscriptionRegistry;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.util.DocumentKeyUtil;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles Geode {@code Region.invalidate(key)} (INVALIDATE, opcode 83). Request parts: region(0),
 * key(1), eventId(2). Invalidation keeps the key present but drops its value. The client expects a
 * REPLY carrying flags + an optional version tag (same shape as the destroy reply), and returns null.
 */
public class InvalidateHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(InvalidateHandler.class);

    private final Repository repository;
    private final SubscriptionRegistry subscriptions;

    public InvalidateHandler(Repository repository, SubscriptionRegistry subscriptions) {
        this.repository = repository;
        this.subscriptions = subscriptions;
    }

    /** Convenience for callers/tests with no subscription feed. */
    public InvalidateHandler(Repository repository) {
        this(repository, new SubscriptionRegistry());
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        int parts = frame.getParts().size();
        String region = parts > 0 ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload()) : "";
        String key = parts > 1 ? ByteUtils.bytesToString(frame.getParts().get(1).getPayload()) : "";
        String docId = DocumentKeyUtil.docId(region, key);

        repository.invalidate(docId);
        subscriptions.publishInvalidate(region, key, SubscriptionRegistry.clientId(ctx));

        log.info(StructuredLog.event(
                "handler_invalidate", "region", region, "key", key, "docId", docId,
                "txId", frame.getTransactionId()));

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildRemoveResponse(frame.getTransactionId())));
    }
}
