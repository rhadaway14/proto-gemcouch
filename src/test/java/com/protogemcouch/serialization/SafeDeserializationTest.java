package com.protogemcouch.serialization;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the CWE-502 guard on the shim's Java deserialization of untrusted client values: only JDK
 * container/scalar types are deserialized; any application class (where gadget chains live) is rejected
 * and the value is left to be preserved opaquely.
 */
class SafeDeserializationTest {

    /** A non-JDK Serializable class standing in for an application/gadget class — must be rejected. */
    private static final class AppClass implements Serializable {
        private static final long serialVersionUID = 1L;
        @SuppressWarnings("unused")
        private final String payload = "x";
    }

    private static byte[] javaSerialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    @Test
    void deserializesAllowedJdkMap() throws IOException {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put("a", "1");
        map.put("b", "2");
        byte[] bytes = javaSerialize(map);

        Object result = SafeDeserialization.deserialize(bytes, 0, bytes.length);

        assertInstanceOf(Map.class, result, "an allowlisted JDK map deserializes");
        assertEquals(map, result);
    }

    @Test
    void rejectsNonJdkApplicationClass() throws IOException {
        byte[] bytes = javaSerialize(new AppClass());

        assertNull(SafeDeserialization.deserialize(bytes, 0, bytes.length),
                "a non-JDK application class must be rejected (gadget guard), yielding null");
    }

    @Test
    void rejectsAJdkMapContainingANonJdkValue() throws IOException {
        // The classic gadget shape: a JDK container whose element is an application object. The filter
        // must reject during deserialization (the inner class load), so the whole readObject fails.
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("ok", "1");
        map.put("danger", new AppClass());
        byte[] bytes = javaSerialize(map);

        assertNull(SafeDeserialization.deserialize(bytes, 0, bytes.length),
                "a JDK map carrying a non-JDK element must be rejected");
    }
}
