package com.protogemcouch.serialization;

import java.util.Objects;

public record StoredValue(
        Type type,
        String value,
        Integer integerValue,
        Boolean booleanValue
) {

    public enum Type {
        STRING,
        INTEGER,
        BOOLEAN
    }

    public static StoredValue stringValue(String value) {
        return new StoredValue(Type.STRING, value, null, null);
    }

    public static StoredValue integerValue(Integer value) {
        return new StoredValue(Type.INTEGER, null, value, null);
    }

    public static StoredValue booleanValue(Boolean value) {
        return new StoredValue(Type.BOOLEAN, null, null, value);
    }

    public StoredValue {
        Objects.requireNonNull(type, "type must not be null");

        if (type == Type.STRING && value == null) {
            throw new IllegalArgumentException("STRING StoredValue requires value");
        }

        if (type == Type.INTEGER && integerValue == null) {
            throw new IllegalArgumentException("INTEGER StoredValue requires integerValue");
        }

        if (type == Type.BOOLEAN && booleanValue == null) {
            throw new IllegalArgumentException("BOOLEAN StoredValue requires booleanValue");
        }
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
}