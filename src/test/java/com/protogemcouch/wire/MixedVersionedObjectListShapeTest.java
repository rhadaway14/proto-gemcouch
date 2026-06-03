package com.protogemcouch.wire;

import com.protogemcouch.serialization.StoredValue;
import org.apache.geode.DataSerializer;
import org.apache.geode.internal.cache.tier.sockets.VersionedObjectList;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MixedVersionedObjectListShapeTest {

    @Test
    void mixed_string_and_integer_versioned_object_list_shape_should_deserialize() {
        List<String> keys = List.of(
                "mixed-string-1",
                "mixed-integer-1",
                "mixed-string-2",
                "mixed-integer-2"
        );

        Map<String, StoredValue> values = new LinkedHashMap<>();
        values.put("mixed-string-1", StoredValue.stringValue("value-1"));
        values.put("mixed-integer-1", StoredValue.integerValue(1001));
        values.put("mixed-string-2", StoredValue.stringValue("value-2"));
        values.put("mixed-integer-2", StoredValue.integerValue(2002));

        byte[] response = GemResponseWriter.buildGetAllChunkedResponse(100, keys, values);

        /*
         * buildGetAllChunkedResponse creates:
         *
         * int messageType
         * int numberOfParts
         * int txId
         * int chunkLength
         * byte lastChunkFlag
         * int partLength
         * byte partType
         * payload
         *
         * Payload starts after 4 + 4 + 4 + 4 + 1 + 4 + 1 = 22 bytes.
         */
        int payloadOffset = 22;
        int payloadLength = response.length - payloadOffset;

        byte[] payload = new byte[payloadLength];
        System.arraycopy(response, payloadOffset, payload, 0, payloadLength);

        System.out.println("MIXED_VERSIONED_OBJECT_LIST_HEX_START");
        System.out.println(bytesToHex(payload));
        System.out.println("MIXED_VERSIONED_OBJECT_LIST_HEX_END");

        assertDoesNotThrow(() -> {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
                Object decoded = DataSerializer.readObject(in);
                assertInstanceOf(VersionedObjectList.class, decoded);
            }
        });
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder out = new StringBuilder();

        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xff));
        }

        return out.toString();
    }
}