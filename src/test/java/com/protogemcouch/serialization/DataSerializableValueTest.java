package com.protogemcouch.serialization;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for opaque preservation of a Geode {@code DataSerializable} value (DSCODE
 * {@code 0x2d}). The shim cannot load the user's class, so it must preserve the payload verbatim and
 * keep it out of the raw-byte-array path (which would re-frame it as a {@code byte[]} on read).
 */
class DataSerializableValueTest {

    /** Build a {@code 2d 2b 57 <len2> <class> <toData>} DataSerializable payload. */
    private static byte[] dataSerializablePayload(String className, byte[] toData) {
        byte[] name = className.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x2d);                 // DSCODE.DATA_SERIALIZABLE
        out.write(0x2b);                 // DSCODE.CLASS
        out.write(0x57);                 // string code
        out.write((name.length >>> 8) & 0xff);
        out.write(name.length & 0xff);
        out.write(name, 0, name.length);
        out.write(toData, 0, toData.length);
        return out.toByteArray();
    }

    @Test
    void dataSerializablePayloadIsPreservedOpaquelyWithParsedClassName() {
        byte[] payload = dataSerializablePayload(
                "com.example.Order", new byte[] {0x57, 0x00, 0x03, 'a', 'b', 'c', 0x00, 0x00, 0x00, 0x07});

        ValueDecoding.OpaqueGeodeValue opaque = ValueDecoding.decodeOpaqueStandaloneUtilityValue(payload);

        assertNotNull(opaque, "0x2d DataSerializable payload should be preserved opaquely");
        assertEquals("dataSerializable:com.example.Order", opaque.typeName());
        assertArrayEquals(payload, opaque.encodedValue(), "the whole payload must be preserved verbatim");
    }

    @Test
    void dataSerializableIsNotTreatedAsARawByteArray() {
        byte[] payload = dataSerializablePayload("com.example.Order", new byte[] {0x01, 0x02});
        assertNull(ValueDecoding.decodeRawByteArrayValue(payload),
                "0x2d must be excluded from the raw-byte-array path so it round-trips as an object");
    }

    @Test
    void malformedDataSerializablePrefixFallsBackToGenericTypeName() {
        // 0x2d present but not the expected 2b 57 class prefix: still preserved, generic type name.
        byte[] payload = {0x2d, 0x28, 0x05, 0x01, 0x02, 0x03};
        ValueDecoding.OpaqueGeodeValue opaque = ValueDecoding.decodeOpaqueStandaloneUtilityValue(payload);

        assertNotNull(opaque);
        assertEquals("dataSerializable", opaque.typeName());
        assertTrue(payload.length == opaque.encodedValue().length);
        assertArrayEquals(payload, opaque.encodedValue());
    }
}
