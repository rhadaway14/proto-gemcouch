package com.protogemcouch.couchbase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parsing rules for {@code CB_MAX_VALUE_BYTES}: unset/blank/garbage falls back to Couchbase's 20 MiB
 * ceiling, an explicit positive value is honored, and {@code 0}/negative disables the limit.
 */
class MaxValueBytesConfigTest {

    @Test
    void defaultsToCouchbaseCeilingWhenUnsetBlankOrUnparseable() {
        assertEquals(CouchbaseRepository.DEFAULT_MAX_VALUE_BYTES, CouchbaseRepository.parseMaxValueBytes(null));
        assertEquals(CouchbaseRepository.DEFAULT_MAX_VALUE_BYTES, CouchbaseRepository.parseMaxValueBytes(""));
        assertEquals(CouchbaseRepository.DEFAULT_MAX_VALUE_BYTES, CouchbaseRepository.parseMaxValueBytes("  "));
        assertEquals(CouchbaseRepository.DEFAULT_MAX_VALUE_BYTES, CouchbaseRepository.parseMaxValueBytes("not-a-number"));
        assertEquals(20L * 1024 * 1024, CouchbaseRepository.DEFAULT_MAX_VALUE_BYTES);
    }

    @Test
    void honorsExplicitPositiveLimit() {
        assertEquals(4096L, CouchbaseRepository.parseMaxValueBytes("4096"));
        assertEquals(1L, CouchbaseRepository.parseMaxValueBytes(" 1 "));
        assertEquals(52428800L, CouchbaseRepository.parseMaxValueBytes("52428800"));
    }

    @Test
    void zeroOrNegativeDisablesTheLimit() {
        assertEquals(0L, CouchbaseRepository.parseMaxValueBytes("0"));
        assertEquals(0L, CouchbaseRepository.parseMaxValueBytes("-1"));
        assertEquals(0L, CouchbaseRepository.parseMaxValueBytes("-100000"));
    }
}
