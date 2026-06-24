package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.pdx.PdxInstance;
import org.apache.geode.pdx.PdxInstanceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PdxShapeTest {

    private Cache cache;

    @AfterEach
    void tearDown() {
        if (cache != null && !cache.isClosed()) {
            cache.close();
        }
    }

    @Test
    void simplePdxInstanceShape() throws IOException {
        PdxInstance value = pdxFactory("com.example.SimplePdx")
                .writeString("id", "customer-1")
                .writeString("name", "Rob")
                .writeInt("age", 42)
                .writeBoolean("active", true)
                .create();

        printShape("SIMPLE_PDX_INSTANCE", value);
    }

    @Test
    void pdxInstanceWithPrimitiveAndStringArrayFieldsShape() throws IOException {
        PdxInstance value = pdxFactory("com.example.PdxWithArrays")
                .writeString("id", "array-doc-1")
                .writeIntArray("scores", new int[] {1, 42, -7, Integer.MAX_VALUE, Integer.MIN_VALUE})
                .writeLongArray("longValues", new long[] {1L, 42L, -7L, 9_876_543_210L})
                .writeBooleanArray("flags", new boolean[] {true, false, true})
                .writeDoubleArray("measurements", new double[] {1.0d, 7.25d, -7.25d})
                .writeStringArray("tags", new String[] {"one", null, "three"})
                .create();

        printShape("PDX_INSTANCE_WITH_PRIMITIVE_AND_STRING_ARRAY_FIELDS", value);
    }

    @Test
    void pdxInstanceWithObjectArrayFieldShape() throws IOException {
        Object[] items = new Object[] {
                "one",
                Integer.valueOf(42),
                Boolean.TRUE,
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        };

        PdxInstance value = pdxFactory("com.example.PdxWithObjectArray")
                .writeString("id", "object-array-doc-1")
                .writeObjectArray("items", items)
                .create();

        printShape("PDX_INSTANCE_WITH_OBJECT_ARRAY_FIELD", value);
    }

    @Test
    void pdxInstanceWithArrayListObjectFieldShape() throws IOException {
        ArrayList<Object> items = new ArrayList<>();
        items.add("one");
        items.add(Integer.valueOf(42));
        items.add(Boolean.TRUE);
        items.add(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        PdxInstance value = pdxFactory("com.example.PdxWithArrayListObject")
                .writeString("id", "array-list-doc-1")
                .writeObject("items", items)
                .create();

        printShape("PDX_INSTANCE_WITH_ARRAY_LIST_OBJECT_FIELD", value);
    }

    @Test
    void pdxInstanceWithNestedMapFieldShape() throws IOException {
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tier", "gold");
        attributes.put("score", Integer.valueOf(9001));
        attributes.put("active", Boolean.TRUE);
        attributes.put("uuid", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        attributes.put("createdAt", new Date(1_000L));

        PdxInstance value = pdxFactory("com.example.PdxWithNestedMap")
                .writeString("id", "nested-map-doc-1")
                .writeObject("attributes", attributes)
                .create();

        printShape("PDX_INSTANCE_WITH_NESTED_MAP_FIELD", value);
    }

    @Test
    void pdxInstanceWithStandaloneUtilityFieldsShape() throws IOException {
        PdxInstance value = pdxFactory("com.example.PdxWithUtilityFields")
                .writeString("id", "utility-doc-1")
                .writeObject("uuid", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
                .writeObject("bigInteger", new BigInteger("123456789012345678901234567890"))
                .writeObject("bigDecimal", new BigDecimal("1234567890.123456789"))
                .writeObject("status", DemoStatus.ACTIVE)
                .create();

        printShape("PDX_INSTANCE_WITH_STANDALONE_UTILITY_FIELDS", value);
    }

    @Test
    void pdxInstanceWithJavaTimeFieldsShape() throws IOException {
        PdxInstance value = pdxFactory("com.example.PdxWithJavaTimeFields")
                .writeString("id", "java-time-doc-1")
                .writeObject("instant", Instant.parse("2026-05-13T20:37:37Z"))
                .writeObject("localDate", LocalDate.of(2026, 5, 13))
                .writeObject("localDateTime", LocalDateTime.of(2026, 5, 13, 20, 37, 37))
                .create();

        printShape("PDX_INSTANCE_WITH_JAVA_TIME_FIELDS", value);
    }

    @Test
    void pdxInstanceWithNestedSerializablePojoFieldShape() throws IOException {
        PdxInstance value = pdxFactory("com.example.PdxWithPojo")
                .writeString("id", "pojo-doc-1")
                .writeObject("profile", new CustomerProfile(
                        "customer-1",
                        "Rob",
                        42,
                        true
                ))
                .create();

        printShape("PDX_INSTANCE_WITH_NESTED_SERIALIZABLE_POJO_FIELD", value);
    }

    @Test
    void pdxInstanceWithMixedNestedValuesShape() throws IOException {
        ArrayList<Object> list = new ArrayList<>();
        list.add("list-item");
        list.add(Integer.valueOf(42));
        list.add(Boolean.TRUE);
        list.add(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("tier", "gold");
        map.put("score", Integer.valueOf(9001));
        map.put("uuid", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        map.put("createdAt", new Date(1_000L));

        PdxInstance value = pdxFactory("com.example.PdxWithMixedNestedValues")
                .writeString("id", "mixed-doc-1")
                .writeString("name", "Rob")
                .writeInt("age", 42)
                .writeBoolean("active", true)
                .writeObject("uuid", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
                .writeObject("bigInteger", new BigInteger("123456789012345678901234567890"))
                .writeObject("bigDecimal", new BigDecimal("1234567890.123456789"))
                .writeObject("status", DemoStatus.ACTIVE)
                .writeObject("instant", Instant.parse("2026-05-13T20:37:37Z"))
                .writeObject("localDate", LocalDate.of(2026, 5, 13))
                .writeObject("localDateTime", LocalDateTime.of(2026, 5, 13, 20, 37, 37))
                .writeObjectArray("objectArray", new Object[] {
                        "one",
                        Integer.valueOf(42),
                        Boolean.TRUE
                })
                .writeObject("objectArrayList", list)
                .writeObject("attributes", map)
                .writeObject("profile", new CustomerProfile(
                        "customer-1",
                        "Rob",
                        42,
                        true
                ))
                .create();

        printShape("PDX_INSTANCE_WITH_MIXED_NESTED_VALUES", value);
    }

    @Test
    void pdxInstanceWithNestedPdxObjectArrayFieldShape() throws IOException {
        PdxInstance addr0 = pdxFactory("demo.Address").writeString("zip", "78701").create();
        PdxInstance addr1 = pdxFactory("demo.Address").writeString("zip", "73301").create();
        PdxInstance value = pdxFactory("demo.Customer")
                .writeString("status", "active")
                .writeObjectArray("addresses", new PdxInstance[] {addr0, addr1})
                .create();

        printShape("PDX_INSTANCE_WITH_NESTED_PDX_OBJECT_ARRAY_FIELD", value);
    }

    @Test
    void pdxInstanceWithStringObjectArrayFieldShape() throws IOException {
        PdxInstance value = pdxFactory("demo.WithStringContacts")
                .writeString("status", "active")
                .writeObjectArray("contacts", new Object[] {"alice@x.com", "bob@x.com"})
                .create();

        printShape("PDX_INSTANCE_WITH_STRING_OBJECT_ARRAY_FIELD", value);
    }

    private PdxInstanceFactory pdxFactory(String className) {
        return cache().createPdxInstanceFactory(className);
    }

    private Cache cache() {
        if (cache == null || cache.isClosed()) {
            cache = new CacheFactory()
                    .set("mcast-port", "0")
                    .set("locators", "")
                    .setPdxReadSerialized(true)
                    .create();
        }

        return cache;
    }

    private static void printShape(String label, Object value) throws IOException {
        byte[] encoded = geodeEncode(value);

        assertTrue(
                encoded.length > 0,
                label + " should produce a non-empty Geode DataSerializer payload"
        );

        System.out.println(label + "_HEX_START");
        System.out.println(toHex(encoded));
        System.out.println(label + "_HEX_END");

        System.out.println(label + "_SUMMARY_START");
        System.out.println("length=" + encoded.length);
        System.out.println("firstByte=0x" + toHexByte(encoded[0]));
        System.out.println("first16=" + firstBytesHex(encoded, 16));
        System.out.println(label + "_SUMMARY_END");
    }

    private static byte[] geodeEncode(Object value) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(baos)) {
            DataSerializer.writeObject(value, out);
        }

        return baos.toByteArray();
    }

    private static String firstBytesHex(byte[] bytes, int maxBytes) {
        int length = Math.min(bytes.length, maxBytes);
        StringBuilder sb = new StringBuilder(length * 2);

        for (int i = 0; i < length; i++) {
            sb.append(toHexByte(bytes[i]));
        }

        return sb.toString();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);

        for (byte b : bytes) {
            sb.append(toHexByte(b));
        }

        return sb.toString();
    }

    private static String toHexByte(byte value) {
        return String.format("%02x", value & 0xff);
    }

    private enum DemoStatus {
        ACTIVE,
        INACTIVE,
        PENDING
    }

    private record CustomerProfile(
            String id,
            String name,
            int age,
            boolean active
    ) implements Serializable {
        private static final long serialVersionUID = 1L;
    }
}