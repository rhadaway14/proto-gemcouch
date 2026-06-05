package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.query.OqlQuery;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles Geode OQL queries (QUERY, opcode 34). First-cut scope: {@code SELECT * FROM /region},
 * returning all of the region's values as a chunked query response. Unsupported queries return a
 * chunked query error (raised client-side as a {@code ServerOperationException}).
 */
public class QueryHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(QueryHandler.class);

    private final Repository repository;

    public QueryHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        int txId = frame.getTransactionId();
        String oql = frame.getParts().isEmpty()
                ? ""
                : ByteUtils.bytesToString(frame.getParts().get(0).getPayload());

        OqlQuery query;
        try {
            query = OqlQuery.parse(oql);
        } catch (OqlQuery.UnsupportedQueryException e) {
            log.info(StructuredLog.event(
                    "handler_query_unsupported", "query", oql, "reason", e.getMessage(), "txId", txId));
            ctx.writeAndFlush(Unpooled.wrappedBuffer(
                    GemResponseWriter.buildQueryErrorResponse(txId, e.getMessage())));
            return;
        }

        String region = query.regionPath();
        List<String> keys = repository.keySet(region);
        Map<String, StoredValue> all = repository.getAll(region, keys);
        List<StoredValue> values = new ArrayList<>(all.size());
        for (StoredValue value : all.values()) {
            if (value != null) {
                values.add(value);
            }
        }

        log.info(StructuredLog.event(
                "handler_query_ok", "query", oql, "region", region, "rows", values.size(), "txId", txId));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildQueryResponse(txId, values)));
    }
}
