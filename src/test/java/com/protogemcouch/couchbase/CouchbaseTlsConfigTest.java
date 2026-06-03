package com.protogemcouch.couchbase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CouchbaseTlsConfigTest {

    @Test
    void secureSchemeEnablesTls() {
        assertTrue(CouchbaseRepository.couchbaseTlsEnabled("couchbases://couchbase", null));
        assertTrue(CouchbaseRepository.couchbaseTlsEnabled("COUCHBASES://host", null));
    }

    @Test
    void explicitFlagEnablesTls() {
        assertTrue(CouchbaseRepository.couchbaseTlsEnabled("couchbase://couchbase", "true"));
    }

    @Test
    void plaintextSchemeWithoutFlagDisablesTls() {
        assertFalse(CouchbaseRepository.couchbaseTlsEnabled("couchbase://couchbase", null));
        assertFalse(CouchbaseRepository.couchbaseTlsEnabled("couchbase://couchbase", "false"));
        assertFalse(CouchbaseRepository.couchbaseTlsEnabled(null, null));
    }
}
