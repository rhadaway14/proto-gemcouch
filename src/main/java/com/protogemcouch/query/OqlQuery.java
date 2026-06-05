package com.protogemcouch.query;

import com.protogemcouch.serialization.StoredValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed OQL query.
 *
 * <p>Supported subset: {@code SELECT * FROM /region [alias] [WHERE <conditions>]}, where each
 * condition is {@code <field> <op> <literal>} (ops {@code = <> != < <= > >=}; literals are quoted
 * strings, numbers, booleans, or {@code null}) and conditions are joined by {@code AND}. Field
 * access resolves a top-level key of map-typed values (a Geode {@code HashMap} value); other value
 * types simply do not match a field predicate. Anything outside this subset (projections, {@code OR},
 * parentheses, {@code ORDER BY}, joins, methods) is reported as unsupported so the shim can return a
 * clean query error instead of silently mishandling it.
 */
public final class OqlQuery {

    private static final Pattern QUERY = Pattern.compile(
            "(?is)^\\s*SELECT\\s+\\*\\s+FROM\\s+(/[A-Za-z0-9_./-]+|[A-Za-z0-9_./-]+)"
                    + "(?:\\s+(?!WHERE\\b)[A-Za-z_][A-Za-z0-9_]*)?"   // optional alias
                    + "(?:\\s+WHERE\\s+(.+?))?\\s*;?\\s*$");

    private static final Pattern CONDITION = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*(<=|>=|<>|!=|=|<|>)\\s*(.+?)\\s*$");

    private final String regionPath;
    private final List<Condition> conditions;

    private OqlQuery(String regionPath, List<Condition> conditions) {
        this.regionPath = regionPath;
        this.conditions = conditions;
    }

    public String regionPath() {
        return regionPath;
    }

    /** True if the value satisfies every condition (an empty WHERE matches everything). */
    public boolean matches(StoredValue value) {
        for (Condition condition : conditions) {
            if (!condition.matches(value)) {
                return false;
            }
        }
        return true;
    }

    public static OqlQuery parse(String oql) {
        if (oql == null || oql.isBlank()) {
            throw new UnsupportedQueryException("empty query");
        }
        String text = oql.trim();
        Matcher matcher = QUERY.matcher(text);
        if (!matcher.matches()) {
            throw new UnsupportedQueryException(
                    "only 'SELECT * FROM /region [WHERE ...]' is supported in this build: " + text);
        }
        String region = matcher.group(1);
        String where = matcher.group(2);
        return new OqlQuery(region, parseConditions(where));
    }

    private static List<Condition> parseConditions(String where) {
        List<Condition> conditions = new ArrayList<>();
        if (where == null || where.isBlank()) {
            return conditions;
        }
        if (where.contains("(") || where.contains(")")) {
            throw new UnsupportedQueryException("parentheses are not supported: " + where);
        }
        if (where.matches("(?is).*\\bOR\\b.*")) {
            throw new UnsupportedQueryException("OR is not supported: " + where);
        }
        for (String clause : where.split("(?i)\\s+AND\\s+")) {
            Matcher m = CONDITION.matcher(clause);
            if (!m.matches()) {
                throw new UnsupportedQueryException("unsupported condition: " + clause.trim());
            }
            conditions.add(new Condition(lastSegment(m.group(1)), Operator.of(m.group(2)),
                    Literal.parse(m.group(3))));
        }
        return conditions;
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

    /** One {@code field op literal} condition. */
    static final class Condition {
        private final String field;
        private final Operator op;
        private final Literal literal;

        Condition(String field, Operator op, Literal literal) {
            this.field = field;
            this.op = op;
            this.literal = literal;
        }

        boolean matches(StoredValue value) {
            Object actual = resolveField(value, field);
            if (actual == ABSENT) {
                return false; // field not resolvable on this value
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

    private static final Object ABSENT = new Object();

    /** Resolve a top-level field of a map-typed value; {@link #ABSENT} if not resolvable. */
    private static Object resolveField(StoredValue value, String field) {
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

    /** A WHERE literal: number, string, boolean, or null, with lenient comparison helpers. */
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
                return new Literal(null, t, null, false); // bare identifier treated as a string
            }
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

        /** -1/0/1 comparing {@code actual} to this literal, or null if not comparable. */
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
