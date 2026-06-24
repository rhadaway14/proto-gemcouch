package com.protogemcouch.ops;

import com.protogemcouch.util.ByteUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates PDX field reading using real PdxType + instance bytes captured from a Geode 1.15 server
 * (a {@code demo.Order} with String {@code status} and int {@code amount}). The instance's type id is
 * the shim-assigned id (a real client embeds whatever id the server returns from type registration).
 */
class PdxFieldAccessorTest {

    // PdxType for demo.Order { String status; int amount; } (the opcode-93 part payload).
    private static final byte[] PDX_TYPE = ByteUtils.hex(
            "2d2b5700256f72672e6170616368652e67656f64652e7064782e696e7465726e616c2e5064785479706557000a64656d6f"
                    + "2e4f726465720000000000000000000257000673746174757300000000000000000900000000ffffffff0057000661"
                    + "6d6f756e74000000010000000004fffffffcffffffff00");
    // PDX field data for status="closed", amount=10.
    private static final byte[] FIELD_DATA = ByteUtils.hex("570006636c6f7365640000000a");

    private static byte[] pdxInstance(int typeId, byte[] fieldData) {
        ByteBuffer buffer = ByteBuffer.allocate(9 + fieldData.length);
        buffer.put((byte) 0x5d);
        buffer.putInt(fieldData.length);
        buffer.putInt(typeId);
        buffer.put(fieldData);
        return buffer.array();
    }

    @Test
    void readsScalarFieldsByName() {
        PdxTypeRegistry registry = new PdxTypeRegistry();
        int typeId = registry.getOrCreateTypeId(PDX_TYPE);
        byte[] instance = pdxInstance(typeId, FIELD_DATA);

        assertEquals("closed", PdxFieldAccessor.read(instance, registry, "status"));
        assertEquals(10, PdxFieldAccessor.read(instance, registry, "amount"));
    }

    @Test
    void readScalarFieldsExtractsAllScalarsForTheSidecar() {
        PdxTypeRegistry registry = new PdxTypeRegistry();
        int typeId = registry.getOrCreateTypeId(PDX_TYPE);
        byte[] instance = pdxInstance(typeId, FIELD_DATA);

        Map<String, Object> fields = PdxFieldAccessor.readScalarFields(instance, registry);
        assertEquals("closed", fields.get("status"));
        assertEquals(10, fields.get("amount"));
        assertEquals(2, fields.size(), "both scalar fields extracted");
    }

    @Test
    void readScalarFieldsReturnsEmptyForUnknownTypeOrNonPdx() {
        PdxTypeRegistry registry = new PdxTypeRegistry();
        int typeId = registry.getOrCreateTypeId(PDX_TYPE);

        assertTrue(PdxFieldAccessor.readScalarFields(pdxInstance(999, FIELD_DATA), registry).isEmpty(),
                "unknown type id -> no sidecar");
        assertTrue(PdxFieldAccessor.readScalarFields(new byte[] {0x01, 0x02}, registry).isEmpty(),
                "not a PDX instance -> no sidecar");
    }

    @Test
    void returnsNullForMissingFieldUnknownTypeOrNonPdx() {
        PdxTypeRegistry registry = new PdxTypeRegistry();
        int typeId = registry.getOrCreateTypeId(PDX_TYPE);

        assertNull(PdxFieldAccessor.read(pdxInstance(typeId, FIELD_DATA), registry, "missing"),
                "unknown field");
        assertNull(PdxFieldAccessor.read(pdxInstance(999, FIELD_DATA), registry, "status"),
                "unknown type id");
        assertNull(PdxFieldAccessor.read(new byte[] {0x01, 0x02}, registry, "status"),
                "not a PDX instance");
    }

    // --- OBJECT_ARRAY element walker (parseObjectArrayElements) ---
    // Synthetic 0x5d-framed elements are sufficient: the parser slices by the self-describing framing
    // and never deserializes against a PdxType registry.

