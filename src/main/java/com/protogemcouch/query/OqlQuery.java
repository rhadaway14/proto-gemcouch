package com.protogemcouch.query;

import com.protogemcouch.serialization.StoredValue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed OQL query.
 *
 * <p>Supported subset:
 * {@code SELECT (* | <field>) FROM /region [alias] [WHERE <conditions>]}, where conditions are
 * {@code <field> <op> <literal>} (ops {@code = <> != < <= > >=}; literals are quoted strings,
 * numbers, booleans, or {@code null}) combined with {@code AND}/{@code OR} (AND binds tighter; no
 * parentheses). Field access resolves a top-level key of map-typed values (a Geode {@code HashMap}
 * value). A single projected field returns that field's value per row; {@code SELECT *} returns the
 * whole value. Anything else (multi-field/struct projections, parentheses, {@code ORDER BY}, joins,
 * methods) is reported as unsupported so the shim returns a clean query error.
 */
public final class OqlQuery {

    private static final Pattern QUERY = Pattern.compile(
            "(?is)^\\s*SELECT\\s+(.+?)\\s+FROM\\s+(/[A-Za-z0-9_./-]+|[A-Za-z0-9_./-]+)"
                    + "(?:\\s+(?!WHERE\\b)(?!ORDER\\b)[A-Za-z_][A-Za-z0-9_]*)?"  // optional alias
                    + "(?:\\s+WHERE\\s+(.+?))?"
                    + "(?:\\s+ORDER\\s+BY\\s+(.+?))?"
                    + "\\s*;?\\s*$");

    private static final Pattern CONDITION = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*(<=|>=|<>|!=|=|<|>)\\s*(.+?)\\s*$");

    // A trailing `LIMIT <n>` is split off before the main grammar runs (the closing token before `;`/end
    // can only be a literal count, so it can't be confused with a string literal ending in a quote).
    private static final Pattern LIMIT_TAIL = Pattern.compile("(?is)^(.*?)\\s+LIMIT\\s+(\\d+)\\s*(;?)\\s*$");

    private final String regionPath;
    private final List<String> projectionFields;   // empty = SELECT *
    private final List<List<Condition>> orGroups;  // OR of AND-groups; empty = match all
    private final List<OrderKey> orderBy;          // empty = no ordering
    private final int limit;                       // -1 = no LIMIT; >= 0 = explicit row cap

    private OqlQuery(String regionPath, List<String> projectionFields,
                    List<List<Condition>> orGroups, List<OrderKey> orderBy, int limit) {
        this.regionPath = regionPath;
        this.projectionFields = projectionFields;
        this.orGroups = orGroups;
        this.orderBy = orderBy;
        this.limit = limit;
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

    /** A simple, single-segment field name safe to embed as an identifier in a pushdown path. */
    private static final Pattern SIMPLE_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Whether the query has a WHERE clause (an empty WHERE matches every value). */
    public boolean hasWhere() {
        return !orGroups.isEmpty();
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
            return Optional.empty(); // no WHERE (size 0) or an OR is present (size > 1)
        }
        List<FieldPredicate> predicates = new ArrayList<>();
        for (Condition condition : orGroups.get(0)) {
            if (!SIMPLE_FIELD.matcher(condition.field).matches()) {
                continue; // dotted/nested field: not pushable, but the matcher still applies it
            }
            if (condition.op == Operator.EQ && condition.literal.isPlainString()) {
                predicates.add(FieldPredicate.stringEquality(condition.field, condition.literal.asText()));
            } else if (condition.literal.isNumeric()) {
                PushdownOp op = numericOp(condition.op);
                if (op != null) { // numeric =/</<=/>/>= ; skip numeric <>//!= (rarely selective)
                    predicates.add(FieldPredicate.numericComparison(
                            condition.field, op, condition.literal.numberValue(), condition.literal.asText()));
                }
            }
            // else (string range, boolean, null, ...): skip — the matcher re-filters authoritatively.
        }
        return predicates.isEmpty() ? Optional.empty() : Optional.of(predicates);
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
     * Resolves a field of a value to its raw object, returning {@link #ABSENT} when not resolvable.
     * The default {@link #MAP_RESOLVER} reads map-typed values; callers (e.g. for PDX) can supply a
     * resolver that understands other value types.
     */
    @FunctionalInterface
    public interface FieldResolver {
        Object resolve(StoredValue value, String field);
    }

    /** Sentinel for "field not resolvable on this value". */
    public static final Object ABSENT = new Object();

    /** Default resolver: top-level keys of map-typed values. */
    public static final FieldResolver MAP_RESOLVER = OqlQuery::resolveMapField;

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
        for (String field : projectionFields) {
            Object resolved = resolver.resolve(value, field);
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

        Matcher matcher = QUERY.matcher(text);
        if (!matcher.matches()) {
            throw new UnsupportedQueryException(
                    "only 'SELECT (* | field, ...) FROM /region [WHERE ...] [ORDER BY ...] [LIMIT n]' "
                            + "is supported: " + oql);
        }
        return new OqlQuery(matcher.group(2), parseProjection(matcher.group(1)),
                parseWhere(matcher.group(3)), parseOrderBy(matcher.group(4)), limit);
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
                int c = compareField(a, b, key.field, resolver);
                if (c != 0) {
                    return key.ascending ? c : -c;
                }
            }
            return 0;
        });
    }

