package com.protogemcouch.serialization;

import java.util.Arrays;
import java.util.Date;
import java.util.Objects;

public record StoredValue(
        Type type,
        String value,
        Character characterValue,
        Byte byteValue,
        byte[] byteArrayValue,
        Short shortValue,
        Integer integerValue,
        Boolean booleanValue,
        Long longValue,
        Float floatValue,
        Double doubleValue,
        Date dateValue
) {

    public enum Type {
        STRING,
        CHARACTER,
        BYTE,
        BYTE_ARRAY,
        SHORT,
        INTEGER,
        BOOLEAN,
        LONG,
        FLOAT,
        DOUBLE,
        DATE
    }

    public static StoredValue stringValue(String value) {
        return new StoredValue(Type.STRING, value, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue characterValue(Character value) {
        return new StoredValue(Type.CHARACTER, null, value, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue byteValue(Byte value) {
        return new StoredValue(Type.BYTE, null, null, value, null, null, null, null, null, null, null, null);
    }

    public static StoredValue byteArrayValue(byte[] value) {
        return new StoredValue(Type.BYTE_ARRAY, null, null, null, copyByteArray(value), null, null, null, null, null, null, null);
    }

    public static StoredValue shortValue(Short value) {
        return new StoredValue(Type.SHORT, null, null, null, null, value, null, null, null, null, null, null);
    }

    public static StoredValue integerValue(Integer value) {
        return new StoredValue(Type.INTEGER, null, null, null, null, null, value, null, null, null, null, null);
    }

    public static StoredValue booleanValue(Boolean value) {
        return new StoredValue(Type.BOOLEAN, null, null, null, null, null, null, value, null, null, null, null);
    }

    public static StoredValue longValue(Long value) {
        return new StoredValue(Type.LONG, null, null, null, null, null, null, null, value, null, null, null);
    }

    public static StoredValue floatValue(Float value) {
        return new StoredValue(Type.FLOAT, null, null, null, null, null, null, null, null, value, null, null);
    }

    public static StoredValue doubleValue(Double value) {
        return new StoredValue(Type.DOUBLE, null, null, null, null, null, null, null, null, null, value, null);
    }

    public static StoredValue dateValue(Date value) {
        return new StoredValue(Type.DATE, null, null, null, null, null, null, null, null, null, null, value);
    }

    public StoredValue {
        Objects.requireNonNull(type, "type must not be null");

        if (byteArrayValue != null) {
            byteArrayValue = copyByteArray(byteArrayValue);
        }

        if (type == Type.STRING && value == null) {
            throw new IllegalArgumentException("STRING StoredValue requires value");
        }

        if (type == Type.CHARACTER && characterValue == null) {
            throw new IllegalArgumentException("CHARACTER StoredValue requires characterValue");
        }

        if (type == Type.BYTE && byteValue == null) {
            throw new IllegalArgumentException("BYTE StoredValue requires byteValue");
        }

        if (type == Type.BYTE_ARRAY && byteArrayValue == null) {
            throw new IllegalArgumentException("BYTE_ARRAY StoredValue requires byteArrayValue");
        }

        if (type == Type.SHORT && shortValue == null) {
            throw new IllegalArgumentException("SHORT StoredValue requires shortValue");
        }

        if (type == Type.INTEGER && integerValue == null) {
            throw new IllegalArgumentException("INTEGER StoredValue requires integerValue");
        }

        if (type == Type.BOOLEAN && booleanValue == null) {
            throw new IllegalArgumentException("BOOLEAN StoredValue requires booleanValue");
        }

        if (type == Type.LONG && longValue == null) {
            throw new IllegalArgumentException("LONG StoredValue requires longValue");
        }

        if (type == Type.FLOAT && floatValue == null) {
            throw new IllegalArgumentException("FLOAT StoredValue requires floatValue");
        }

        if (type == Type.DOUBLE && doubleValue == null) {
            throw new IllegalArgumentException("DOUBLE StoredValue requires doubleValue");
        }

        if (type == Type.DATE && dateValue == null) {
            throw new IllegalArgumentException("DATE StoredValue requires dateValue");
        }
    }

    @Override
    public byte[] byteArrayValue() {
        return copyByteArray(byteArrayValue);
    }

    public Character asCharacter() {
        if (type != Type.CHARACTER) {
            throw new IllegalStateException("StoredValue is not CHARACTER. Actual type: " + type);
        }

        return characterValue;
    }

    public Byte asByte() {
        if (type != Type.BYTE) {
            throw new IllegalStateException("StoredValue is not BYTE. Actual type: " + type);
        }

        return byteValue;
    }

    public byte[] asByteArray() {
        if (type != Type.BYTE_ARRAY) {
            throw new IllegalStateException("StoredValue is not BYTE_ARRAY. Actual type: " + type);
        }

        return copyByteArray(byteArrayValue);
    }

    public Short asShort() {
        if (type != Type.SHORT) {
            throw new IllegalStateException("StoredValue is not SHORT. Actual type: " + type);
        }

        return shortValue;
    }

    public Integer asInteger() {
        if (type != Type.INTEGER) {
            throw new IllegalStateException("StoredValue is not INTEGER. Actual type: " + type);
        }

        return integerValue;
    }

    public Boolean asBoolean() {
        if (type != Type.BOOLEAN) {
            throw new IllegalStateException("StoredValue is not BOOLEAN. Actual type: " + type);
        }

        return booleanValue;
    }

    public Long asLong() {
        if (type != Type.LONG) {
            throw new IllegalStateException("StoredValue is not LONG. Actual type: " + type);
        }

        return longValue;
    }

    public Float asFloat() {
        if (type != Type.FLOAT) {
            throw new IllegalStateException("StoredValue is not FLOAT. Actual type: " + type);
        }

        return floatValue;
    }

    public Double asDouble() {
        if (type != Type.DOUBLE) {
            throw new IllegalStateException("StoredValue is not DOUBLE. Actual type: " + type);
        }

        return doubleValue;
    }

    public Date asDate() {
        if (type != Type.DATE) {
            throw new IllegalStateException("StoredValue is not DATE. Actual type: " + type);
        }

        return dateValue;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof StoredValue that)) {
            return false;
        }

        return type == that.type
                && Objects.equals(value, that.value)
                && Objects.equals(characterValue, that.characterValue)
                && Objects.equals(byteValue, that.byteValue)
                && Arrays.equals(byteArrayValue, that.byteArrayValue)
                && Objects.equals(shortValue, that.shortValue)
                && Objects.equals(integerValue, that.integerValue)
                && Objects.equals(booleanValue, that.booleanValue)
                && Objects.equals(longValue, that.longValue)
                && Objects.equals(floatValue, that.floatValue)
                && Objects.equals(doubleValue, that.doubleValue)
                && Objects.equals(dateValue, that.dateValue);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                type,
                value,
                characterValue,
                byteValue,
                shortValue,
                integerValue,
                booleanValue,
                longValue,
                floatValue,
                doubleValue,
                dateValue
        );

        result = 31 * result + Arrays.hashCode(byteArrayValue);

        return result;
    }

    private static byte[] copyByteArray(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }
}