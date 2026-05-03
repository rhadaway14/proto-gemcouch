package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.HexFormat;

class IntegerShapeTest {

    @Test
    void printIntegerWireShape() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(bytes)) {
            DataSerializer.writeObject(Integer.valueOf(7), out);
        }

        System.out.println("INTEGER_HEX_START");
        System.out.println(HexFormat.of().formatHex(bytes.toByteArray()));
        System.out.println("INTEGER_HEX_END");
    }
}