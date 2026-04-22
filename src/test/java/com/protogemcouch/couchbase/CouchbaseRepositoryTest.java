package com.protogemcouch.couchbase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CouchbaseRepositoryTest {

    @Test
    void docId_builds_expected_format() {
        String result = CouchbaseRepository.docId("/helloWorld", "my-key");
        assertEquals("/helloWorld::my-key", result);
    }
}