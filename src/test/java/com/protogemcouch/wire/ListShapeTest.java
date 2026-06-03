package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

class ListShapeTest {

    @Test
    void printStringListWireShape() throws Exception {
        List<String> keys = new ArrayList<>();
        keys.add("key-1");
        keys.add("key-2");
        keys.add("key-3");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(bytes)) {
            DataSerializer.writeObject(keys, out);
        }

        System.out.println("LIST_HEX_START");
        System.out.println(HexFormat.of().formatHex(bytes.toByteArray()));
        System.out.println("LIST_HEX_END");
    }
}