package com.protogemcouch.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OqlQueryTest {

    @Test
    void parsesSelectStarAndExtractsRegionPath() {
        assertEquals("/orders", OqlQuery.parse("SELECT * FROM /orders").regionPath());
        assertEquals("/orders", OqlQuery.parse("  select   *   from   /orders  ").regionPath(),
                "case-insensitive, whitespace-tolerant");
        assertEquals("/orders", OqlQuery.parse("SELECT * FROM /orders;").regionPath(),
                "trailing semicolon allowed");
        assertEquals("region", OqlQuery.parse("SELECT * FROM region").regionPath(),
                "region path without a leading slash is accepted");
    }

    @Test
    void rejectsUnsupportedQueries() {
        assertThrows(OqlQuery.UnsupportedQueryException.class,
                () -> OqlQuery.parse("SELECT * FROM /orders WHERE status = 'open'"));
        assertThrows(OqlQuery.UnsupportedQueryException.class,
                () -> OqlQuery.parse("SELECT name FROM /orders"));
        assertThrows(OqlQuery.UnsupportedQueryException.class,
                () -> OqlQuery.parse("SELECT DISTINCT * FROM /orders"));
        assertThrows(OqlQuery.UnsupportedQueryException.class, () -> OqlQuery.parse(""));
        assertThrows(OqlQuery.UnsupportedQueryException.class, () -> OqlQuery.parse(null));
    }
}
