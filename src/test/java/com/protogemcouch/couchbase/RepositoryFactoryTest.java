package com.protogemcouch.couchbase;

import com.protogemcouch.testsupport.FakeRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RepositoryFactoryTest {

    @Test
    void close_does_nothing_for_non_couchbase_repository() {
        Repository repository = new FakeRepository();

        assertDoesNotThrow(() -> RepositoryFactory.close(repository));
    }

    @Test
    void close_does_nothing_for_null_repository() {
        assertDoesNotThrow(() -> RepositoryFactory.close(null));
    }
}