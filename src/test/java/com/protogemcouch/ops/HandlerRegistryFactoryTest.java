package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.wire.MessageTypes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HandlerRegistryFactoryTest {

    @Test
    void create_registers_expected_handlers() {
        Repository repository = new FakeRepository();

        OpcodeRegistry registry = HandlerRegistryFactory.create(repository);

        assertNotNull(registry.get(MessageTypes.GET));
        assertNotNull(registry.get(MessageTypes.PUT));
        assertNotNull(registry.get(MessageTypes.REMOVE));
        assertNotNull(registry.get(MessageTypes.CONTAINS_KEY));
        assertNotNull(registry.get(MessageTypes.KEY_SET));
        assertNotNull(registry.get(MessageTypes.PUT_ALL));
        assertNotNull(registry.get(MessageTypes.GET_CLIENT_PARTITION_ATTRIBUTES));
        assertNotNull(registry.get(MessageTypes.SIZE));
        assertNotNull(registry.get(MessageTypes.GET_ALL_70));
        assertNotNull(registry.get(MessageTypes.CONTROL));
        assertNotNull(registry.get(MessageTypes.PING));
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