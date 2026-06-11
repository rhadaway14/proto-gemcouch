package com.protogemcouch.ops;

import com.protogemcouch.util.ByteUtils;
import org.apache.geode.DataSerializer;
import org.apache.geode.pdx.internal.PdxType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Validates the reverse PDX lookup the shim serves for GET_PDX_TYPE_BY_ID (92): the kept type must
 * re-serialize to a {@link PdxType} stamped with the assigned id and the original field layout, so a
 * second client can decode a PDX value (e.g. a CQ event value) it did not itself write. Uses real
 * PdxType bytes captured from a Geode 1.15 server (a {@code demo.Order} with String {@code status} and
 * int {@code amount}) — the same blob as {@link PdxFieldAccessorTest}.
 */
class PdxTypeRegistryTest {

    private static final byte[] PDX_TYPE = ByteUtils.hex(
            "2d2b5700256f72672e6170616368652e67656f64652e7064782e696e7465726e616c2e5064785479706557000a64656d6f"
                    + "2e4f726465720000000000000000000257000673746174757300000000000000000900000000ffffffff0057000661"
                    + "6d6f756e74000000010000000004fffffffcffffffff00");

    @Test
    void serializedTypeRoundTripsWithAssignedIdAndFields() throws Exception {
        PdxTypeRegistry registry = new PdxTypeRegistry();
        int typeId = registry.getOrCreateTypeId(PDX_TYPE);

        byte[] serialized = registry.serializedPdxType(typeId);
        assertNotNull(serialized, "the kept type re-serializes for the reverse-lookup reply");

        PdxType decoded = (PdxType) DataSerializer.readObject(
                new DataInputStream(new ByteArrayInputStream(serialized)));
        assertEquals(typeId, decoded.getTypeId(),
                "the served type carries the shim-assigned id so the client caches it correctly");
        assertEquals("demo.Order", decoded.getClassName());
        assertNotNull(decoded.getPdxField("status"), "field layout survives the round-trip");
        assertNotNull(decoded.getPdxField("amount"));
    }

    @Test
    void serializedTypeIsNullForUnknownId() {
        PdxTypeRegistry registry = new PdxTypeRegistry();
        registry.getOrCreateTypeId(PDX_TYPE);
        assertNull(registry.serializedPdxType(999), "unknown type id has no serialized form");
    }
}
