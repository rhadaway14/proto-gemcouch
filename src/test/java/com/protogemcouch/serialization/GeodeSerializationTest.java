package com.protogemcouch.serialization;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeodeSerializationTest {

    @Test
    void serialize_and_deserialize_string_round_trip() {
        String original = "hello-world";
        byte[] bytes = GeodeSerialization.serializeString(original);
        String decoded = GeodeSerialization.deserializeString(bytes);

        assertEquals(original, decoded);
    }

    @Test
    void serialize_and_deserialize_boolean_round_trip() {
        byte[] bytes = GeodeSerialization.serializeBoolean(true);
        Object decoded = GeodeSerialization.deserializeObject(bytes);

        assertEquals(Boolean.TRUE, decoded);
    }

    @Test
    void serialize_and_deserialize_object_round_trip() {
        List<String> original = Arrays.asList("a", "b", "c");
        byte[] bytes = GeodeSerialization.serializeObject(original);
        Object decoded = GeodeSerialization.deserializeObject(bytes);

        assertInstanceOf(List.class, decoded);
        assertEquals(original, decoded);
    }

    @Test
    void deserializeGetAllKeys_returns_list_of_strings() {
        List<String> original = Arrays.asList("k1", "k2", "k3");
        byte[] bytes = GeodeSerialization.serializeObject(original);

        List<String> decoded = GeodeSerialization.deserializeGetAllKeys(bytes);

        assertEquals(original, decoded);
    }
}