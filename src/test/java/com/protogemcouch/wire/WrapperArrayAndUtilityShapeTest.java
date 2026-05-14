package com.protogemcouch.wire;

import org.apache.geode.DataSerializer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WrapperArrayAndUtilityShapeTest {

    @Test
    void integerArrayShape() throws IOException {
        Integer[] value = new Integer[] {
                Integer.valueOf(1),
                Integer.valueOf(42),
                Integer.valueOf(-7),
                null,
                Integer.MAX_VALUE,
                Integer.MIN_VALUE
        };

        printShape("INTEGER_ARRAY", value);
    }

    @Test
    void emptyIntegerArrayShape() throws IOException {
        Integer[] value = new Integer[] {};

        printShape("INTEGER_ARRAY_EMPTY", value);
    }

    @Test
    void longArrayShape() throws IOException {
        Long[] value = new Long[] {
                Long.valueOf(1L),
                Long.valueOf(42L),
                Long.valueOf(-7L),
                null,
                Long.valueOf(9_876_543_210L),
                Long.MAX_VALUE,
                Long.MIN_VALUE
        };

        printShape("LONG_ARRAY", value);
    }

    @Test
    void emptyLongArrayShape() throws IOException {
        Long[] value = new Long[] {};

        printShape("LONG_ARRAY_EMPTY", value);
    }

    @Test
    void booleanArrayShape() throws IOException {
        Boolean[] value = new Boolean[] {
                Boolean.TRUE,
                Boolean.FALSE,
                null,
                Boolean.TRUE
        };

        printShape("BOOLEAN_ARRAY", value);
    }

    @Test
    void emptyBooleanArrayShape() throws IOException {
        Boolean[] value = new Boolean[] {};

        printShape("BOOLEAN_ARRAY_EMPTY", value);
    }

    @Test
    void doubleArrayShape() throws IOException {
        Double[] value = new Double[] {
                Double.valueOf(1.0d),
                Double.valueOf(7.25d),
                Double.valueOf(-7.25d),
                null,
                Double.MAX_VALUE,
                Double.MIN_VALUE
        };

        printShape("DOUBLE_ARRAY", value);
    }

    @Test
    void emptyDoubleArrayShape() throws IOException {
        Double[] value = new Double[] {};

        printShape("DOUBLE_ARRAY_EMPTY", value);
    }

    @Test
    void uuidShape() throws IOException {
        UUID value = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        printShape("UUID", value);
    }

    @Test
    void uuidArrayShape() throws IOException {
        UUID[] value = new UUID[] {
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000001")
        };

        printShape("UUID_ARRAY", value);
    }

    @Test
    void bigDecimalShape() throws IOException {
        BigDecimal value = new BigDecimal("1234567890.123456789");

        printShape("BIG_DECIMAL", value);
    }

    @Test
    void bigDecimalArrayShape() throws IOException {
        BigDecimal[] value = new BigDecimal[] {
                new BigDecimal("1.00"),
                new BigDecimal("42.42"),
                null,
                new BigDecimal("-7.25"),
                new BigDecimal("1234567890.123456789")
        };

        printShape("BIG_DECIMAL_ARRAY", value);
    }

    @Test
    void bigIntegerShape() throws IOException {
        BigInteger value = new BigInteger("123456789012345678901234567890");

        printShape("BIG_INTEGER", value);
    }

    @Test
    void bigIntegerArrayShape() throws IOException {
        BigInteger[] value = new BigInteger[] {
                BigInteger.ONE,
                BigInteger.valueOf(42L),
                null,
                BigInteger.valueOf(-7L),
                new BigInteger("123456789012345678901234567890")
        };

        printShape("BIG_INTEGER_ARRAY", value);
    }

    @Test
    void enumShape() throws IOException {
        DemoStatus value = DemoStatus.ACTIVE;

        printShape("ENUM", value);
    }

    @Test
    void enumArrayShape() throws IOException {
        DemoStatus[] value = new DemoStatus[] {
                DemoStatus.ACTIVE,
                DemoStatus.INACTIVE,
                null,
                DemoStatus.PENDING
        };

        printShape("ENUM_ARRAY", value);
    }

    @Test
    void instantShape() throws IOException {
        Instant value = Instant.parse("2026-05-13T20:37:37Z");

        printShape("INSTANT", value);
    }

    @Test
    void instantArrayShape() throws IOException {
        Instant[] value = new Instant[] {
                Instant.parse("2026-05-13T20:37:37Z"),
                null,
                Instant.EPOCH
        };

        printShape("INSTANT_ARRAY", value);
    }

    @Test
    void localDateShape() throws IOException {
        LocalDate value = LocalDate.of(2026, 5, 13);

        printShape("LOCAL_DATE", value);
    }

    @Test
    void localDateArrayShape() throws IOException {
        LocalDate[] value = new LocalDate[] {
                LocalDate.of(2026, 5, 13),
                null,
                LocalDate.of(1970, 1, 1)
        };

        printShape("LOCAL_DATE_ARRAY", value);
    }

    @Test
    void localDateTimeShape() throws IOException {
        LocalDateTime value = LocalDateTime.of(2026, 5, 13, 20, 37, 37);

        printShape("LOCAL_DATE_TIME", value);
    }

    @Test
    void localDateTimeArrayShape() throws IOException {
        LocalDateTime[] value = new LocalDateTime[] {
                LocalDateTime.of(2026, 5, 13, 20, 37, 37),
                null,
                LocalDateTime.of(1970, 1, 1, 0, 0, 0)
        };

        printShape("LOCAL_DATE_TIME_ARRAY", value);
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
}