    private static List<OrderKey> parseOrderBy(String orderBy) {
        List<OrderKey> keys = new ArrayList<>();
        if (orderBy == null || orderBy.isBlank()) {
            return keys;
        }
        for (String part : orderBy.split(",")) {
            String[] tokens = part.trim().split("\\s+");
            String field = lastSegment(tokens[0]);
            if (!field.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                throw new UnsupportedQueryException("unsupported ORDER BY field: " + part.trim());
            }
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
            keys.add(new OrderKey(field, ascending));
        }
        return keys;
    }

    /** Compare a field across two values: numeric when both parse as numbers, else string; nulls last. */
    private static int compareField(StoredValue a, StoredValue b, String field, FieldResolver resolver) {
        Object av = resolver.resolve(a, field);
        Object bv = resolver.resolve(b, field);
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
        private final String field;
        private final boolean ascending;

        OrderKey(String field, boolean ascending) {
            this.field = field;
            this.ascending = ascending;
        }
    }

    private static List<String> parseProjection(String selectList) {
        List<String> fields = new ArrayList<>();
        String list = selectList.trim();
        if (list.equals("*")) {
            return fields; // SELECT *
        }
        if (list.toUpperCase().startsWith("DISTINCT")) {
            throw new UnsupportedQueryException("DISTINCT is not supported: " + list);
        }
        for (String raw : list.split(",")) {
            String field = raw.trim();
            if (!field.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                throw new UnsupportedQueryException("unsupported projection field: " + field);
            }
            fields.add(lastSegment(field));
        }
        return fields;
    }

    private static List<List<Condition>> parseWhere(String where) {
        List<List<Condition>> orGroups = new ArrayList<>();
        if (where == null || where.isBlank()) {
            return orGroups;
        }
        if (where.contains("(") || where.contains(")")) {
            throw new UnsupportedQueryException("parentheses are not supported: " + where);
        }
        for (String orGroup : where.split("(?i)\\s+OR\\s+")) {
            List<Condition> andGroup = new ArrayList<>();
            for (String clause : orGroup.split("(?i)\\s+AND\\s+")) {
                Matcher m = CONDITION.matcher(clause);
                if (!m.matches()) {
                    throw new UnsupportedQueryException("unsupported condition: " + clause.trim());
                }
                andGroup.add(new Condition(lastSegment(m.group(1)), Operator.of(m.group(2)),
                        Literal.parse(m.group(3))));
            }
            orGroups.add(andGroup);
        }
        return orGroups;
    }

    /** Take the final segment of a possibly alias/nested path (e.g. {@code r.status} -> {@code status}). */
    private static String lastSegment(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /** Raised when a query uses a feature outside the supported subset. */
    public static final class UnsupportedQueryException extends RuntimeException {
        public UnsupportedQueryException(String message) {
            super(message);
        }
    }

    enum Operator {
        EQ, NEQ, LT, LTE, GT, GTE;

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
        private final String field;
        private final Operator op;
        private final Literal literal;

        Condition(String field, Operator op, Literal literal) {
            this.field = field;
            this.op = op;
            this.literal = literal;
        }

        boolean matches(StoredValue value, FieldResolver resolver) {
            Object actual = resolver.resolve(value, field);
            if (actual == ABSENT) {
                return false;
            }
            switch (op) {
                case EQ: return literal.equalsValue(actual);
                case NEQ: return !literal.equalsValue(actual);
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
