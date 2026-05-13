package com.protogemcouch.serialization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record StoredValue(
        Type type,
        String value,
        Character characterValue,
        Byte byteValue,
        byte[] byteArrayValue,
        String[] stringArrayValue,
        ArrayList<String> stringArrayListValue,
        LinkedHashMap<String, String> stringHashMapValue,
        LinkedHashMap<String, Object> stringObjectHashMapValue,
        String javaSerializedClassName,
        byte[] javaSerializedValue,
        byte[] objectArrayValue,
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
        STRING_ARRAY,
        STRING_ARRAY_LIST,
        STRING_HASH_MAP,
        STRING_OBJECT_HASH_MAP,
        JAVA_SERIALIZED_OBJECT,
        OBJECT_ARRAY,
        SHORT,
        INTEGER,
        BOOLEAN,
        LONG,
        FLOAT,
        DOUBLE,
        DATE
    }

    public static StoredValue stringValue(String value) {
        return new StoredValue(Type.STRING, value, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue characterValue(Character value) {
        return new StoredValue(Type.CHARACTER, null, value, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue byteValue(Byte value) {
        return new StoredValue(Type.BYTE, null, null, value, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue byteArrayValue(byte[] value) {
        return new StoredValue(Type.BYTE_ARRAY, null, null, null, copyByteArray(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue stringArrayValue(String[] value) {
        return new StoredValue(Type.STRING_ARRAY, null, null, null, null, copyStringArray(value), null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue stringArrayListValue(ArrayList<String> value) {
        return new StoredValue(Type.STRING_ARRAY_LIST, null, null, null, null, null, copyStringArrayList(value), null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue stringHashMapValue(Map<String, String> value) {
        return new StoredValue(Type.STRING_HASH_MAP, null, null, null, null, null, null, copyStringHashMap(value), null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue stringObjectHashMapValue(Map<String, Object> value) {
        return new StoredValue(Type.STRING_OBJECT_HASH_MAP, null, null, null, null, null, null, null, copyStringObjectHashMap(value), null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue javaSerializedObjectValue(String className, byte[] serializedValue) {
        return new StoredValue(Type.JAVA_SERIALIZED_OBJECT, null, null, null, null, null, null, null, null, className, copyByteArray(serializedValue), null, null, null, null, null, null, null, null);
    }

    public static StoredValue objectArrayValue(byte[] encodedObjectArrayValue) {
        return new StoredValue(Type.OBJECT_ARRAY, null, null, null, null, null, null, null, null, null, null, copyByteArray(encodedObjectArrayValue), null, null, null, null, null, null, null);
    }

    public static StoredValue shortValue(Short value) {
        return new StoredValue(Type.SHORT, null, null, null, null, null, null, null, null, null, null, null, value, null, null, null, null, null, null);
    }

    public static StoredValue integerValue(Integer value) {
        return new StoredValue(Type.INTEGER, null, null, null, null, null, null, null, null, null, null, null, null, value, null, null, null, null, null);
    }

    public static StoredValue booleanValue(Boolean value) {
        return new StoredValue(Type.BOOLEAN, null, null, null, null, null, null, null, null, null, null, null, null, null, value, null, null, null, null);
    }

    public static StoredValue longValue(Long value) {
        return new StoredValue(Type.LONG, null, null, null, null, null, null, null, null, null, null, null, null, null, null, value, null, null, null);
    }

    public static StoredValue floatValue(Float value) {
        return new StoredValue(Type.FLOAT, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, value, null, null);
    }

    public static StoredValue doubleValue(Double value) {
        return new StoredValue(Type.DOUBLE, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, value, null);
    }

    public static StoredValue dateValue(Date value) {
        return new StoredValue(Type.DATE, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, copyDate(value));
    }

    public StoredValue {
        Objects.requireNonNull(type, "type must not be null");

        if (byteArrayValue != null) {
            byteArrayValue = copyByteArray(byteArrayValue);
        }

        if (stringArrayValue != null) {
            stringArrayValue = copyStringArray(stringArrayValue);
        }

        if (stringArrayListValue != null) {
            stringArrayListValue = copyStringArrayList(stringArrayListValue);
        }

        if (stringHashMapValue != null) {
            stringHashMapValue = copyStringHashMap(stringHashMapValue);
        }

        if (stringObjectHashMapValue != null) {
            stringObjectHashMapValue = copyStringObjectHashMap(stringObjectHashMapValue);
        }

        if (javaSerializedValue != null) {
            javaSerializedValue = copyByteArray(javaSerializedValue);
        }

        if (objectArrayValue != null) {
            objectArrayValue = copyByteArray(objectArrayValue);
        }

        if (dateValue != null) {
            dateValue = copyDate(dateValue);
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

        if (type == Type.STRING_ARRAY && stringArrayValue == null) {
            throw new IllegalArgumentException("STRING_ARRAY StoredValue requires stringArrayValue");
        }

        if (type == Type.STRING_ARRAY_LIST && stringArrayListValue == null) {
            throw new IllegalArgumentException("STRING_ARRAY_LIST StoredValue requires stringArrayListValue");
        }

        if (type == Type.STRING_HASH_MAP && stringHashMapValue == null) {
            throw new IllegalArgumentException("STRING_HASH_MAP StoredValue requires stringHashMapValue");
        }

        if (type == Type.STRING_OBJECT_HASH_MAP && stringObjectHashMapValue == null) {
            throw new IllegalArgumentException("STRING_OBJECT_HASH_MAP StoredValue requires stringObjectHashMapValue");
        }

        if (type == Type.JAVA_SERIALIZED_OBJECT) {
            if (javaSerializedClassName == null || javaSerializedClassName.isBlank()) {
                throw new IllegalArgumentException("JAVA_SERIALIZED_OBJECT StoredValue requires javaSerializedClassName");
            }

            if (javaSerializedValue == null || javaSerializedValue.length == 0) {
                throw new IllegalArgumentException("JAVA_SERIALIZED_OBJECT StoredValue requires javaSerializedValue");
            }
        }

        if (type == Type.OBJECT_ARRAY) {
            if (objectArrayValue == null || objectArrayValue.length == 0) {
                throw new IllegalArgumentException("OBJECT_ARRAY StoredValue requires objectArrayValue");
            }

            if ((objectArrayValue[0] & 0xff) != 0x34) {
                throw new IllegalArgumentException("OBJECT_ARRAY StoredValue must start with Geode Object[] marker 0x34");
            }
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

    @Override
    public String[] stringArrayValue() {
        return copyStringArray(stringArrayValue);
    }

    @Override
    public ArrayList<String> stringArrayListValue() {
        return copyStringArrayList(stringArrayListValue);
    }

    @Override
    public LinkedHashMap<String, String> stringHashMapValue() {
        return copyStringHashMap(stringHashMapValue);
    }

    @Override
    public LinkedHashMap<String, Object> stringObjectHashMapValue() {
        return copyStringObjectHashMap(stringObjectHashMapValue);
    }

    @Override
    public byte[] javaSerializedValue() {
        return copyByteArray(javaSerializedValue);
    }

    @Override
    public byte[] objectArrayValue() {
        return copyByteArray(objectArrayValue);
    }

    @Override
    public Date dateValue() {
        return copyDate(dateValue);
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

    public String[] asStringArray() {
        if (type != Type.STRING_ARRAY) {
            throw new IllegalStateException("StoredValue is not STRING_ARRAY. Actual type: " + type);
        }

        return copyStringArray(stringArrayValue);
    }

    public ArrayList<String> asStringArrayList() {
        if (type != Type.STRING_ARRAY_LIST) {
            throw new IllegalStateException("StoredValue is not STRING_ARRAY_LIST. Actual type: " + type);
        }

        return copyStringArrayList(stringArrayListValue);
    }

    public LinkedHashMap<String, String> asStringHashMap() {
        if (type != Type.STRING_HASH_MAP) {
            throw new IllegalStateException("StoredValue is not STRING_HASH_MAP. Actual type: " + type);
        }

        return copyStringHashMap(stringHashMapValue);
    }

    public LinkedHashMap<String, Object> asStringObjectHashMap() {
        if (type != Type.STRING_OBJECT_HASH_MAP) {
            throw new IllegalStateException("StoredValue is not STRING_OBJECT_HASH_MAP. Actual type: " + type);
        }

        return copyStringObjectHashMap(stringObjectHashMapValue);
    }

    public String asJavaSerializedClassName() {
        if (type != Type.JAVA_SERIALIZED_OBJECT) {
            throw new IllegalStateException("StoredValue is not JAVA_SERIALIZED_OBJECT. Actual type: " + type);
        }

        return javaSerializedClassName;
    }

    public byte[] asJavaSerializedValue() {
        if (type != Type.JAVA_SERIALIZED_OBJECT) {
            throw new IllegalStateException("StoredValue is not JAVA_SERIALIZED_OBJECT. Actual type: " + type);
        }

        return copyByteArray(javaSerializedValue);
    }

    public byte[] asObjectArrayValue() {
        if (type != Type.OBJECT_ARRAY) {
            throw new IllegalStateException("StoredValue is not OBJECT_ARRAY. Actual type: " + type);
        }

        return copyByteArray(objectArrayValue);
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

        return copyDate(dateValue);
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
                && Arrays.equals(stringArrayValue, that.stringArrayValue)
                && Objects.equals(stringArrayListValue, that.stringArrayListValue)
                && Objects.equals(stringHashMapValue, that.stringHashMapValue)
                && stringObjectHashMapsEqual(stringObjectHashMapValue, that.stringObjectHashMapValue)
                && Objects.equals(javaSerializedClassName, that.javaSerializedClassName)
                && Arrays.equals(javaSerializedValue, that.javaSerializedValue)
                && Arrays.equals(objectArrayValue, that.objectArrayValue)
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
                stringArrayListValue,
                stringHashMapValue,
                javaSerializedClassName,
                shortValue,
                integerValue,
                booleanValue,
                longValue,
                floatValue,
                doubleValue,
                dateValue
        );

        result = 31 * result + Arrays.hashCode(byteArrayValue);
        result = 31 * result + Arrays.hashCode(stringArrayValue);
        result = 31 * result + stringObjectHashMapHashCode(stringObjectHashMapValue);
        result = 31 * result + Arrays.hashCode(javaSerializedValue);
        result = 31 * result + Arrays.hashCode(objectArrayValue);

        return result;
    }

    private static byte[] copyByteArray(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static String[] copyStringArray(String[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static ArrayList<String> copyStringArrayList(ArrayList<String> value) {
        return value == null ? null : new ArrayList<>(value);
    }

    private static LinkedHashMap<String, String> copyStringHashMap(Map<String, String> value) {
        return value == null ? null : new LinkedHashMap<>(value);
    }

    private static LinkedHashMap<String, Object> copyStringObjectHashMap(Map<String, Object> value) {
        if (value == null) {
            return null;
        }

        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : value.entrySet()) {
            copy.put(entry.getKey(), copySupportedMapObjectValue(entry.getValue()));
        }

        return copy;
    }

    private static Object copySupportedMapObjectValue(Object value) {
        if (value instanceof byte[] bytes) {
            return copyByteArray(bytes);
        }

        if (value instanceof String[] strings) {
            return copyStringArray(strings);
        }

        if (value instanceof ArrayList<?> list) {
            ArrayList<String> copy = new ArrayList<>(list.size());

            for (Object item : list) {
                copy.add(item == null ? null : String.valueOf(item));
            }

            return copy;
        }

        if (value instanceof Date date) {
            return copyDate(date);
        }

        return value;
    }

    private static Date copyDate(Date value) {
        return value == null ? null : new Date(value.getTime());
    }

    private static boolean stringObjectHashMapsEqual(
            LinkedHashMap<String, Object> left,
            LinkedHashMap<String, Object> right
    ) {
        if (left == right) {
            return true;
        }

        if (left == null || right == null || left.size() != right.size()) {
            return false;
        }

        for (Map.Entry<String, Object> entry : left.entrySet()) {
            String key = entry.getKey();

            if (!right.containsKey(key)) {
                return false;
            }

            if (!mapObjectValuesEqual(entry.getValue(), right.get(key))) {
                return false;
            }
        }

        return true;
    }

    private static boolean mapObjectValuesEqual(Object left, Object right) {
        if (left instanceof byte[] leftBytes && right instanceof byte[] rightBytes) {
            return Arrays.equals(leftBytes, rightBytes);
        }

        if (left instanceof String[] leftStrings && right instanceof String[] rightStrings) {
            return Arrays.equals(leftStrings, rightStrings);
        }

        return Objects.equals(left, right);
    }

    private static int stringObjectHashMapHashCode(LinkedHashMap<String, Object> value) {
        if (value == null) {
            return 0;
        }

        int result = 1;

        for (Map.Entry<String, Object> entry : value.entrySet()) {
            result = 31 * result + Objects.hashCode(entry.getKey());
            result = 31 * result + mapObjectValueHashCode(entry.getValue());
        }

        return result;
    }

    private static int mapObjectValueHashCode(Object value) {
        if (value instanceof byte[] bytes) {
            return Arrays.hashCode(bytes);
        }

        if (value instanceof String[] strings) {
            return Arrays.hashCode(strings);
        }

        return Objects.hashCode(value);
    }
}
