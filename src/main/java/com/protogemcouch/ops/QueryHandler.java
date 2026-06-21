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
        int limit = query.limit();
        // LIMIT may be pushed to the backend only when there is no ORDER BY (a sorted top-N needs the
        // full matched set first). The loose-predicate case is then guarded by a refetch below.
        boolean limitPushed = query.hasLimit() && limit > 0 && !query.hasOrderBy();

        boolean pushdownUsed = false;
        Collection<StoredValue> candidates = null;
        // The predicate list to push: the eligible subset of the WHERE, or an empty list to push just a
        // region-scoped LIMIT when there is no WHERE (so the backend caps rows instead of a full scan).
        List<OqlQuery.FieldPredicate> pushPreds = null;
        if (pushdownEnabled) {
            Optional<List<OqlQuery.FieldPredicate>> eligible = query.pushdownPredicates();
            if (eligible.isPresent()) {
                pushPreds = eligible.get();
            } else if (limitPushed && !query.hasWhere()) {
                pushPreds = List.of(); // no WHERE + pushable LIMIT -> region-scoped LIMIT
            }
        }
        if (pushPreds != null) {
            Optional<List<StoredValue>> pushed = repository.queryPushdownByPredicates(
                    region, pushPreds, limitPushed ? limit : 0);
            if (pushed.isPresent()) {
                candidates = pushed.get();
                pushdownUsed = true;
            }
        }
        if (candidates == null) {
            candidates = repository.getAll(region, repository.keySet(region)).values();
        }

        List<StoredValue> matched = matchesOf(candidates, query);

        // LIMIT-pushdown correctness guard: if the backend capped the page (returned a full `limit`
        // candidates) but the matcher rejected some, true matches beyond the cap may have been dropped —
        // refetch the full candidate set (unbounded pushdown, else scan) so we never under-return.
        if (limitPushed && pushdownUsed && matched.size() < limit && candidates.size() >= limit) {
            Optional<List<StoredValue>> refetched =
                    repository.queryPushdownByPredicates(region, pushPreds, 0);
            if (refetched.isPresent()) {
                candidates = refetched.get();
            } else {
                candidates = repository.getAll(region, repository.keySet(region)).values();
                pushdownUsed = false;
            }
            matched = matchesOf(candidates, query);
        }

        // ORDER BY (on the source values), then apply LIMIT to the result rows.
        query.sort(matched, fieldResolver);
        if (query.hasLimit() && matched.size() > limit) {
            matched = new ArrayList<>(matched.subList(0, limit));
        }

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
                "pushdown", pushdownUsed, "limit", query.hasLimit() ? limit : -1, "txId", txId));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
    }

    /** Authoritatively filter a candidate set to the values that satisfy the query's WHERE clause. */
    private List<StoredValue> matchesOf(Collection<StoredValue> candidates, OqlQuery query) {
        List<StoredValue> matched = new ArrayList<>();
        for (StoredValue value : candidates) {
            if (value != null && query.matches(value, fieldResolver)) {
                matched.add(value);
            }
        }
        return matched;
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
