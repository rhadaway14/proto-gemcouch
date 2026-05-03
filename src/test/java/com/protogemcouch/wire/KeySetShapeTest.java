package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.HexFormat;

class KeySetShapeTest {

    @Test
    void printStringSetWireShape() throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("key-1");
        keys.add("key-2");
        keys.add("key-3");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(bytes)) {
            DataSerializer.writeObject(keys, out);
        }

        System.out.println("KEY_SET_HEX_START");
        System.out.println(HexFormat.of().formatHex(bytes.toByteArray()));
        System.out.println("KEY_SET_HEX_END");
    }
}