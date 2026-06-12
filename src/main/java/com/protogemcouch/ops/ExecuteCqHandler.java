package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.query.OqlQuery;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.subscription.SubscriptionRegistry;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import com.protogemcouch.wire.MessageTypes;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles EXECUTECQ (42) and EXECUTECQ_WITH_IR (43): part[0] is the CQ name, part[1] is the OQL query.
 * The shim compiles the query with its own {@link OqlQuery} and registers the CQ for the client;
 * subsequent mutations matching the predicate push a CQ event to the client's feed.
 *
 * <p>For plain EXECUTECQ the reply is the server's "cq created successfully." ack. For
 * EXECUTECQ_WITH_IR ({@code executeWithInitialResults()}) the reply is, additionally, the current
 * matching entries as a query-style chunked struct response of {@code {key, value}} — the client op
 * extends {@code QueryOpImpl}, so it parses the reply exactly like a query result.
 */
public class ExecuteCqHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecuteCqHandler.class);

    private final SubscriptionRegistry subscriptions;
    private final Repository repository;
    private final OqlQuery.FieldResolver fieldResolver;

    public ExecuteCqHandler(SubscriptionRegistry subscriptions, Repository repository,
                            OqlQuery.FieldResolver fieldResolver) {
        this.subscriptions = subscriptions;
        this.repository = repository;
        this.fieldResolver = fieldResolver;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String cqName = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload()) : "";
        String queryText = frame.getParts().size() > 1
                ? ByteUtils.bytesToString(frame.getParts().get(1).getPayload()) : "";
        boolean withInitialResults = frame.getMessageType() == MessageTypes.EXECUTECQ_WITH_IR;

        OqlQuery query = null;
        try {
            query = OqlQuery.parse(queryText);
            subscriptions.registerCq(SubscriptionRegistry.clientId(ctx), cqName, query);
            log.info(StructuredLog.event(
                    "handler_execute_cq", "cq", cqName, "query", queryText, "withInitialResults",
                    withInitialResults, "region", query.regionPath(), "txId", frame.getTransactionId()));
        } catch (OqlQuery.UnsupportedQueryException e) {
            // The CQ is acknowledged but will not match (unsupported predicate); a clean degradation.
            log.warn(StructuredLog.event(
                    "handler_execute_cq_unsupported", "cq", cqName, "query", queryText,
                    "reason", e.getMessage(), "txId", frame.getTransactionId()));
        }

        byte[] reply;
        if (withInitialResults) {
            List<Map.Entry<String, StoredValue>> initial = query == null ? List.of() : initialResults(query);
            reply = GemResponseWriter.buildExecuteCqWithIrReply(frame.getTransactionId(), initial);
        } else {
            reply = GemResponseWriter.buildExecuteCqReply();
        }
        ctx.writeAndFlush(Unpooled.wrappedBuffer(reply));
    }

    /** Snapshot the region's current entries that match the CQ predicate (the initial result set). */
    private List<Map.Entry<String, StoredValue>> initialResults(OqlQuery query) {
        String region = query.regionPath();
        List<String> keys = repository.keySet(region);
        Map<String, StoredValue> all = repository.getAll(region, keys);
        List<Map.Entry<String, StoredValue>> matches = new ArrayList<>();
        for (Map.Entry<String, StoredValue> entry : all.entrySet()) {
            StoredValue value = entry.getValue();
            if (value != null && query.matches(value, fieldResolver)) {
                matches.add(Map.entry(entry.getKey(), value));
            }
        }
        return matches;
    }
}
