package com.protogemcouch.couchbase;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RepositoryFactoryTest {

    @Test
    void close_does_nothing_for_non_couchbase_repository() {
        Repository repository = new FakeRepository();

        assertDoesNotThrow(() -> RepositoryFactory.close(repository));
    }

    @Test
    void close_does_nothing_for_null_repository_reference_pattern() {
        assertDoesNotThrow(() -> RepositoryFactory.close(null));
    }

    private static class FakeRepository implements Repository {
        @Override
        public String get(String docId) {
            return null;
        }

        @Override
        public Map<String, String> getAll(String region, List<String> keys) {
            return Map.of();
        }

        @Override
        public void put(String docId, String value) {
        }

        @Override
        public void remove(String docId) {
        }

        @Override
        public boolean containsKey(String docId) {
            return false;
        }

        @Override
        public boolean containsValueForKey(String docId) {
            return false;
        }

        @Override
        public int size(String region) {
            return 0;
        }

        @Override
        public List<String> keySet(String region) {
            return List.of();
        }
    }
}