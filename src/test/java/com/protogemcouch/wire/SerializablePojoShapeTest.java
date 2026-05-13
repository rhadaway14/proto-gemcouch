package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializablePojoShapeTest {

    @Test
    void simple_serializable_pojo_shape_is_observed() throws Exception {
        CustomerProfile value = new CustomerProfile(
                "customer-1",
                "Rob",
                42,
                true
        );

        byte[] actual = serializeObject(value);

        printHex("SERIALIZABLE_POJO_SIMPLE_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void serializable_pojo_with_null_field_shape_is_observed() throws Exception {
        CustomerProfile value = new CustomerProfile(
                "customer-2",
                null,
                43,
                false
        );

        byte[] actual = serializeObject(value);

        printHex("SERIALIZABLE_POJO_WITH_NULL_FIELD_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void serializable_pojo_with_date_and_byte_array_shape_is_observed() throws Exception {
        CustomerProfileWithExtras value = new CustomerProfileWithExtras(
                "customer-3",
                "Rob",
                42,
                true,
                new Date(1_000L),
                new byte[] {0x01, 0x02, 0x03, 0x04, 0x05}
        );

        byte[] actual = serializeObject(value);

        printHex("SERIALIZABLE_POJO_WITH_DATE_AND_BYTE_ARRAY_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    @Test
    void serializable_pojo_with_nested_map_shape_is_observed() throws Exception {
        LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tier", "gold");
        attributes.put("score", Integer.valueOf(9001));
        attributes.put("active", Boolean.TRUE);
        attributes.put("lastSeen", new Date(1_000L));

        CustomerProfileWithAttributes value = new CustomerProfileWithAttributes(
                "customer-4",
                "Rob",
                attributes
        );

        byte[] actual = serializeObject(value);

        printHex("SERIALIZABLE_POJO_WITH_NESTED_MAP_HEX", actual);

        assertNotNull(actual);
        assertTrue(actual.length > 0);
    }

    private static byte[] serializeObject(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(bytes)) {
            DataSerializer.writeObject(value, out);
        }

        return bytes.toByteArray();
    }

    private static void printHex(String label, byte[] payload) {
        System.out.println(label + "_START");
        System.out.println(toHex(payload));
        System.out.println(label + "_END");
    }

    private static String toHex(byte[] payload) {
        StringBuilder out = new StringBuilder();

        for (byte b : payload) {
            out.append(String.format("%02x", b & 0xff));
        }

        return out.toString();
    }

    private static final class CustomerProfile implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String id;
        private final String name;
        private final int age;
        private final boolean active;

        private CustomerProfile(String id, String name, int age, boolean active) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.active = active;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof CustomerProfile that)) {
                return false;
            }

            return age == that.age
                    && active == that.active
                    && Objects.equals(id, that.id)
                    && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, age, active);
        }
    }

    private static final class CustomerProfileWithExtras implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String id;
        private final String name;
        private final int age;
        private final boolean active;
        private final Date createdAt;
        private final byte[] payload;

        private CustomerProfileWithExtras(
                String id,
                String name,
                int age,
                boolean active,
                Date createdAt,
                byte[] payload
        ) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.active = active;
            this.createdAt = createdAt == null ? null : new Date(createdAt.getTime());
            this.payload = payload == null ? null : payload.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof CustomerProfileWithExtras that)) {
                return false;
            }

            return age == that.age
                    && active == that.active
                    && Objects.equals(id, that.id)
                    && Objects.equals(name, that.name)
                    && Objects.equals(createdAt, that.createdAt)
                    && java.util.Arrays.equals(payload, that.payload);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(id, name, age, active, createdAt);
            result = 31 * result + java.util.Arrays.hashCode(payload);
            return result;
        }
    }

    private static final class CustomerProfileWithAttributes implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String id;
        private final String name;
        private final LinkedHashMap<String, Object> attributes;

        private CustomerProfileWithAttributes(
                String id,
                String name,
                LinkedHashMap<String, Object> attributes
        ) {
            this.id = id;
            this.name = name;
            this.attributes = attributes == null ? null : new LinkedHashMap<>(attributes);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof CustomerProfileWithAttributes that)) {
                return false;
            }

            return Objects.equals(id, that.id)
                    && Objects.equals(name, that.name)
                    && Objects.equals(attributes, that.attributes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, attributes);
        }
    }
}