    /**
     * A {@code writeObjectArray} form (count <= 252): single-byte length, the real component-type header
     * (DSCODE.CLASS 0x2b + {@code org.apache.geode.pdx.PdxInstance} as a 0x57 Geode string), then the
     * element byte forms — matching what a Geode client serializes for a {@code PdxInstance[]} field.
     */
    private static byte[] objectArray(byte[]... elements) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(elements.length & 0xff); // single-byte length form
        out.writeBytes(componentTypeHeader("org.apache.geode.pdx.PdxInstance"));
        for (byte[] e : elements) {
            out.writeBytes(e);
        }
        return out.toByteArray();
    }

    /** DSCODE.CLASS (0x2b) + the class name as a DSCODE.STRING (0x57, unsigned-short length). */
    private static byte[] componentTypeHeader(String className) {
        byte[] name = className.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x2b);
        out.write(0x57);
        out.write((name.length >>> 8) & 0xff);
        out.write(name.length & 0xff);
        out.writeBytes(name);
        return out.toByteArray();
    }

    @Test
    void parsesHomogeneousPdxObjectArrayElements() {
        byte[] e0 = pdxInstance(7, new byte[] {1, 2, 3});
        byte[] e1 = pdxInstance(8, new byte[] {4, 5});
        List<Object> elements = PdxFieldAccessor.parseObjectArrayElements(objectArray(e0, e1));

        assertNotNull(elements);
        assertEquals(2, elements.size());
        assertArrayEquals(e0, (byte[]) elements.get(0), "first element sliced byte-for-byte");
        assertArrayEquals(e1, (byte[]) elements.get(1), "second element sliced byte-for-byte");
    }

    @Test
    void nullObjectArrayElementBecomesNullEntry() {
        byte[] e0 = pdxInstance(7, new byte[] {9});
        byte[] nullMarker = {0x29}; // DSCODE.NULL
        byte[] e2 = pdxInstance(9, new byte[] {});
        List<Object> elements = PdxFieldAccessor.parseObjectArrayElements(objectArray(e0, nullMarker, e2));

        assertEquals(3, elements.size());
        assertArrayEquals(e0, (byte[]) elements.get(0));
        assertNull(elements.get(1), "DSCODE.NULL element resolves to a null entry");
        assertArrayEquals(e2, (byte[]) elements.get(2));
    }

    @Test
    void emptyObjectArrayYieldsEmptyList() {
        List<Object> elements = PdxFieldAccessor.parseObjectArrayElements(objectArray());
        assertNotNull(elements);
        assertTrue(elements.isEmpty());
    }

    @Test
    void nullObjectArrayMarkerYieldsEmptyList() {
        List<Object> elements = PdxFieldAccessor.parseObjectArrayElements(new byte[] {(byte) 0xff});
        assertNotNull(elements);
        assertTrue(elements.isEmpty(), "the -1 (null array) length yields nothing to navigate");
    }

    @Test
    void truncatedObjectArrayElementStopsSafely() {
        // Declares 2 elements, but the second PDX frame is cut off after its header.
        byte[] e0 = pdxInstance(7, new byte[] {1});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(2);
        out.writeBytes(e0);
        out.write(0x5d);
        out.writeBytes(intBytes(100)); // declares 100 data bytes that are not present
        out.writeBytes(intBytes(8));
        List<Object> elements = PdxFieldAccessor.parseObjectArrayElements(out.toByteArray());

        assertNotNull(elements, "must not throw on truncation");
        assertEquals(1, elements.size(), "only the intact element is returned; the truncated one is dropped");
        assertArrayEquals(e0, (byte[]) elements.get(0));
    }

    @Test
    void unknownObjectArrayElementDscodeStopsWalk() {
        byte[] e0 = pdxInstance(7, new byte[] {1});
        byte[] unknown = {0x57}; // a non-PDX, non-null DSCODE whose length we cannot compute
        List<Object> elements = PdxFieldAccessor.parseObjectArrayElements(objectArray(e0, unknown));

        assertEquals(1, elements.size(),
                "collection stops at the first element type we cannot length-walk; later indices are ABSENT");
        assertArrayEquals(e0, (byte[]) elements.get(0));
    }

    /** A DSCODE.STRING element: 0x57 then a writeUTF blob (2-byte length + modified UTF-8 bytes). */
    private static byte[] stringElement(String s) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x57);
            java.io.DataOutputStream dos = new java.io.DataOutputStream(out);
            dos.writeUTF(s);
            dos.flush();
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void parsesScalarStringObjectArrayElements() {
        // Object[] {"alice@x.com", "bob@x.com"} — scalar elements decode to Strings so IN can scan them.
        List<Object> elements = PdxFieldAccessor.parseObjectArrayElements(
                objectArray(stringElement("alice@x.com"), stringElement("bob@x.com")));

        assertEquals(List.of("alice@x.com", "bob@x.com"), elements,
                "string elements decode to comparable String values");
    }

    @Test
    void parsesMixedPdxAndStringObjectArrayElements() {
        byte[] pdx = pdxInstance(7, new byte[] {1, 2});
        List<Object> elements = PdxFieldAccessor.parseObjectArrayElements(
                objectArray(stringElement("x"), pdx, stringElement("y")));

        assertEquals(3, elements.size());
        assertEquals("x", elements.get(0));
        assertArrayEquals(pdx, (byte[]) elements.get(1), "a nested-PDX element stays a raw byte slice");
        assertEquals("y", elements.get(2));
    }

    @Test
    void parsesObjectArrayWithoutComponentHeader() {
        // Some forms omit the component-type header and place elements directly after the length.
        byte[] e0 = pdxInstance(7, new byte[] {1, 2});
        byte[] e1 = pdxInstance(8, new byte[] {3});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(2);
        out.writeBytes(e0);
        out.writeBytes(e1);
        List<Object> elements = PdxFieldAccessor.parseObjectArrayElements(out.toByteArray());

        assertEquals(2, elements.size());
        assertArrayEquals(e0, (byte[]) elements.get(0));
        assertArrayEquals(e1, (byte[]) elements.get(1));
    }

    @Test
    void malformedObjectArrayLengthPrefixReturnsNull() {
        assertNull(PdxFieldAccessor.parseObjectArrayElements(new byte[0]), "empty input");
        assertNull(PdxFieldAccessor.parseObjectArrayElements(null), "null input");
    }

    private static byte[] intBytes(int v) {
        return new byte[] {
                (byte) ((v >>> 24) & 0xff), (byte) ((v >>> 16) & 0xff),
                (byte) ((v >>> 8) & 0xff), (byte) (v & 0xff)};
    }
}
