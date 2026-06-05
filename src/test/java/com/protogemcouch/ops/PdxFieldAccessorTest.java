package com.protogemcouch.ops;

import com.protogemcouch.util.ByteUtils;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
