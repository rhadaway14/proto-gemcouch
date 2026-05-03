package com.protogemcouch.serialization;

public record StoredValue(Type type, String value) {

    private static final String PREFIX = "__PROTOGEMCOUCH_TYPED__|";
    private static final String SEP = "|";

    public enum Type {
        STRING,
        INTEGER
    }

    public static StoredValue stringValue(String value) {
        if (value == null) {
            return null;
        }

        return new StoredValue(Type.STRING, value);
    }

    public static StoredValue integerValue(Integer value) {
        if (value == null) {
            return null;
        }

        return new StoredValue(Type.INTEGER, String.valueOf(value));
    }

    public Integer asInteger() {
        if (type != Type.INTEGER) {
            throw new IllegalStateException("Stored value is not an integer. Actual type: " + type);
        }

        return Integer.valueOf(value);
    }

    public String toRepositoryValue() {
        if (type == Type.STRING) {
            /*
             * Preserve the existing string storage path so all previously validated
             * string operations continue to behave the same way.
             */
            return value;
        }

        return PREFIX + type.name().toLowerCase() + SEP + value;
    }

    public static StoredValue fromRepositoryValue(String repositoryValue) {
        if (repositoryValue == null) {
            return null;
        }

        if (!repositoryValue.startsWith(PREFIX)) {
            return stringValue(repositoryValue);
        }

        String body = repositoryValue.substring(PREFIX.length());
        int separator = body.indexOf(SEP);

        if (separator <= 0 || separator >= body.length() - 1) {
            /*
             * If the envelope is malformed, return it as a string rather than
             * dropping data.
             */
            return stringValue(repositoryValue);
        }

        String typeText = body.substring(0, separator);
        String valueText = body.substring(separator + 1);

        if ("integer".equalsIgnoreCase(typeText)) {
            return new StoredValue(Type.INTEGER, valueText);
        }

        if ("string".equalsIgnoreCase(typeText)) {
            return new StoredValue(Type.STRING, valueText);
        }

        return stringValue(repositoryValue);
    }
}