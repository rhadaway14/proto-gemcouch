package com.protogemcouch.query;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed OQL query. The first-cut OQL support targets {@code SELECT * FROM /region} only — the
 * smallest shape that still exercises the full chunked query-response path. Anything beyond that
 * (projections, {@code WHERE}, joins, indexes, methods) is reported as unsupported so the shim can
 * return a clean query error instead of silently mishandling it.
 */
public final class OqlQuery {

    // SELECT * FROM <region-path>, case-insensitive, optional trailing semicolon/whitespace.
    private static final Pattern SELECT_STAR = Pattern.compile(
            "(?is)^\\s*SELECT\\s+\\*\\s+FROM\\s+(/[A-Za-z0-9_./-]+|[A-Za-z0-9_./-]+)\\s*;?\\s*$");

    private final String regionPath;

    private OqlQuery(String regionPath) {
        this.regionPath = regionPath;
    }

    /** The region path referenced by the query (as written, e.g. {@code /orders}). */
    public String regionPath() {
        return regionPath;
    }

    /**
     * Parse a {@code SELECT * FROM /region} query.
     *
     * @throws UnsupportedQueryException if the text is blank or uses any feature beyond the first-cut
     *         {@code SELECT *} shape (e.g. a {@code WHERE} clause or a projection list).
     */
    public static OqlQuery parse(String oql) {
        if (oql == null || oql.isBlank()) {
            throw new UnsupportedQueryException("empty query");
        }
        Matcher matcher = SELECT_STAR.matcher(oql.trim());
        if (!matcher.matches()) {
            throw new UnsupportedQueryException(
                    "only 'SELECT * FROM /region' is supported in this build: " + oql.trim());
        }
        return new OqlQuery(matcher.group(1));
    }

    /** Raised when a query uses a feature outside the supported subset. */
    public static final class UnsupportedQueryException extends RuntimeException {
        public UnsupportedQueryException(String message) {
            super(message);
        }
    }
}
