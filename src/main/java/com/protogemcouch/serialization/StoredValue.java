package com.protogemcouch.serialization;

import java.util.Objects;

public record StoredValue(
        Type type,
        String value,
        Short shortValue,
        Integer integerValue,
        Boolean booleanValue,
        Long longValue,
        Float floatValue,
        Double doubleValue
) {

    public enum Type {
        STRING,
        SHORT,
        INTEGER,
        BOOLEAN,
        LONG,
        FLOAT,
        DOUBLE
    }

    public static StoredValue stringValue(String value) {
        return new StoredValue(Type.STRING, value, null, null, null, null, null, null);
    }

    public static StoredValue shortValue(Short value) {
        return new StoredValue(Type.SHORT, null, value, null, null, null, null, null);
    }

    public static StoredValue integerValue(Integer value) {
        return new StoredValue(Type.INTEGER, null, null, value, null, null, null, null);
    }

    public static StoredValue booleanValue(Boolean value) {
        return new StoredValue(Type.BOOLEAN, null, null, null, value, null, null, null);
    }

    public static StoredValue longValue(Long value) {
        return new StoredValue(Type.LONG, null, null, null, null, value, null, null);
    }

    public static StoredValue floatValue(Float value) {
        return new StoredValue(Type.FLOAT, null, null, null, null, null, value, null);
    }

    public static StoredValue doubleValue(Double value) {
        return new StoredValue(Type.DOUBLE, null, null, null, null, null, null, value);
    }

    public StoredValue {
        Objects.requireNonNull(type, "type must not be null");

        if (type == Type.STRING && value == null) {
            throw new IllegalArgumentException("STRING StoredValue requires value");
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
}