package com.protogemcouch.serialization;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the fix for standalone JDK collections/maps that the structured decoders don't cover
 * (LinkedList/HashSet/TreeMap/…): they must be preserved as an {@code OPAQUE_GEODE_VALUE} (exact bytes
 * re-served so the client gets the container back) rather than leaking through the raw-byte-array
 * catch-all as a {@code byte[]}. Also guards the deserialization validation: a raw {@code byte[]} that
 * merely starts with a container marker byte must NOT be mis-tagged.
 */
class ContainerOpaqueDecodeTest {

    @Test
    void linkedListIsPreservedOpaquely() throws IOException {
        byte[] payload = geodeEncode(new LinkedList<>(List.of("a", "b", "c")));
        ValueDecoding.OpaqueGeodeValue opaque = ValueDecoding.decodeOpaqueStandaloneUtilityValue(payload);
        assertNotNull(opaque, "a serialized LinkedList must decode as an opaque Geode value, not a byte[]");
        assertArrayEquals(payload, opaque.encodedValue(), "the exact bytes are preserved for re-serving");
    }

    @Test
    void hashSetIsPreservedOpaquely() throws IOException {
        byte[] payload = geodeEncode(new HashSet<>(List.of("x", "y", "z")));
        assertNotNull(ValueDecoding.decodeOpaqueStandaloneUtilityValue(payload),
                "a serialized HashSet must decode as an opaque Geode value");
    }

    @Test
    void treeMapIsPreservedOpaquely() throws IOException {
        TreeMap<String, String> m = new TreeMap<>();
        m.put("a", "1");
        m.put("b", "2");
        byte[] payload = geodeEncode(m);
        assertNotNull(ValueDecoding.decodeOpaqueStandaloneUtilityValue(payload),
                "a serialized TreeMap must decode as an opaque Geode value");
    }

    @Test
    void rawByteArrayStartingWithAContainerMarkerIsNotMisTagged() {
        // 0x42 is the HASH_SET marker, but these bytes are not a valid serialized HashSet.
        byte[] notAContainer = {0x42, (byte) 0x99, (byte) 0xab, (byte) 0xcd, 0x01};
        assertNull(ValueDecoding.decodeOpaqueStandaloneUtilityValue(notAContainer),
                "a raw byte[] that merely starts with a container marker must fall through, not be mis-tagged");
    }

    private static byte[] geodeEncode(Object value) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            DataSerializer.writeObject(value, out);
        }
        return baos.toByteArray();
    }
}
