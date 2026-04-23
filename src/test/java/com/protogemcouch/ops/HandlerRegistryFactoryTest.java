package com.protogemcouch.ops;

import com.protogemcouch.testsupport.FakeRepository;
import com.protogemcouch.wire.MessageTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class HandlerRegistryFactoryTest {

    @Test
    void create_registers_expected_handlers() {
        FakeRepository repository = new FakeRepository();

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
}