package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.query.OqlQuery;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.GemResponseWriter;
import com.protogemcouch.wire.MessageTypes;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles Geode OQL queries (QUERY, opcode 34). First-cut scope: {@code SELECT * FROM /region},
 * returning all of the region's values as a chunked query response. Unsupported queries return a
 * chunked query error (raised client-side as a {@code ServerOperationException}).
 *
 * <p>When pushdown is enabled (the {@code OQL_PUSHDOWN} flag) and the query is a plain AND of
 * string-equality conditions, the backend pre-filters candidate documents (N1QL) instead of the shim
 * scanning the whole region. The candidate set is only ever a superset, and the matcher/projection/sort
 * below run unchanged on it, so results are identical to the scan; anything not eligible, or any backend
 * problem, falls back to the full-region scan.
 */
public class QueryHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(QueryHandler.class);

    private final Repository repository;
    private final OqlQuery.FieldResolver fieldResolver;
    private final boolean pushdownEnabled;

    public QueryHandler(Repository repository, OqlQuery.FieldResolver fieldResolver) {
        this(repository, fieldResolver, false);
    }

    public QueryHandler(Repository repository, OqlQuery.FieldResolver fieldResolver, boolean pushdownEnabled) {
        this.repository = repository;
        // PDX-aware resolver (shared with CQ matching): reads PDX object fields, else map-typed values.
        this.fieldResolver = fieldResolver;
        this.pushdownEnabled = pushdownEnabled;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        int txId = frame.getTransactionId();
        String oql = frame.getParts().isEmpty()
                ? ""
                : ByteUtils.bytesToString(frame.getParts().get(0).getPayload());

        OqlQuery query;
        try {
            // Parameterized query (opcode 80): bind $1..$N from the trailing parts before parsing.
            if (frame.getMessageType() == MessageTypes.QUERY_WITH_PARAMETERS) {
                List<Object> params = decodeBindParameters(frame, txId);
                log.info(StructuredLog.event(
                        "handler_query_parameters", "query", oql, "param_count", params.size(), "txId", txId));
                oql = OqlQuery.bindParameters(oql, params);
            }
            query = OqlQuery.parse(oql);
        } catch (OqlQuery.UnsupportedQueryException e) {
            log.info(StructuredLog.event(
                    "handler_query_unsupported", "query", oql, "reason", e.getMessage(), "txId", txId));
            ctx.writeAndFlush(Unpooled.wrappedBuffer(
                    GemResponseWriter.buildQueryErrorResponse(txId, e.getMessage())));
            return;
        }

        String region = query.regionPath();

        // Candidate source: pushed-down (backend pre-filtered) when eligible, else the full-region scan.
        // Either way the candidates are a superset; query.matches() below is authoritative.
        boolean pushdownUsed = false;
        Collection<StoredValue> candidates = null;
        if (pushdownEnabled) {
            Optional<List<OqlQuery.FieldPredicate>> predicates = query.pushdownPredicates();
            if (predicates.isPresent()) {
                Optional<List<StoredValue>> pushed =
                        repository.queryPushdownByPredicates(region, predicates.get());
                if (pushed.isPresent()) {
                    candidates = pushed.get();
                    pushdownUsed = true;
                }
            }
        }
        if (candidates == null) {
            List<String> keys = repository.keySet(region);
            candidates = repository.getAll(region, keys).values();
        }

        // Filter, then ORDER BY (on the source values), then project.
        List<StoredValue> matched = new ArrayList<>();
        for (StoredValue value : candidates) {
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
            response = GemResponseWriter.buildQueryStructResponse(txId, fieldCount, rows, query.hasOrderBy());
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
                "handler_query_ok", "query", oql, "region", region, "rows", rowCount,
                "pushdown", pushdownUsed, "txId", txId));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
    }

    /**
     * Decode the bind parameters of a QUERY_WITH_PARAMETERS request: part[1] is the int parameter
     * count and part[2..] are the serialized values, decoded with the standard value decoder and
     * mapped to plain Java objects for {@link OqlQuery#bindParameters}.
     */
    private List<Object> decodeBindParameters(GemFrame frame, int txId) {
        List<GemPart> parts = frame.getParts();
        int count = parts.size() > 1 ? ByteUtils.bytesToInt(parts.get(1).getPayload()) : 0;
        List<Object> params = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            int index = 2 + i;
            if (index >= parts.size()) {
                break;
            }
            params.add(paramObject(PutHandler.decodePutValue(parts.get(index).getPayload(), txId)));
        }
        return params;
    }

    /** Map a decoded bind value to a plain Java object for OQL literal rendering. */
    private static Object paramObject(StoredValue value) {
        if (value == null) {
            return null;
        }
        switch (value.type()) {
            case INTEGER: return value.asInteger();
            case LONG: return value.asLong();
            case SHORT: return value.asShort();
            case BYTE: return value.asByte();
            case DOUBLE: return value.asDouble();
            case FLOAT: return value.asFloat();
            case BOOLEAN: return value.asBoolean();
            case CHARACTER: return String.valueOf(value.asCharacter());
            case DATE: return value.asDate();
            case STRING: return value.value();
            default: return value.value();
        }
    }
}
