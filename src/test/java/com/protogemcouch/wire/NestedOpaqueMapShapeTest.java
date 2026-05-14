package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedOpaqueMapShapeTest {

    @Test
    void hashMapWithNestedUuidShape() throws IOException {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "uuid-map");
        value.put("id", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        printShape("HASH_MAP_WITH_NESTED_UUID", value);
    }

    @Test
    void hashMapWithNestedBigIntegerShape() throws IOException {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "big-integer-map");
        value.put("amount", new BigInteger("123456789012345678901234567890"));

        printShape("HASH_MAP_WITH_NESTED_BIG_INTEGER", value);
    }

    @Test
    void hashMapWithNestedBigDecimalShape() throws IOException {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "big-decimal-map");
        value.put("amount", new BigDecimal("1234567890.123456789"));

        printShape("HASH_MAP_WITH_NESTED_BIG_DECIMAL", value);
    }

    @Test
    void hashMapWithNestedEnumShape() throws IOException {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "enum-map");
        value.put("status", DemoStatus.ACTIVE);

        printShape("HASH_MAP_WITH_NESTED_ENUM", value);
    }

    @Test
    void hashMapWithNestedObjectArrayShape() throws IOException {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "object-array-map");
        value.put("items", new Object[] {
                "one",
                Integer.valueOf(42),
                Boolean.TRUE,
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        });

        printShape("HASH_MAP_WITH_NESTED_OBJECT_ARRAY", value);
    }

    @Test
    void hashMapWithNestedArrayListObjectShape() throws IOException {
        ArrayList<Object> list = new ArrayList<>();
        list.add("one");
        list.add(Integer.valueOf(42));
        list.add(Boolean.TRUE);
        list.add(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "object-array-list-map");
        value.put("items", list);

        printShape("HASH_MAP_WITH_NESTED_ARRAY_LIST_OBJECT", value);
    }

    @Test
    void hashMapWithNestedSerializablePojoShape() throws IOException {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "pojo-map");
        value.put("profile", new CustomerProfile(
                "customer-1",
                "Rob",
                42,
                true
        ));

        printShape("HASH_MAP_WITH_NESTED_SERIALIZABLE_POJO", value);
    }

    @Test
    void hashMapWithNestedIntegerWrapperArrayShape() throws IOException {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "integer-wrapper-array-map");
        value.put("items", new Integer[] {
                Integer.valueOf(1),
                Integer.valueOf(42),
                Integer.valueOf(-7),
                null,
                Integer.MAX_VALUE,
                Integer.MIN_VALUE
        });

        printShape("HASH_MAP_WITH_NESTED_INTEGER_WRAPPER_ARRAY", value);
    }

    @Test
    void hashMapWithNestedUuidArrayShape() throws IOException {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "uuid-array-map");
        value.put("items", new UUID[] {
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000001")
        });

        printShape("HASH_MAP_WITH_NESTED_UUID_ARRAY", value);
    }

    @Test
    void hashMapWithNestedBigDecimalArrayShape() throws IOException {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "big-decimal-array-map");
        value.put("items", new BigDecimal[] {
                new BigDecimal("1.00"),
                new BigDecimal("42.42"),
                null,
                new BigDecimal("-7.25"),
                new BigDecimal("1234567890.123456789")
        });

        printShape("HASH_MAP_WITH_NESTED_BIG_DECIMAL_ARRAY", value);
    }

    @Test
    void hashMapWithNestedJavaTimeValuesShape() throws IOException {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "java-time-map");
        value.put("instant", Instant.parse("2026-05-13T20:37:37Z"));
        value.put("localDate", LocalDate.of(2026, 5, 13));
        value.put("localDateTime", LocalDateTime.of(2026, 5, 13, 20, 37, 37));

        printShape("HASH_MAP_WITH_NESTED_JAVA_TIME_VALUES", value);
    }

    @Test
    void hashMapWithNestedJavaTimeArraysShape() throws IOException {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "java-time-array-map");
        value.put("instants", new Instant[] {
                Instant.parse("2026-05-13T20:37:37Z"),
                null,
                Instant.EPOCH
        });
        value.put("localDates", new LocalDate[] {
                LocalDate.of(2026, 5, 13),
                null,
                LocalDate.of(1970, 1, 1)
        });
        value.put("localDateTimes", new LocalDateTime[] {
                LocalDateTime.of(2026, 5, 13, 20, 37, 37),
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0, 0)
        });

        printShape("HASH_MAP_WITH_NESTED_JAVA_TIME_ARRAYS", value);
    }

    @Test
    void hashMapWithMixedNestedOpaqueValuesShape() throws IOException {
        ArrayList<Object> list = new ArrayList<>();
        list.add("list-item");
        list.add(Integer.valueOf(42));
        list.add(Boolean.TRUE);
        list.add(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "mixed-nested-opaque-map");
        value.put("uuid", UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        value.put("bigInteger", new BigInteger("123456789012345678901234567890"));
        value.put("bigDecimal", new BigDecimal("1234567890.123456789"));
        value.put("status", DemoStatus.ACTIVE);
        value.put("objectArray", new Object[] {
                "one",
                Integer.valueOf(42),
                Boolean.TRUE
        });
        value.put("objectArrayList", list);
        value.put("integerArray", new Integer[] {
                Integer.valueOf(1),
                Integer.valueOf(42),
                null,
                Integer.valueOf(-7)
        });
        value.put("uuidArray", new UUID[] {
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000001")
        });
        value.put("instant", Instant.parse("2026-05-13T20:37:37Z"));
        value.put("localDate", LocalDate.of(2026, 5, 13));
        value.put("localDateTime", LocalDateTime.of(2026, 5, 13, 20, 37, 37));
        value.put("profile", new CustomerProfile(
                "customer-1",
                "Rob",
                42,
                true
        ));

        printShape("HASH_MAP_WITH_MIXED_NESTED_OPAQUE_VALUES", value);
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