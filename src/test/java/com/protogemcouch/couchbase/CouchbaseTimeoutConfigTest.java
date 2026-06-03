package com.protogemcouch.couchbase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CouchbaseTimeoutConfigTest {

    @Test
    void usesDefaultForUnsetOrInvalidValues() {
        assertEquals(5000L, CouchbaseRepository.parsePositiveLongOrDefault(null, 5000L));
        assertEquals(5000L, CouchbaseRepository.parsePositiveLongOrDefault("", 5000L));
        assertEquals(5000L, CouchbaseRepository.parsePositiveLongOrDefault("abc", 5000L));
        assertEquals(5000L, CouchbaseRepository.parsePositiveLongOrDefault("0", 5000L));
        assertEquals(5000L, CouchbaseRepository.parsePositiveLongOrDefault("-100", 5000L));
    }

    @Test
    void parsesPositiveOverride() {
        assertEquals(2500L, CouchbaseRepository.parsePositiveLongOrDefault("2500", 5000L));
    }
}
