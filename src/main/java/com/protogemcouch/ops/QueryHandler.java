package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.MetricsRegistry;
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
    private final MetricsRegistry metrics;

    public QueryHandler(Repository repository, OqlQuery.FieldResolver fieldResolver) {
        this(repository, fieldResolver, false, null);
    }

    public QueryHandler(Repository repository, OqlQuery.FieldResolver fieldResolver, boolean pushdownEnabled) {
        this(repository, fieldResolver, pushdownEnabled, null);
    }

    public QueryHandler(Repository repository, OqlQuery.FieldResolver fieldResolver,
                        boolean pushdownEnabled, MetricsRegistry metrics) {
        this.repository = repository;
        // PDX-aware resolver (shared with CQ matching): reads PDX object fields, else map-typed values.
        this.fieldResolver = fieldResolver;
        this.pushdownEnabled = pushdownEnabled;
        this.metrics = metrics;
    }

    /** Record an OQL query's pushdown outcome (no-op when no metrics registry is wired). */
    private void recordPushdown(boolean pushed, String fallbackReason) {
        if (metrics != null) {
            metrics.recordPushdownQuery(pushed, fallbackReason);
        }
    }

    /**
     * The fallback reason for a query that did not push: {@code "disabled"} when pushdown is off,
     * {@code "backend_unavailable"} when an eligible pushdown form existed but the backend returned
     * nothing (no index / error), else {@code "ineligible"}.
     */
    private String fallbackReason(OqlQuery query) {
        if (!pushdownEnabled) {
            return "disabled";
        }
        boolean anyEligible = query.pushdownPredicates().isPresent()
                || query.pushdownOrGroups().isPresent()
                || (query.hasOrderBy() && query.pushdownOrderBy().isPresent())
                || (query.isDistinct() && query.pushdownDistinct().isPresent())
                || (query.hasLimit() && !query.hasWhere());
        return anyEligible ? "backend_unavailable" : "ineligible";
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

        if (query.isGroupBy()) {
            handleGroupBy(ctx, oql, query, region, txId);
            return;
        }

        if (query.isAggregate()) {
            handleAggregate(ctx, oql, query, region, txId);
            return;
        }

        // DISTINCT pushdown (1.6.0-M3): push SELECT DISTINCT <field> [WHERE] to N1QL so the backend
        // dedups (single top-level field; absent or fully-eligible WHERE; map-typed → exact). ORDER BY /
        // LIMIT keep the in-shim path. The backend-deduped values are run through the in-shim dedup too,
        // so values sharing a string form merge exactly as the scan would.
        if (pushdownEnabled && query.isDistinct() && !query.hasOrderBy() && !query.hasLimit()
                && query.projectionFieldCount() == 1) {
            Optional<List<OqlQuery.FieldPredicate>> distinctPreds = query.pushdownDistinct();
            if (distinctPreds.isPresent()) {
                String field = query.projectionFieldNames().get(0);
                Optional<List<StoredValue>> pushed =
                        repository.distinctPushdown(region, field, distinctPreds.get());
                if (pushed.isPresent()) {
                    List<List<StoredValue>> rows = new ArrayList<>(pushed.get().size());
                    for (StoredValue value : pushed.get()) {
                        rows.add(List.of(value));
                    }
                    List<List<StoredValue>> distinctRows = OqlQuery.deduplicateRows(rows);
                    byte[] response = GemResponseWriter.buildDistinctQueryResponse(
                            txId, 1, query.projectionFieldNames(), distinctRows);
                    recordPushdown(true, null);
                    log.info(StructuredLog.event(
                            "handler_query_ok", "query", oql, "region", region, "rows", distinctRows.size(),
                            "pushdown", true, "distinct", true, "txId", txId));
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
                    return;
                }
            }
        }

        // Candidate source: pushed-down (backend pre-filtered) when eligible, else the full-region scan.
        // Either way the candidates are a superset; query.matches() below is authoritative.
        int limit = query.limit();

        // ORDER BY pushdown (headline): when the ORDER BY keys are pushdown-eligible (single top-level
        // fields), push WHERE + ORDER BY (+ LIMIT) so the backend sorts server-side — a true server-side
        // top-N. The backend row order is then authoritative and the in-shim sort is skipped (the matcher
        // still re-filters, preserving the surviving rows' relative order).
        boolean alreadySorted = false;
        boolean pushdownUsed = false;
        List<StoredValue> matched = null;
        if (pushdownEnabled && query.hasOrderBy()) {
            Optional<List<StoredValue>> ordered = tryOrderedPushdown(query, region, limit);
            if (ordered.isPresent()) {
                matched = ordered.get();
                alreadySorted = true;
                pushdownUsed = true;
            }
        }

        if (matched == null) {
            // LIMIT may be pushed to the backend only when there is no ORDER BY (a sorted top-N needs the
            // full matched set first). The loose-predicate case is then guarded by a refetch below.
            boolean limitPushed = query.hasLimit() && limit > 0 && !query.hasOrderBy();

            Collection<StoredValue> candidates = null;
            // The predicate list to push: the eligible subset of the WHERE, or an empty list to push just
            // a region-scoped LIMIT when there is no WHERE (so the backend caps rows instead of scanning).
            List<OqlQuery.FieldPredicate> pushPreds = null;
            List<List<OqlQuery.FieldPredicate>> pushOrGroups = null;
            if (pushdownEnabled) {
                Optional<List<OqlQuery.FieldPredicate>> eligible = query.pushdownPredicates();
                if (eligible.isPresent()) {
                    pushPreds = eligible.get();
                } else {
                    Optional<List<List<OqlQuery.FieldPredicate>>> orEligible = query.pushdownOrGroups();
                    if (orEligible.isPresent()) {
                        pushOrGroups = orEligible.get();
                    } else if (limitPushed && !query.hasWhere()) {
                        pushPreds = List.of(); // no WHERE + pushable LIMIT -> region-scoped LIMIT
                    }
                }
            }
            if (pushPreds != null) {
                Optional<List<StoredValue>> pushed = repository.queryPushdownByPredicates(
                        region, pushPreds, limitPushed ? limit : 0);
                if (pushed.isPresent()) {
                    candidates = pushed.get();
                    pushdownUsed = true;
                }
            } else if (pushOrGroups != null) {
                Optional<List<StoredValue>> pushed = repository.queryPushdownByOrGroups(
                        region, pushOrGroups, limitPushed ? limit : 0);
                if (pushed.isPresent()) {
                    candidates = pushed.get();
                    pushdownUsed = true;
                }
            }
            if (candidates == null) {
                candidates = repository.getAll(region, repository.keySet(region)).values();
            }

            matched = matchesOf(candidates, query);

            // LIMIT-pushdown correctness guard: if the backend capped the page (returned a full `limit`
            // candidates) but the matcher rejected some, true matches beyond the cap may have been dropped
            // — refetch the full candidate set (unbounded pushdown, else scan) so we never under-return.
            if (limitPushed && pushdownUsed && matched.size() < limit && candidates.size() >= limit) {
                Optional<List<StoredValue>> refetched = pushOrGroups != null
                        ? repository.queryPushdownByOrGroups(region, pushOrGroups, 0)
                        : repository.queryPushdownByPredicates(region, pushPreds, 0);
                if (refetched.isPresent()) {
                    candidates = refetched.get();
                } else {
                    candidates = repository.getAll(region, repository.keySet(region)).values();
                    pushdownUsed = false;
                }
                matched = matchesOf(candidates, query);
            }
        }

        // ORDER BY (on the source values) in-shim, unless the backend already sorted; then LIMIT the rows.
        if (!alreadySorted) {
            query.sort(matched, fieldResolver);
        }
        if (query.hasLimit() && matched.size() > limit) {
            matched = new ArrayList<>(matched.subList(0, limit));
        }

        int fieldCount = query.projectionFieldCount();
        int rowCount = matched.size();
        byte[] response;
        if (query.isDistinct() && fieldCount >= 1) {
            // DISTINCT: project all rows, deduplicate, then encode with the Set/StructSet CollectionType.
            List<List<StoredValue>> allRows = new ArrayList<>(matched.size());
            for (StoredValue value : matched) {
                allRows.add(query.projectRow(value, fieldResolver));
            }
            List<List<StoredValue>> distinctRows = OqlQuery.deduplicateRows(allRows);
            response = GemResponseWriter.buildDistinctQueryResponse(
                    txId, fieldCount, query.projectionFieldNames(), distinctRows);
        } else if (fieldCount > 1) {
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

        recordPushdown(pushdownUsed, pushdownUsed ? null : fallbackReason(query));
        log.info(StructuredLog.event(
                "handler_query_ok", "query", oql, "region", region, "rows", rowCount,
                "pushdown", pushdownUsed, "limit", query.hasLimit() ? limit : -1, "txId", txId));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
    }

    /**
     * Handle an aggregate query (COUNT/SUM/MIN/MAX/AVG): try aggregate pushdown first when eligible,
     * then fall back to fetch-candidates + in-shim compute.
     */
    private void handleAggregate(ChannelHandlerContext ctx, String oql, OqlQuery query,
                                 String region, int txId) {
        boolean isCount = query.aggregateFunction() == OqlQuery.AggregateFunction.COUNT_STAR
                || query.aggregateFunction() == OqlQuery.AggregateFunction.COUNT_FIELD;

        // Aggregate pushdown: try when WHERE is a single AND-group with eligible predicates.
        // The backend computes the scalar directly; fall back to in-shim scan otherwise.
        if (pushdownEnabled) {
            Optional<List<OqlQuery.FieldPredicate>> eligible = query.pushdownPredicates();
            if (eligible.isPresent()) {
                Optional<Number> pushed = repository.aggregatePushdown(
                        region, query.aggregateFunction(), query.aggregateFieldPath(), eligible.get());
                if (pushed.isPresent()) {
                    Object result = pushed.get();
                    recordPushdown(true, null);
                    log.info(StructuredLog.event(
                            "handler_aggregate_ok", "query", oql, "region", region,
                            "fn", query.aggregateFunction(), "rows", -1,
                            "result", result, "pushdown", true, "txId", txId));
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(
                            GemResponseWriter.buildAggregateQueryResponse(txId, result, isCount)));
                    return;
                }
            }
        }

        // Fallback: fetch candidates (via predicate/OR pushdown when eligible, else full scan),
        // filter in-shim, compute the aggregate in-shim.
        Collection<StoredValue> candidates = null;
        boolean pushdownUsed = false;
        if (pushdownEnabled) {
            Optional<List<OqlQuery.FieldPredicate>> eligible = query.pushdownPredicates();
            if (eligible.isPresent()) {
                Optional<List<StoredValue>> pushed =
                        repository.queryPushdownByPredicates(region, eligible.get(), 0);
                if (pushed.isPresent()) { candidates = pushed.get(); pushdownUsed = true; }
            } else {
                Optional<List<List<OqlQuery.FieldPredicate>>> orEligible = query.pushdownOrGroups();
                if (orEligible.isPresent()) {
                    Optional<List<StoredValue>> pushed =
                            repository.queryPushdownByOrGroups(region, orEligible.get(), 0);
                    if (pushed.isPresent()) { candidates = pushed.get(); pushdownUsed = true; }
                }
            }
        }
        if (candidates == null) {
            candidates = repository.getAll(region, repository.keySet(region)).values();
        }

        List<StoredValue> matched = matchesOf(candidates, query);
        Object result = query.computeAggregateRaw(matched, fieldResolver);

        // Aggregate fell back to the in-shim compute (the candidate fetch may still have pushed): the
        // aggregate itself was not pushed, so classify it as a fallback with the appropriate reason.
        recordPushdown(false, !pushdownEnabled ? "disabled"
                : (query.pushdownPredicates().isPresent() ? "backend_unavailable" : "ineligible"));
        log.info(StructuredLog.event(
                "handler_aggregate_ok", "query", oql, "region", region,
                "fn", query.aggregateFunction(), "rows", matched.size(),
                "result", result, "pushdown", pushdownUsed, "txId", txId));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildAggregateQueryResponse(txId, result, isCount)));
    }

    /**
     * Handle a GROUP BY query: try GROUP BY pushdown to N1QL first when eligible, then fall back to
     * fetch-candidates + in-shim grouping with the StructBag wire shape.
     */
    private void handleGroupBy(ChannelHandlerContext ctx, String oql, OqlQuery query,
                               String region, int txId) {
        // GROUP BY pushdown: push the whole group-and-aggregate to N1QL when eligible (single key,
        // single top-level field, single-AND-group or absent WHERE, map-typed region).
        if (pushdownEnabled) {
            Optional<List<OqlQuery.FieldPredicate>> gbEligible = query.pushdownGroupBy();
            if (gbEligible.isPresent()) {
                Optional<List<List<Object>>> pushed = repository.groupByPushdown(
                        region, query.groupByColumns(), gbEligible.get());
                if (pushed.isPresent()) {
                    List<List<Object>> rows = pushed.get();
                    recordPushdown(true, null);
                    log.info(StructuredLog.event(
                            "handler_groupby_ok", "query", oql, "region", region,
                            "rows", -1, "groups", rows.size(),
                            "pushdown", true, "txId", txId));
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(
                            GemResponseWriter.buildGroupByQueryResponse(txId, query.groupByColumns(), rows)));
                    return;
                }
            }
        }

        // Fallback: fetch candidates (predicate or OR pushdown when eligible, else full scan),
        // filter in-shim, then group-and-aggregate in-shim.
        Collection<StoredValue> candidates = null;
        boolean pushdownUsed = false;
        if (pushdownEnabled) {
            Optional<List<OqlQuery.FieldPredicate>> eligible = query.pushdownPredicates();
            if (eligible.isPresent()) {
                Optional<List<StoredValue>> pushed =
                        repository.queryPushdownByPredicates(region, eligible.get(), 0);
                if (pushed.isPresent()) { candidates = pushed.get(); pushdownUsed = true; }
            } else {
                Optional<List<List<OqlQuery.FieldPredicate>>> orEligible = query.pushdownOrGroups();
                if (orEligible.isPresent()) {
                    Optional<List<StoredValue>> pushed =
                            repository.queryPushdownByOrGroups(region, orEligible.get(), 0);
                    if (pushed.isPresent()) { candidates = pushed.get(); pushdownUsed = true; }
                }
            }
        }
        if (candidates == null) {
            candidates = repository.getAll(region, repository.keySet(region)).values();
        }

        List<StoredValue> matched = matchesOf(candidates, query);
        List<List<Object>> rows = query.computeGroupBy(matched, fieldResolver);

        // GROUP BY fell back to in-shim grouping (the candidate fetch may still have pushed): classify the
        // query as a fallback with the appropriate reason (eligible-but-backend-empty vs ineligible).
        recordPushdown(false, !pushdownEnabled ? "disabled"
                : (query.pushdownGroupBy().isPresent() ? "backend_unavailable" : "ineligible"));
        log.info(StructuredLog.event(
                "handler_groupby_ok", "query", oql, "region", region,
                "rows", matched.size(), "groups", rows.size(),
                "pushdown", pushdownUsed, "txId", txId));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildGroupByQueryResponse(txId, query.groupByColumns(), rows)));
    }

    /**
     * Try to push WHERE + ORDER BY (+ LIMIT) to the backend for a server-side sort / top-N. Returns the
     * matched (filtered, backend-ordered) values when ordering was pushed, or {@link Optional#empty()} to
     * fall back to the unordered path + in-shim sort.
     *
     * <p>Eligibility: the ORDER BY keys are all single top-level fields, and either the WHERE is
     * pushdown-eligible (predicate or OR-group) or there is no WHERE at all (a region-scoped ordered
     * top-N). A WHERE that exists but cannot be pushed is declined here (the unordered scan path handles
     * it), because pushing an unfiltered ORDER BY over the whole region buys nothing.
     *
     * <p>Because the pushed candidates are a superset, a backend-capped page can lose true matches to
     * false positives; the same top-N guard as the unordered path refetches unbounded (still
     * backend-ordered) when a full page yields fewer than {@code limit} matches, so we never under-return.
     */
    private Optional<List<StoredValue>> tryOrderedPushdown(OqlQuery query, String region, int limit) {
        Optional<List<OqlQuery.OrderByKey>> orderEligible = query.pushdownOrderBy();
        if (orderEligible.isEmpty()) {
            return Optional.empty();
        }
        List<OqlQuery.OrderByKey> order = orderEligible.get();
        int pushLimit = (query.hasLimit() && limit > 0) ? limit : 0;

        List<OqlQuery.FieldPredicate> preds = null;
        List<List<OqlQuery.FieldPredicate>> orGroups = null;
        Optional<List<OqlQuery.FieldPredicate>> eligible = query.pushdownPredicates();
        if (eligible.isPresent()) {
            preds = eligible.get();
        } else {
            Optional<List<List<OqlQuery.FieldPredicate>>> orEligible = query.pushdownOrGroups();
            if (orEligible.isPresent()) {
                orGroups = orEligible.get();
            } else if (!query.hasWhere()) {
                preds = List.of(); // no WHERE: a region-scoped ordered (top-N) query
            } else {
                return Optional.empty(); // WHERE present but unpushable: leave it to the scan path
            }
        }

        Optional<List<StoredValue>> pushed = (orGroups != null)
                ? repository.queryPushdownByOrGroups(region, orGroups, order, pushLimit)
                : repository.queryPushdownByPredicates(region, preds, order, pushLimit);
        if (pushed.isEmpty()) {
            return Optional.empty();
        }

        List<StoredValue> candidates = pushed.get();
        List<StoredValue> matched = matchesOf(candidates, query); // preserves the backend row order

        if (pushLimit > 0 && matched.size() < limit && candidates.size() >= limit) {
            Optional<List<StoredValue>> refetched = (orGroups != null)
                    ? repository.queryPushdownByOrGroups(region, orGroups, order, 0)
                    : repository.queryPushdownByPredicates(region, preds, order, 0);
            if (refetched.isEmpty()) {
                return Optional.empty(); // fall back to the full-region scan + in-shim sort
            }
            matched = matchesOf(refetched.get(), query);
        }
        return Optional.of(matched);
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
