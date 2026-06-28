package com.protogemcouch.query;

import com.protogemcouch.serialization.StoredValue;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed OQL query.
 *
 * <p>Supported subset:
 * {@code SELECT (* | <field> | <aggregate>(<field>|*) | <key>, <agg>(<field>|*)) FROM /region [alias]
 * [WHERE <conditions>] [GROUP BY <keys>] [ORDER BY <keys>] [LIMIT n]}, where conditions are
 * {@code <field> <op> <literal>} (ops {@code = <> != < <= > >=}; literals are quoted strings, numbers,
 * booleans, or {@code null}) combined with {@code AND}/{@code OR} (AND binds tighter; no parentheses).
 * Aggregate queries ({@link #isAggregate()}) compute a scalar over the WHERE-filtered set.
 * GROUP BY queries ({@link #isGroupBy()}) group the WHERE-filtered set by key columns and compute
 * per-group aggregates; {@code ORDER BY} and {@code HAVING} are not supported with GROUP BY.
 * Anything else is reported as unsupported.
 */
public final class OqlQuery {

    /**
     * Aggregate functions supported in {@code SELECT <fn>(<arg>) FROM ...}.
     * {@link #COUNT_STAR} uses {@code *} as the argument; all others name a field.
     */
    public enum AggregateFunction {
        COUNT_STAR, COUNT_FIELD, SUM, MIN, MAX, AVG
    }

    /** Matches {@code COUNT(*)} / {@code COUNT(field)} / {@code SUM(f)} etc. at the SELECT position. */
    private static final Pattern AGGREGATE = Pattern.compile(
            "(?i)^\\s*(COUNT|SUM|MIN|MAX|AVG)\\s*\\(\\s*(\\*|[A-Za-z_][A-Za-z0-9_.]*)\\s*\\)\\s*$");

    /** Splits {@code GROUP BY <keys>} from the end of the OQL string (after LIMIT is stripped). */
    private static final Pattern GROUP_BY_SPLIT = Pattern.compile(
            "(?is)^(.*?)\\s+GROUP\\s+BY\\s+(.+?)\\s*(;?)\\s*$");

    private static final Pattern QUERY = Pattern.compile(
            "(?is)^\\s*SELECT\\s+(.+?)\\s+FROM\\s+(/[A-Za-z0-9_./-]+|[A-Za-z0-9_./-]+)"
                    + "(?:\\s+(?!WHERE\\b)(?!ORDER\\b)([A-Za-z_][A-Za-z0-9_]*))?"  // optional alias (captured)
                    + "(?:\\s+WHERE\\s+(.+?))?"
                    + "(?:\\s+ORDER\\s+BY\\s+(.+?))?"
                    + "\\s*;?\\s*$");

    private static final Pattern CONDITION = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_.\\[\\]]*)\\s*(<=|>=|<>|!=|=|<|>)\\s*(.+?)\\s*$");

    /** Containment: {@code <literal> IN <path>} (the array/collection on the right). */
    private static final Pattern IN_CONDITION = Pattern.compile(
            "(?i)^\\s*(.+?)\\s+IN\\s+([A-Za-z_][A-Za-z0-9_.\\[\\]]*)\\s*$");

    /** One dotted path segment: a field name with zero or more {@code [index]} suffixes. */
    private static final Pattern SEGMENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)((?:\\[[0-9]+\\])*)");

    /** A projection field: a dotted path of name segments, each optionally carrying {@code [n]} indices. */
    private static final String PROJECTION_FIELD =
            "[A-Za-z_][A-Za-z0-9_]*(?:\\[[0-9]+\\])*(?:\\.[A-Za-z_][A-Za-z0-9_]*(?:\\[[0-9]+\\])*)*";

    /** A single {@code [index]} within a segment's bracket suffixes. */
    private static final Pattern INDEX = Pattern.compile("\\[([0-9]+)\\]");

    // A trailing `LIMIT <n>` is split off before the main grammar runs (the closing token before `;`/end
    // can only be a literal count, so it can't be confused with a string literal ending in a quote).
    private static final Pattern LIMIT_TAIL = Pattern.compile("(?is)^(.*?)\\s+LIMIT\\s+(\\d+)\\s*(;?)\\s*$");

    /**
     * One column in a GROUP BY SELECT list: either a group key (fn=null) or an aggregate column.
     * For COUNT(*)/COUNT(field), {@code columnName} is the positional ordinal string {@code "0"} (Geode
     * convention). For SUM/AVG/MIN/MAX, {@code columnName} is the simple field name.
     */
    public record GroupByColumn(
            String columnName,
            AggregateFunction fn,
            List<String> fieldPath) {
        public boolean isGroupKey() {
            return fn == null;
        }

        public boolean isCount() {
            return fn == AggregateFunction.COUNT_STAR || fn == AggregateFunction.COUNT_FIELD;
        }
    }

    private final String regionPath;
    private final List<List<String>> projectionFields; // empty = SELECT *; each entry is a field path
    private final List<List<Condition>> orGroups;      // OR of AND-groups; empty = match all
    private final List<OrderKey> orderBy;              // empty = no ordering
    private final int limit;                           // -1 = no LIMIT; >= 0 = explicit row cap
    private final AggregateFunction aggregateFunction; // null = not an aggregate query
    private final List<String> aggregateField;         // field path for COUNT(f)/SUM/MIN/MAX/AVG; null for COUNT(*)
    private final List<GroupByColumn> groupByColumns;  // null = not a GROUP BY query
    private final boolean distinct;                    // true when SELECT DISTINCT is used

    private OqlQuery(String regionPath, List<List<String>> projectionFields,
                    List<List<Condition>> orGroups, List<OrderKey> orderBy, int limit,
                    AggregateFunction aggregateFunction, List<String> aggregateField,
                    List<GroupByColumn> groupByColumns, boolean distinct) {
        this.regionPath = regionPath;
        this.projectionFields = projectionFields;
        this.orGroups = orGroups;
        this.orderBy = orderBy;
        this.limit = limit;
        this.aggregateFunction = aggregateFunction;
        this.aggregateField = aggregateField;
        this.groupByColumns = groupByColumns;
        this.distinct = distinct;
    }

    /** Whether the query has a {@code LIMIT} clause. */
    public boolean hasLimit() {
        return limit >= 0;
    }

    /** The row cap from {@code LIMIT} (defined when {@link #hasLimit()}); {@code -1} otherwise. */
    public int limit() {
        return limit;
    }

    public String regionPath() {
        return regionPath;
    }

    /** Number of projected fields: 0 for {@code SELECT *}, 1 for a single field, N for a struct. */
    public int projectionFieldCount() {
        return projectionFields.size();
    }

    /**
     * The display names of the projected fields in order — the last path segment of each projection
     * field (e.g. {@code e.address.zip} → {@code "zip"}). Used by the DISTINCT struct CollectionType
     * to embed the actual field names rather than {@code field$0}, {@code field$1}.
     */
    public List<String> projectionFieldNames() {
        List<String> names = new ArrayList<>(projectionFields.size());
        for (List<String> path : projectionFields) {
            names.add(path.get(path.size() - 1));
        }
        return names;
    }

    /** Whether the query has an ORDER BY (so the response must preserve row order). */
    public boolean hasOrderBy() {
        return !orderBy.isEmpty();
    }

    /** The comparison of a pushdown-eligible scalar predicate. */
    public enum PushdownOp {
        EQ("="), LT("<"), LTE("<="), GT(">"), GTE(">=");

        private final String symbol;

        PushdownOp(String symbol) {
            this.symbol = symbol;
        }

        /** The SQL/N1QL comparison symbol (e.g. {@code "<="}). */
        public String symbol() {
            return symbol;
        }
    }

    /**
     * A pushdown-eligible scalar predicate on a top-level field: either a string equality
     * ({@code numeric == false}, {@code op == EQ}, compare {@link #text}) or a numeric comparison
     * ({@code numeric == true}, compare {@link #number}). The backend translates these to a region-scoped
     * N1QL predicate that is a <em>superset</em> of the matches (the caller's matcher re-filters).
     */
    public record FieldPredicate(String field, PushdownOp op, boolean numeric, String text, double number) {
        static FieldPredicate stringEquality(String field, String text) {
            return new FieldPredicate(field, PushdownOp.EQ, false, text, 0d);
        }

        static FieldPredicate numericComparison(String field, PushdownOp op, double number, String text) {
            return new FieldPredicate(field, op, true, text, number);
        }
    }

    /** Whether the query has a WHERE clause (an empty WHERE matches every value). */
    public boolean hasWhere() {
        return !orGroups.isEmpty();
    }

    /** True when the SELECT list is an aggregate function (COUNT/SUM/MIN/MAX/AVG). */
    public boolean isAggregate() {
        return aggregateFunction != null;
    }

    /** The aggregate function (defined when {@link #isAggregate()}). */
    public AggregateFunction aggregateFunction() {
        return aggregateFunction;
    }

    /**
     * The field path for the aggregate (defined for all aggregate functions except {@link
     * AggregateFunction#COUNT_STAR}; {@code null} for COUNT(*)). A single-element path is a top-level
     * field (the only case eligible for pushdown).
     */
    public List<String> aggregateFieldPath() {
        return aggregateField;
    }

    /** True when the query has a GROUP BY clause. */
    public boolean isGroupBy() {
        return groupByColumns != null;
    }

    /** True when the SELECT list had the DISTINCT keyword. */
    public boolean isDistinct() {
        return distinct;
    }

    /**
     * Deduplicate a list of projected rows for a DISTINCT query.
     *
     * <p>For single-field projections each row is a one-element list; the dedup key is the string
     * representation of the projected value. For multi-field (struct) projections the key is the
     * concatenation of all field values. First-seen row wins (preserves the order Couchbase returns).
     *
     * @param rows projected rows from {@link #projectRow} — each inner list has one element per
     *             SELECT field
     * @return a new list containing only the first occurrence of each distinct row
     */
    public static List<List<StoredValue>> deduplicateRows(List<List<StoredValue>> rows) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        List<List<StoredValue>> result = new ArrayList<>();
        for (List<StoredValue> row : rows) {
            String key = rowKey(row);
            if (seen.add(key)) {
                result.add(row);
            }
        }
        return result;
    }

    private static String rowKey(List<StoredValue> row) {
        if (row.size() == 1) {
            String v = row.get(0).value();
            return v == null ? "null" : v;
        }
        StringBuilder sb = new StringBuilder();
        for (StoredValue sv : row) {
            String v = sv.value();
            sb.append(v == null ? "null" : v).append('');
        }
        return sb.toString();
    }

    /** The GROUP BY SELECT columns in order (group keys first, then aggregate column); defined when {@link #isGroupBy()}. */
    public List<GroupByColumn> groupByColumns() {
        return groupByColumns;
    }

    /**
     * When this is a GROUP BY query and is eligible for a full GROUP BY pushdown to N1QL, returns the
     * eligible WHERE predicates (possibly empty when there is no WHERE). Returns {@link Optional#empty()}
     * when the GROUP BY is ineligible (multiple group keys, nested field path, multi-segment aggregate
     * field, OR WHERE, or not a GROUP BY query at all).
     *
     * <p>Eligibility: exactly one group key column with a single top-level field; the aggregate column
     * has a single top-level field (or is COUNT_STAR); the WHERE is a single AND-group or absent.
     */
    public Optional<List<FieldPredicate>> pushdownGroupBy() {
        if (!isGroupBy()) {
            return Optional.empty();
        }
        // Count group keys and check the key field path.
        List<GroupByColumn> keys = new ArrayList<>();
        GroupByColumn aggCol = null;
        for (GroupByColumn col : groupByColumns) {
            if (col.isGroupKey()) {
                keys.add(col);
            } else {
                aggCol = col;
            }
        }
        if (keys.size() != 1) {
            return Optional.empty(); // multi-key GROUP BY: not yet supported
        }
        GroupByColumn keyCol = keys.get(0);
        if (keyCol.fieldPath() == null || keyCol.fieldPath().size() != 1) {
            return Optional.empty();
        }
        if (aggCol != null && aggCol.fn() != AggregateFunction.COUNT_STAR) {
            if (aggCol.fieldPath() == null || aggCol.fieldPath().size() != 1) {
                return Optional.empty();
            }
        }
        // WHERE must be single-AND-group or absent.
        if (hasWhere()) {
            return pushdownPredicates(); // reuse single-AND-group eligibility
        }
        return Optional.of(List.of()); // no WHERE → empty predicate list
    }

    /**
     * Compute the aggregate over an already-WHERE-filtered list of values.
     *
     * <ul>
     *   <li>{@code COUNT(*)} — count of matched rows (always returns a non-null {@code Integer}).</li>
     *   <li>{@code COUNT(field)} — count of non-null resolved field values ({@code Integer}).</li>
     *   <li>{@code SUM(field)} — sum of numeric field values ({@code Double}; {@code 0.0} for empty).</li>
     *   <li>{@code AVG(field)} — mean of numeric field values ({@code Double}; {@code null} for empty).</li>
     *   <li>{@code MIN(field)} / {@code MAX(field)} — min/max over {@code Comparable} field values;
     *       {@code null} for an empty or all-absent set.</li>
     * </ul>
     *
     * @return the aggregate result, or {@code null} when undefined (empty set for AVG/MIN/MAX)
     */
    public Number computeAggregate(List<StoredValue> matched, FieldResolver resolver) {
        if (aggregateFunction == null) {
            throw new IllegalStateException("not an aggregate query");
        }
        switch (aggregateFunction) {
            case COUNT_STAR:
                return matched.size();
            case COUNT_FIELD: {
                int count = 0;
                for (StoredValue v : matched) {
                    Object field = resolver.resolve(v, aggregateField);
                    if (field != ABSENT && field != null) {
                        count++;
                    }
                }
                return count;
            }
            case SUM: {
                double sum = 0.0;
                for (StoredValue v : matched) {
                    Double n = resolveNumeric(v, aggregateField, resolver);
                    if (n != null) {
                        sum += n;
                    }
                }
                return sum;
            }
            case AVG: {
                double sum = 0.0;
                int count = 0;
                for (StoredValue v : matched) {
                    Double n = resolveNumeric(v, aggregateField, resolver);
                    if (n != null) {
                        sum += n;
                        count++;
                    }
                }
                return count == 0 ? null : sum / count;
            }
            case MIN: {
                Comparable<?> min = null;
                for (StoredValue v : matched) {
                    Object field = resolver.resolve(v, aggregateField);
                    if (field != ABSENT && field != null && field instanceof Comparable) {
                        @SuppressWarnings("unchecked")
                        Comparable<Object> c = (Comparable<Object>) field;
                        if (min == null || c.compareTo(min) < 0) {
                            min = c;
                        }
                    }
                }
                return min instanceof Number ? (Number) min : (min != null ? 0 : null);
            }
            case MAX: {
                Comparable<?> max = null;
                for (StoredValue v : matched) {
                    Object field = resolver.resolve(v, aggregateField);
                    if (field != ABSENT && field != null && field instanceof Comparable) {
                        @SuppressWarnings("unchecked")
                        Comparable<Object> c = (Comparable<Object>) field;
                        if (max == null || c.compareTo(max) > 0) {
                            max = c;
                        }
                    }
                }
                return max instanceof Number ? (Number) max : (max != null ? 0 : null);
            }
            default:
                throw new IllegalStateException("unknown aggregate: " + aggregateFunction);
        }
    }

    /**
     * Returns the raw resolved field value for MIN/MAX (may be any {@link Comparable}, not just
     * {@link Number}), or {@code null} when the field is absent or not comparable.
     */
    public Object computeAggregateRaw(List<StoredValue> matched, FieldResolver resolver) {
        if (aggregateFunction != AggregateFunction.MIN && aggregateFunction != AggregateFunction.MAX) {
            return computeAggregate(matched, resolver);
        }
        Comparable<?> result = null;
        boolean isMin = aggregateFunction == AggregateFunction.MIN;
        for (StoredValue v : matched) {
            Object field = resolver.resolve(v, aggregateField);
            if (field != ABSENT && field != null && field instanceof Comparable) {
                @SuppressWarnings("unchecked")
                Comparable<Object> c = (Comparable<Object>) field;
                if (result == null || (isMin ? c.compareTo(result) < 0 : c.compareTo(result) > 0)) {
                    result = c;
                }
            }
        }
        return result;
    }

    /**
     * Group the WHERE-filtered rows and compute per-group aggregates.
     *
     * <p>Returns a list of result rows (one per group), each row containing the group key values
     * followed by the aggregate value(s), in SELECT list order. Preserves insertion order (first
     * occurrence of each group key). Groups with all-integer source values return integer aggregates
     * (SUM → Integer/Long, AVG → Integer/Long truncated); mixed or floating-point inputs return Double.
     */
    public List<List<Object>> computeGroupBy(List<StoredValue> matched, FieldResolver resolver) {
        if (!isGroupBy()) {
            throw new IllegalStateException("not a GROUP BY query");
        }
        LinkedHashMap<List<Object>, List<StoredValue>> groups = new LinkedHashMap<>();
        for (StoredValue v : matched) {
            List<Object> key = new ArrayList<>();
            for (GroupByColumn col : groupByColumns) {
                if (col.isGroupKey()) {
                    Object val = resolver.resolve(v, col.fieldPath());
                    key.add(val == ABSENT ? null : val);
                }
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }
        List<List<Object>> result = new ArrayList<>();
        int keyIndex = 0;
        for (Map.Entry<List<Object>, List<StoredValue>> entry : groups.entrySet()) {
            List<Object> row = new ArrayList<>();
            int ki = 0;
            for (GroupByColumn col : groupByColumns) {
                if (col.isGroupKey()) {
                    row.add(entry.getKey().get(ki++));
                } else {
                    row.add(computeGroupByAgg(entry.getValue(), col, resolver));
                }
            }
            result.add(row);
            keyIndex++;
        }
        return result;
    }

    private static Object computeGroupByAgg(List<StoredValue> rows, GroupByColumn col, FieldResolver resolver) {
        switch (col.fn()) {
            case COUNT_STAR:
                return rows.size();
            case COUNT_FIELD: {
                int count = 0;
                for (StoredValue v : rows) {
                    Object f = resolver.resolve(v, col.fieldPath());
                    if (f != ABSENT && f != null) count++;
                }
                return count;
            }
            case SUM: {
                boolean allIntegral = true;
                long lsum = 0;
                double dsum = 0.0;
                int count = 0;
                for (StoredValue v : rows) {
                    Object f = resolver.resolve(v, col.fieldPath());
                    if (f == ABSENT || f == null || !(f instanceof Number)) continue;
                    Number n = (Number) f;
                    if (!(f instanceof Integer) && !(f instanceof Long)
                            && !(f instanceof Short) && !(f instanceof Byte)) {
                        allIntegral = false;
                    }
                    lsum += n.longValue();
                    dsum += n.doubleValue();
                    count++;
                }
                if (count == 0) return 0;
                if (allIntegral) {
                    return (lsum <= Integer.MAX_VALUE && lsum >= Integer.MIN_VALUE) ? (int) lsum : lsum;
                }
                return dsum;
            }
            case AVG: {
                boolean allIntegral = true;
                long lsum = 0;
                double dsum = 0.0;
                int count = 0;
                for (StoredValue v : rows) {
                    Object f = resolver.resolve(v, col.fieldPath());
                    if (f == ABSENT || f == null || !(f instanceof Number)) continue;
                    Number n = (Number) f;
                    if (!(f instanceof Integer) && !(f instanceof Long)
                            && !(f instanceof Short) && !(f instanceof Byte)) {
                        allIntegral = false;
                    }
                    lsum += n.longValue();
                    dsum += n.doubleValue();
                    count++;
                }
                if (count == 0) return null;
                if (allIntegral) {
                    long avg = lsum / count;
                    return (avg <= Integer.MAX_VALUE && avg >= Integer.MIN_VALUE) ? (int) avg : avg;
                }
                return dsum / count;
            }
            case MIN:
            case MAX: {
                Comparable<?> result = null;
                boolean isMin = col.fn() == AggregateFunction.MIN;
                for (StoredValue v : rows) {
                    Object f = resolver.resolve(v, col.fieldPath());
                    if (f == ABSENT || f == null || !(f instanceof Comparable)) continue;
                    @SuppressWarnings("unchecked")
                    Comparable<Object> c = (Comparable<Object>) f;
                    if (result == null || (isMin ? c.compareTo(result) < 0 : c.compareTo(result) > 0)) {
                        result = c;
                    }
                }
                return result;
            }
            default:
                throw new IllegalStateException("unknown aggregate: " + col.fn());
        }
    }

    /** Resolve a field to a double for numeric aggregates; null if absent or non-numeric. */
    private static Double resolveNumeric(StoredValue v, List<String> path, FieldResolver resolver) {
        Object field = resolver.resolve(v, path);
        if (field == ABSENT || field == null) {
            return null;
        }
        if (field instanceof Number) {
            return ((Number) field).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(field));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * For a single AND-group WHERE, return the <em>eligible subset</em> of its conditions for backend
     * pre-filtering — string equality ({@code field = 'string'}) and numeric comparison
     * ({@code field = <num>} / {@code < <= > >=} a numeric literal) on simple top-level fields. Ineligible
     * conditions in the group (OR is handled separately; also {@code <>}/{@code !=}, string ranges,
     * boolean/null literals, dotted/nested fields) are <strong>skipped</strong>, not fatal: pushing a
     * subset of an AND is still a superset, and the caller always re-applies the full
     * {@link #matches}/{@link #projectRow}/{@link #sort} to the candidates, so the matcher stays
     * authoritative and the result is identical to a scan. Returns empty for an OR query, no WHERE, or
     * when no condition in the group is eligible (then the caller scans).
     */
    public Optional<List<FieldPredicate>> pushdownPredicates() {
        if (orGroups.size() != 1) {
            return Optional.empty(); // no WHERE (size 0) or OR-of-multiple-groups: use pushdownOrGroups()
        }
        List<FieldPredicate> predicates = new ArrayList<>();
        for (Condition condition : orGroups.get(0)) {
            if (condition.path.size() != 1) {
                continue; // nested-field path: not pushable, but the matcher still applies it
            }
            String field = condition.path.get(0);
            if (condition.op == Operator.EQ && condition.literal.isPlainString()) {
                predicates.add(FieldPredicate.stringEquality(field, condition.literal.asText()));
            } else if (condition.literal.isNumeric()) {
                PushdownOp op = numericOp(condition.op);
                if (op != null) { // numeric =/</<=/>/>= ; skip numeric <>//!= (rarely selective)
                    predicates.add(FieldPredicate.numericComparison(
                            field, op, condition.literal.numberValue(), condition.literal.asText()));
                }
            }
            // else (string range, boolean, null, ...): skip — the matcher re-filters authoritatively.
        }
        return predicates.isEmpty() ? Optional.empty() : Optional.of(predicates);
    }

    /**
     * For a multi-group OR WHERE, return one {@link FieldPredicate} list per OR-group for backend
     * pre-filtering. Only applicable when {@code orGroups.size() > 1}; returns empty otherwise. Also
     * returns empty if any group has no eligible predicates — a group with zero eligible conditions would
     * need to match every document, making OR pushdown equivalent to a full scan.
     */
    public Optional<List<List<FieldPredicate>>> pushdownOrGroups() {
        if (orGroups.size() <= 1) {
            return Optional.empty(); // single group → use pushdownPredicates(); no groups → no WHERE
        }
        List<List<FieldPredicate>> groups = new ArrayList<>();
        for (List<Condition> group : orGroups) {
            List<FieldPredicate> predicates = new ArrayList<>();
            for (Condition condition : group) {
                if (condition.path.size() != 1) continue;
                String field = condition.path.get(0);
                if (condition.op == Operator.EQ && condition.literal.isPlainString()) {
                    predicates.add(FieldPredicate.stringEquality(field, condition.literal.asText()));
                } else if (condition.literal.isNumeric()) {
                    PushdownOp op = numericOp(condition.op);
                    if (op != null) {
                        predicates.add(FieldPredicate.numericComparison(
                                field, op, condition.literal.numberValue(), condition.literal.asText()));
                    }
                }
            }
            if (predicates.isEmpty()) {
                return Optional.empty(); // this OR branch is ineligible; must fall back to scan
            }
            groups.add(predicates);
        }
        return Optional.of(groups);
    }

    /** Map a comparison operator to its pushdown form; {@code null} for the unsupported {@code NEQ}. */
    private static PushdownOp numericOp(Operator op) {
        switch (op) {
            case EQ: return PushdownOp.EQ;
            case LT: return PushdownOp.LT;
            case LTE: return PushdownOp.LTE;
            case GT: return PushdownOp.GT;
            case GTE: return PushdownOp.GTE;
            default: return null; // NEQ
        }
    }

    /**
     * Resolves a (possibly nested) field path of a value to its raw object, returning {@link #ABSENT}
     * when not resolvable. A single-element path is a top-level field; a longer path descends into nested
     * objects (e.g. {@code [address, zip]} reads {@code value.address.zip}). The default
     * {@link #MAP_RESOLVER} navigates map-typed values; callers (e.g. for PDX) can supply a resolver that
     * understands other value types.
     */
    @FunctionalInterface
    public interface FieldResolver {
        Object resolve(StoredValue value, List<String> path);
    }

    /** Sentinel for "field not resolvable on this value". */
    public static final Object ABSENT = new Object();

    /** Default resolver: navigates top-level keys (and nested maps) of map-typed values. */
    public static final FieldResolver MAP_RESOLVER = OqlQuery::resolveMapPath;

    /**
     * Descend one step into a nested member: a {@link Map} key, or — when {@code member} is a numeric
     * index — an element of a {@link List} or array. Returns {@link #ABSENT} when the current object is
     * not a navigable container or lacks the member. Shared so PDX navigation can reuse it.
     */
    public static Object navigateMember(Object current, String member) {
        if (current instanceof Map<?, ?> map) {
            return map.containsKey(member) ? map.get(member) : ABSENT;
        }
        Integer index = asIndex(member);
        if (index != null) {
            if (current instanceof List<?> list) {
                return index < list.size() ? list.get(index) : ABSENT;
            }
            if (current instanceof Object[] array) {
                return index < array.length ? array[index] : ABSENT;
            }
        }
        return ABSENT;
    }

    /** Parse a non-negative array index, or {@code null} when the member is not an index. */
    private static Integer asIndex(String member) {
        if (member.isEmpty()) {
            return null;
        }
        for (int i = 0; i < member.length(); i++) {
            if (!Character.isDigit(member.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.parseInt(member);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True if the value satisfies the WHERE clause (empty WHERE matches everything). */
    public boolean matches(StoredValue value) {
        return matches(value, MAP_RESOLVER);
    }

    public boolean matches(StoredValue value, FieldResolver resolver) {
        if (orGroups.isEmpty()) {
            return true;
        }
        for (List<Condition> andGroup : orGroups) {
            boolean all = true;
            for (Condition condition : andGroup) {
                if (!condition.matches(value, resolver)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /**
     * Project a matched value into its result row: {@code [value]} for {@code SELECT *}, a single
     * wrapped field for a single-field projection, or the M wrapped fields of a struct projection.
     */
    public List<StoredValue> projectRow(StoredValue value) {
        return projectRow(value, MAP_RESOLVER);
    }

    public List<StoredValue> projectRow(StoredValue value, FieldResolver resolver) {
        List<StoredValue> row = new ArrayList<>();
        if (projectionFields.isEmpty()) {
            row.add(value);
            return row;
        }
        for (List<String> path : projectionFields) {
            Object resolved = resolver.resolve(value, path);
            row.add(wrap(resolved == ABSENT ? null : resolved));
        }
        return row;
    }

    private static final Pattern BIND_PARAM = Pattern.compile("\\$(\\d+)");

    /**
     * Substitute {@code $1..$N} bind-parameter references in an OQL string with the corresponding
     * values from {@code params} (1-based), rendered as OQL literals: numbers/booleans bare, strings
     * single-quoted, {@code null} as {@code null}. The result is then parsed/matched exactly like a
     * literal query. Throws {@link UnsupportedQueryException} if a referenced parameter is missing.
     */
    public static String bindParameters(String oql, List<Object> params) {
        if (oql == null || !oql.contains("$")) {
            return oql;
        }
        List<Object> bound = params == null ? List.of() : params;
        return BIND_PARAM.matcher(oql).replaceAll(m -> {
            int oneBased = Integer.parseInt(m.group(1));
            if (oneBased < 1 || oneBased > bound.size()) {
                throw new UnsupportedQueryException(
                        "bind parameter $" + oneBased + " has no value (" + bound.size() + " provided)");
            }
            return Matcher.quoteReplacement(formatLiteral(bound.get(oneBased - 1)));
        });
    }

    /** Render a bind value as an OQL literal token. */
    private static String formatLiteral(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        // String (and any other type): an OQL single-quoted string literal, doubling embedded quotes.
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    public static OqlQuery parse(String oql) {
        if (oql == null || oql.isBlank()) {
            throw new UnsupportedQueryException("empty query");
        }
        String text = oql.trim();

        // Split off a trailing LIMIT first, so the main grammar stays unchanged.
        int limit = -1;
        Matcher limitMatcher = LIMIT_TAIL.matcher(text);
        if (limitMatcher.matches()) {
            try {
                limit = Integer.parseInt(limitMatcher.group(2));
            } catch (NumberFormatException e) {
                throw new UnsupportedQueryException("invalid LIMIT: " + text);
            }
            text = limitMatcher.group(1).trim() + limitMatcher.group(3); // keep a trailing ';' if present
        }

        // Split off GROUP BY (must come after LIMIT so LIMIT n doesn't end up in the keys group).
        String groupByClause = null;
        Matcher groupByMatcher = GROUP_BY_SPLIT.matcher(text);
        if (groupByMatcher.matches()) {
            groupByClause = groupByMatcher.group(2).trim();
            text = groupByMatcher.group(1).trim() + groupByMatcher.group(3);
        }

        Matcher matcher = QUERY.matcher(text);
        if (!matcher.matches()) {
            throw new UnsupportedQueryException(
                    "only 'SELECT (* | field, ...) FROM /region [WHERE ...] [ORDER BY ...] [LIMIT n]' "
                            + "is supported: " + oql);
        }
        String alias = matcher.group(3); // the FROM alias (e.g. `r`), or null
        String selectList = matcher.group(1);
        String whereClause = matcher.group(4);
        String orderByClause = matcher.group(5);

        // GROUP BY path: parse the mixed SELECT list (group keys + aggregate column).
        if (groupByClause != null) {
            if (orderByClause != null && !orderByClause.isBlank()) {
                throw new UnsupportedQueryException("ORDER BY is not supported with GROUP BY: " + oql);
            }
            List<GroupByColumn> columns = parseGroupBySelectList(selectList, alias);
            return new OqlQuery(matcher.group(2), List.of(),
                    parseWhere(whereClause, alias), List.of(), limit, null, null, columns, false);
        }

        // Check for a scalar aggregate before trying the projection path.
        Matcher aggMatcher = AGGREGATE.matcher(selectList);
        if (aggMatcher.matches()) {
            if (orderByClause != null && !orderByClause.isBlank()) {
                throw new UnsupportedQueryException("ORDER BY is not supported with aggregate functions: " + oql);
            }
            AggregateFunction fn = parseAggregateFunction(aggMatcher.group(1), aggMatcher.group(2));
            List<String> aggField = (fn == AggregateFunction.COUNT_STAR)
                    ? null : toPath(aggMatcher.group(2), alias);
            return new OqlQuery(matcher.group(2), List.of(),
                    parseWhere(whereClause, alias), List.of(), limit, fn, aggField, null, false);
        }

        // Check for SELECT DISTINCT.
        boolean isDistinct = false;
        String trimmedSelect = selectList.trim();
        if (trimmedSelect.toUpperCase().startsWith("DISTINCT")) {
            isDistinct = true;
            trimmedSelect = trimmedSelect.substring("DISTINCT".length()).trim();
            if (trimmedSelect.equals("*")) {
                // SELECT DISTINCT * fails on real Geode 1.15 (SerializationException) — reject cleanly.
                throw new UnsupportedQueryException("SELECT DISTINCT * is not supported: " + oql);
            }
        }

        return new OqlQuery(matcher.group(2), parseProjection(trimmedSelect, alias),
                parseWhere(whereClause, alias), parseOrderBy(orderByClause, alias), limit,
                null, null, null, isDistinct);
    }

    /**
     * Parse a GROUP BY SELECT list like {@code "status, COUNT(*)"} or {@code "status, category, SUM(amount)"}.
     * Each comma-separated item is either an aggregate function call (→ GroupByColumn with fn set) or a
     * plain field reference (→ group key column). The aggregate column name follows Geode's convention:
     * COUNT(*)/COUNT(field) → {@code "0"} (positional ordinal); SUM/AVG/MIN/MAX(field) → the field name.
     */
    private static List<GroupByColumn> parseGroupBySelectList(String selectList, String alias) {
        List<GroupByColumn> columns = new ArrayList<>();
        boolean hasAggregate = false;
        for (String raw : selectList.split(",")) {
            String item = raw.trim();
            Matcher agg = AGGREGATE.matcher(item);
            if (agg.matches()) {
                if (hasAggregate) {
                    throw new UnsupportedQueryException(
                            "only one aggregate function per GROUP BY is supported: " + selectList);
                }
                hasAggregate = true;
                AggregateFunction fn = parseAggregateFunction(agg.group(1), agg.group(2));
                List<String> fieldPath = (fn == AggregateFunction.COUNT_STAR)
                        ? null : toPath(agg.group(2), alias);
                // COUNT → column name "0"; SUM/AVG/MIN/MAX → the simple field name
                String colName = (fn == AggregateFunction.COUNT_STAR || fn == AggregateFunction.COUNT_FIELD)
                        ? "0" : agg.group(2).contains(".") ? toPath(agg.group(2), alias).get(toPath(agg.group(2), alias).size() - 1) : agg.group(2);
                columns.add(new GroupByColumn(colName, fn, fieldPath));
            } else {
                if (!item.matches(PROJECTION_FIELD)) {
                    throw new UnsupportedQueryException("unsupported GROUP BY SELECT column: " + item);
                }
                List<String> path = toPath(item, alias);
                String colName = path.get(path.size() - 1); // simple field name
                columns.add(new GroupByColumn(colName, null, path));
            }
        }
        if (!hasAggregate) {
            throw new UnsupportedQueryException(
                    "GROUP BY SELECT list must contain at least one aggregate function: " + selectList);
        }
        return columns;
    }

    private static AggregateFunction parseAggregateFunction(String fn, String arg) {
        switch (fn.toUpperCase()) {
            case "COUNT": return "*".equals(arg) ? AggregateFunction.COUNT_STAR : AggregateFunction.COUNT_FIELD;
            case "SUM":   return AggregateFunction.SUM;
            case "MIN":   return AggregateFunction.MIN;
            case "MAX":   return AggregateFunction.MAX;
            case "AVG":   return AggregateFunction.AVG;
            default: throw new UnsupportedQueryException("unknown aggregate function: " + fn);
        }
    }

    /**
     * Turn a field reference into a navigation path, stripping a leading FROM alias. {@code r.address.zip}
     * (alias {@code r}) becomes {@code [address, zip]}; {@code address.zip} becomes {@code [address, zip]};
     * {@code r.status} becomes {@code [status]}; {@code status} becomes {@code [status]}. Each remaining
     * segment must be a plain identifier.
     */
    private static List<String> toPath(String raw, String alias) {
        String[] segments = raw.trim().split("\\.");
        int start = 0;
        if (alias != null && segments.length > 1 && segments[0].equals(alias)) {
            start = 1; // drop the leading alias qualifier
        }
        List<String> path = new ArrayList<>(segments.length - start);
        for (int i = start; i < segments.length; i++) {
            Matcher m = SEGMENT.matcher(segments[i]);
            if (!m.matches()) {
                throw new UnsupportedQueryException("unsupported field path: " + raw);
            }
            path.add(m.group(1)); // the field name
            Matcher index = INDEX.matcher(m.group(2)); // each [n] becomes its own (numeric) path segment
            while (index.find()) {
                path.add(index.group(1));
            }
        }
        if (path.isEmpty()) {
            throw new UnsupportedQueryException("empty field path: " + raw);
        }
        return path;
    }

    /** Sort matched values in place by the ORDER BY keys (no-op when there is no ORDER BY). */
    public void sort(List<StoredValue> values) {
        sort(values, MAP_RESOLVER);
    }

    public void sort(List<StoredValue> values, FieldResolver resolver) {
        if (orderBy.isEmpty()) {
            return;
        }
        values.sort((a, b) -> {
            for (OrderKey key : orderBy) {
                int c = compareField(a, b, key.path, resolver);
                if (c != 0) {
                    return key.ascending ? c : -c;
                }
            }
            return 0;
        });
    }

    private static List<OrderKey> parseOrderBy(String orderBy, String alias) {
        List<OrderKey> keys = new ArrayList<>();
        if (orderBy == null || orderBy.isBlank()) {
            return keys;
        }
        for (String part : orderBy.split(",")) {
            String[] tokens = part.trim().split("\\s+");
            List<String> path = toPath(tokens[0], alias);
            boolean ascending = true;
            if (tokens.length == 2) {
                if (tokens[1].equalsIgnoreCase("DESC")) {
                    ascending = false;
                } else if (!tokens[1].equalsIgnoreCase("ASC")) {
                    throw new UnsupportedQueryException("unsupported ORDER BY direction: " + tokens[1]);
                }
            } else if (tokens.length > 2) {
                throw new UnsupportedQueryException("unsupported ORDER BY clause: " + part.trim());
            }
            keys.add(new OrderKey(path, ascending));
        }
        return keys;
    }

    /** Compare a field across two values: numeric when both parse as numbers, else string; nulls last. */
    private static int compareField(StoredValue a, StoredValue b, List<String> path, FieldResolver resolver) {
        Object av = resolver.resolve(a, path);
        Object bv = resolver.resolve(b, path);
        boolean aMissing = av == ABSENT || av == null;
        boolean bMissing = bv == ABSENT || bv == null;
        if (aMissing && bMissing) {
            return 0;
        }
        if (aMissing) {
            return 1;
        }
        if (bMissing) {
            return -1;
        }
        Double an = numericOrNull(av);
        Double bn = numericOrNull(bv);
        if (an != null && bn != null) {
            return Double.compare(an, bn);
        }
        return String.valueOf(av).compareTo(String.valueOf(bv));
    }

    private static Double numericOrNull(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class OrderKey {
        private final List<String> path;
        private final boolean ascending;

        OrderKey(List<String> path, boolean ascending) {
            this.path = path;
            this.ascending = ascending;
        }
    }

    private static List<List<String>> parseProjection(String selectList, String alias) {
        List<List<String>> fields = new ArrayList<>();
        String list = selectList.trim();
        if (list.equals("*")) {
            return fields; // SELECT *
        }
        if (list.toUpperCase().startsWith("DISTINCT")) {
            throw new UnsupportedQueryException("DISTINCT is not supported: " + list);
        }
        for (String raw : list.split(",")) {
            String field = raw.trim();
            // A dotted path whose segments may each carry array indices (e.g. addresses[0].zip), matching
            // what toPath accepts for WHERE/ORDER BY so projection stays consistent with predicate paths.
            if (!field.matches(PROJECTION_FIELD)) {
                throw new UnsupportedQueryException("unsupported projection field: " + field);
            }
            fields.add(toPath(field, alias));
        }
        return fields;
    }

    private static List<List<Condition>> parseWhere(String where, String alias) {
        if (where == null || where.isBlank()) {
            return new ArrayList<>();
        }
        return parseDnf(where.trim(), alias);
    }

    /**
     * Parse a WHERE sub-expression into DNF (list of AND-groups). OR has lower precedence than AND;
     * parentheses override precedence. `(A OR B) AND C` is distributed to `[[A,C],[B,C]]`.
     */
    private static List<List<Condition>> parseDnf(String expr, String alias) {
        List<String> orParts = splitTopLevel(expr, "OR");
        List<List<Condition>> result = new ArrayList<>();
        for (String part : orParts) {
            result.addAll(parseConjunction(part.trim(), alias));
        }
        return result;
    }

    /** Parse a conjunction into DNF, distributing any inner OR (via cross-product) upward. */
    private static List<List<Condition>> parseConjunction(String expr, String alias) {
        List<String> andParts = splitTopLevel(expr, "AND");
        List<List<Condition>> dnf = new ArrayList<>();
        dnf.add(new ArrayList<>()); // start with a single empty AND-group
        for (String part : andParts) {
            List<List<Condition>> atomDnf = parseAtomDnf(part.trim(), alias);
            List<List<Condition>> next = new ArrayList<>();
            for (List<Condition> existing : dnf) {
                for (List<Condition> atomGroup : atomDnf) {
                    List<Condition> combined = new ArrayList<>(existing);
                    combined.addAll(atomGroup);
                    next.add(combined);
                }
            }
            dnf = next;
        }
        return dnf;
    }

    /** Parse an atom: either a fully-parenthesized sub-expression or a leaf condition. */
    private static List<List<Condition>> parseAtomDnf(String expr, String alias) {
        String e = expr.trim();
        if (e.startsWith("(")) {
            int close = findMatchingClose(e, 0);
            if (close == e.length() - 1) {
                return parseDnf(e.substring(1, close).trim(), alias);
            }
        }
        List<Condition> single = new ArrayList<>();
        single.add(parseSingleCondition(e, alias));
        List<List<Condition>> result = new ArrayList<>();
        result.add(single);
        return result;
    }

    /** Parse one leaf condition clause (e.g. `status = 'active'` or `'x' IN tags`). */
    private static Condition parseSingleCondition(String clause, String alias) {
        Matcher m = CONDITION.matcher(clause);
        if (m.matches()) {
            return new Condition(toPath(m.group(1), alias), Operator.of(m.group(2)),
                    Literal.parse(m.group(3)));
        }
        Matcher in = IN_CONDITION.matcher(clause);
        if (in.matches()) {
            // `<literal> IN <path>`: the path resolves to the collection; the literal is the
            // element sought (containment). The left operand must be a literal, not a field.
            return new Condition(toPath(in.group(2), alias), Operator.IN,
                    Literal.parse(in.group(1)));
        }
        throw new UnsupportedQueryException("unsupported condition: " + clause.trim());
    }

    /**
     * Find the index of the `)` that closes the `(` at position {@code open} in {@code s},
     * skipping nested parens and single-quoted string literals.
     */
    private static int findMatchingClose(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') { depth++; }
            else if (c == ')') { depth--; if (depth == 0) return i; }
            else if (c == '\'') { i++; while (i < s.length() && s.charAt(i) != '\'') i++; }
        }
        throw new UnsupportedQueryException("unmatched parenthesis: " + s);
    }

    /**
     * Split {@code expr} on top-level (paren-depth=0, not inside string literals) occurrences of
     * {@code keyword} (case-insensitive), where the keyword is bounded by whitespace on both sides.
     * Returns a list of at least one part (the original expression if no split point is found).
     */
    private static List<String> splitTopLevel(String expr, String keyword) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        int n = expr.length();
        int kwLen = keyword.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (c == '(') { depth++; i++; }
            else if (c == ')') { depth--; i++; }
            else if (c == '\'') {
                i++;
                while (i < n && expr.charAt(i) != '\'') i++;
                if (i < n) i++;
            } else if (depth == 0 && Character.isWhitespace(c)) {
                int wsStart = i;
                int pos = i;
                while (pos < n && Character.isWhitespace(expr.charAt(pos))) pos++;
                if (pos + kwLen <= n
                        && expr.substring(pos, pos + kwLen).equalsIgnoreCase(keyword)
                        && (pos + kwLen == n || Character.isWhitespace(expr.charAt(pos + kwLen)))) {
                    parts.add(expr.substring(start, wsStart).trim());
                    i = pos + kwLen;
                    while (i < n && Character.isWhitespace(expr.charAt(i))) i++;
                    start = i;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        String last = expr.substring(start).trim();
        if (!last.isEmpty()) parts.add(last);
        if (parts.isEmpty()) parts.add(expr.trim());
        return parts;
    }

    /** Raised when a query uses a feature outside the supported subset. */
    public static final class UnsupportedQueryException extends RuntimeException {
        public UnsupportedQueryException(String message) {
            super(message);
        }
    }

    enum Operator {
        EQ, NEQ, LT, LTE, GT, GTE, IN;

        static Operator of(String token) {
            switch (token) {
                case "=": return EQ;
                case "<>":
                case "!=": return NEQ;
                case "<": return LT;
                case "<=": return LTE;
                case ">": return GT;
                case ">=": return GTE;
                default: throw new UnsupportedQueryException("unknown operator: " + token);
            }
        }
    }

    static final class Condition {
        private final List<String> path;
        private final Operator op;
        private final Literal literal;

        Condition(List<String> path, Operator op, Literal literal) {
            this.path = path;
            this.op = op;
            this.literal = literal;
        }

        boolean matches(StoredValue value, FieldResolver resolver) {
            Object actual = resolver.resolve(value, path);
            if (actual == ABSENT) {
                return false;
            }
            switch (op) {
                case EQ: return literal.equalsValue(actual);
                case NEQ: return !literal.equalsValue(actual);
                case IN: return containsLiteral(actual);
                default:
                    Integer cmp = literal.compareValue(actual);
                    if (cmp == null) {
                        return false;
                    }
                    switch (op) {
                        case LT: return cmp < 0;
                        case LTE: return cmp <= 0;
                        case GT: return cmp > 0;
                        case GTE: return cmp >= 0;
                        default: return false;
                    }
            }
        }

        /** True if the resolved collection (a {@link java.util.Collection} or array) contains the literal. */
        private boolean containsLiteral(Object collection) {
            if (collection instanceof java.util.Collection<?> elements) {
                for (Object element : elements) {
                    if (literal.equalsValue(element)) {
                        return true;
                    }
                }
                return false;
            }
            if (collection instanceof Object[] array) {
                for (Object element : array) {
                    if (literal.equalsValue(element)) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
    }

    /** Resolve a path against a map-typed value: the first segment is a top-level key, then descend. */
    private static Object resolveMapPath(StoredValue value, List<String> path) {
        if (value == null || path.isEmpty()) {
            return ABSENT;
        }
        Object current = resolveMapField(value, path.get(0));
        for (int i = 1; i < path.size() && current != ABSENT; i++) {
            current = navigateMember(current, path.get(i));
        }
        return current;
    }

    private static Object resolveMapField(StoredValue value, String field) {
        if (value == null) {
            return ABSENT;
        }
        if (value.type() == StoredValue.Type.STRING_OBJECT_HASH_MAP) {
            Map<String, Object> map = value.asStringObjectHashMap();
            return map.containsKey(field) ? map.get(field) : ABSENT;
        }
        if (value.type() == StoredValue.Type.STRING_HASH_MAP) {
            Map<String, String> map = value.asStringHashMap();
            return map.containsKey(field) ? map.get(field) : ABSENT;
        }
        return ABSENT;
    }

    /** Wrap a projected field value (from a map) back into a StoredValue for the result. */
    private static StoredValue wrap(Object o) {
        if (o == null) {
            return StoredValue.stringValue("");
        }
        if (o instanceof String s) {
            return StoredValue.stringValue(s);
        }
        if (o instanceof Boolean b) {
            return StoredValue.booleanValue(b);
        }
        if (o instanceof Integer i) {
            return StoredValue.integerValue(i);
        }
        if (o instanceof Long l) {
            return StoredValue.longValue(l);
        }
        if (o instanceof Short sh) {
            return StoredValue.shortValue(sh);
        }
        if (o instanceof Byte by) {
            return StoredValue.byteValue(by);
        }
        if (o instanceof Double d) {
            return StoredValue.doubleValue(d);
        }
        if (o instanceof Float f) {
            return StoredValue.floatValue(f);
        }
        if (o instanceof Date dt) {
            return StoredValue.dateValue(dt);
        }
        return StoredValue.stringValue(String.valueOf(o));
    }

    static final class Literal {
        private final Double number;
        private final String text;
        private final Boolean bool;
        private final boolean isNull;

        private Literal(Double number, String text, Boolean bool, boolean isNull) {
            this.number = number;
            this.text = text;
            this.bool = bool;
            this.isNull = isNull;
        }

        static Literal parse(String raw) {
            String t = raw.trim();
            if ((t.startsWith("'") && t.endsWith("'") && t.length() >= 2)
                    || (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2)) {
                return new Literal(null, t.substring(1, t.length() - 1), null, false);
            }
            if (t.equalsIgnoreCase("null")) {
                return new Literal(null, null, null, true);
            }
            if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
                return new Literal(null, t, Boolean.parseBoolean(t), false);
            }
            try {
                return new Literal(Double.parseDouble(t), t, null, false);
            } catch (NumberFormatException e) {
                return new Literal(null, t, null, false);
            }
        }

        /**
         * A "plain string" literal: a quoted string, or an unquoted bareword that is not a number,
         * boolean, or null. These compare by string value in {@link #equalsValue}, so they are the
         * literals safe to push down as a string-equality predicate.
         */
        boolean isPlainString() {
            return !isNull && number == null && bool == null && text != null;
        }

        /** The literal's text form (defined for {@link #isPlainString()} and numeric literals). */
        String asText() {
            return text;
        }

        /** A numeric literal (an unquoted number); {@link #numberValue()} holds its value. */
        boolean isNumeric() {
            return number != null;
        }

        /** The numeric value (defined for {@link #isNumeric()} literals). */
        double numberValue() {
            return number;
        }

        boolean equalsValue(Object actual) {
            if (isNull) {
                return actual == null;
            }
            if (actual == null) {
                return false;
            }
            if (number != null) {
                Double a = asDouble(actual);
                return a != null && a.doubleValue() == number.doubleValue();
            }
            if (bool != null) {
                return bool.equals(actual) || String.valueOf(actual).equalsIgnoreCase(text);
            }
            return String.valueOf(actual).equals(text);
        }

        Integer compareValue(Object actual) {
            if (isNull || actual == null) {
                return null;
            }
            if (number != null) {
                Double a = asDouble(actual);
                return a == null ? null : Double.compare(a, number);
            }
            return String.valueOf(actual).compareTo(text == null ? "" : text);
        }

        private static Double asDouble(Object value) {
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
