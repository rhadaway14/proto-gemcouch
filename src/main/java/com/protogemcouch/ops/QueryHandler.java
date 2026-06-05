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
    private final OqlQuery.FieldResolver fieldResolver;

    public QueryHandler(Repository repository, PdxTypeRegistry pdxTypeRegistry) {
        this.repository = repository;
        // Resolve query fields on PDX instances via the kept PdxType; fall back to map-typed values.
        this.fieldResolver = (value, field) -> {
            if (value.type() == StoredValue.Type.PDX_INSTANCE) {
                Object resolved = PdxFieldAccessor.read(value.asPdxInstanceValue(), pdxTypeRegistry, field);
                return resolved == null ? OqlQuery.ABSENT : resolved;
            }
            return OqlQuery.MAP_RESOLVER.resolve(value, field);
        };
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

        // Filter, then ORDER BY (on the source values), then project.
        List<StoredValue> matched = new ArrayList<>();
        for (StoredValue value : all.values()) {
            if (value != null && query.matches(value, fieldResolver)) {
                matched.add(value);
            }
        }
        query.sort(matched, fieldResolver);

        int fieldCount = query.projectionFieldCount();
        int rowCount = matched.size();
        byte[] response;
        if (fieldCount > 1) {
            // Multi-field (struct) projection: each row is a list of field values.
            List<List<StoredValue>> rows = new ArrayList<>(matched.size());
            for (StoredValue value : matched) {
                rows.add(query.projectRow(value, fieldResolver));
            }
            response = GemResponseWriter.buildQueryStructResponse(txId, fieldCount, rows);
        } else {
            // SELECT * or single-field projection: a flat list of values. ORDER BY uses the
            // order-preserving "Ordered" response; otherwise the standard result list.
            List<StoredValue> values = new ArrayList<>(matched.size());
            for (StoredValue value : matched) {
                values.add(query.projectRow(value, fieldResolver).get(0));
            }
            response = query.hasOrderBy()
                    ? GemResponseWriter.buildOrderedQueryResponse(txId, values)
                    : GemResponseWriter.buildQueryResponse(txId, values);
        }

        log.info(StructuredLog.event(
                "handler_query_ok", "query", oql, "region", region, "rows", rowCount, "txId", txId));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
    }
}
