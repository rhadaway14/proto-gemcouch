package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.query.OqlQuery;
import com.protogemcouch.subscription.SubscriptionRegistry;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles EXECUTECQ (42) and EXECUTECQ_WITH_IR (43): part[0] is the CQ name, part[1] is the OQL query.
 * The shim compiles the query with its own {@link OqlQuery} and registers the CQ for the client;
 * subsequent mutations matching the predicate push a CQ event to the client's feed. Replies with the
 * server's "cq created successfully." ack. (Initial-results delivery for _WITH_IR is a later phase.)
 */
public class ExecuteCqHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecuteCqHandler.class);

    private final SubscriptionRegistry subscriptions;

    public ExecuteCqHandler(SubscriptionRegistry subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String cqName = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload()) : "";
        String queryText = frame.getParts().size() > 1
                ? ByteUtils.bytesToString(frame.getParts().get(1).getPayload()) : "";

        try {
            OqlQuery query = OqlQuery.parse(queryText);
            subscriptions.registerCq(SubscriptionRegistry.clientId(ctx), cqName, query);
            log.info(StructuredLog.event(
                    "handler_execute_cq", "cq", cqName, "query", queryText,
                    "region", query.regionPath(), "txId", frame.getTransactionId()));
        } catch (OqlQuery.UnsupportedQueryException e) {
            // The CQ is acknowledged but will not match (unsupported predicate); a clean degradation.
            log.warn(StructuredLog.event(
                    "handler_execute_cq_unsupported", "cq", cqName, "query", queryText,
                    "reason", e.getMessage(), "txId", frame.getTransactionId()));
        }

        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildExecuteCqReply()));
    }
}
