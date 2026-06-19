package com.protogemcouch.serialization;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StoredValue(
        Type type,
        String value,
        Character characterValue,
        Byte byteValue,
        byte[] byteArrayValue,
        boolean[] booleanArrayValue,
        char[] charArrayValue,
        short[] shortArrayValue,
        int[] intArrayValue,
        long[] longArrayValue,
        float[] floatArrayValue,
        double[] doubleArrayValue,
        String[] stringArrayValue,
        ArrayList<String> stringArrayListValue,
        LinkedHashMap<String, String> stringHashMapValue,
        LinkedHashMap<String, Object> stringObjectHashMapValue,
        String javaSerializedClassName,
        byte[] javaSerializedValue,
        byte[] objectArrayValue,
        byte[] objectArrayListValue,
        String opaqueGeodeTypeName,
        byte[] opaqueGeodeValue,
        byte[] pdxInstanceValue,
        Short shortValue,
        Integer integerValue,
        Boolean booleanValue,
        Long longValue,
        Float floatValue,
        Double doubleValue,
        Date dateValue
) implements Serializable {

    public enum Type {
        STRING,
        CHARACTER,
        BYTE,
        BYTE_ARRAY,
        BOOLEAN_ARRAY,
        CHAR_ARRAY,
        SHORT_ARRAY,
        INT_ARRAY,
        LONG_ARRAY,
        FLOAT_ARRAY,
        DOUBLE_ARRAY,
        STRING_ARRAY,
        STRING_ARRAY_LIST,
        STRING_HASH_MAP,
        STRING_OBJECT_HASH_MAP,
        JAVA_SERIALIZED_OBJECT,
        OBJECT_ARRAY,
        OBJECT_ARRAY_LIST,
        OPAQUE_GEODE_VALUE,
        PDX_INSTANCE,
        SHORT,
        INTEGER,
        BOOLEAN,
        LONG,
        FLOAT,
        DOUBLE,
        DATE
    }

    public static StoredValue stringValue(String value) {
        return new StoredValue(Type.STRING, value, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue characterValue(Character value) {
        return new StoredValue(Type.CHARACTER, null, value, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue byteValue(Byte value) {
        return new StoredValue(Type.BYTE, null, null, value, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue byteArrayValue(byte[] value) {
        return new StoredValue(Type.BYTE_ARRAY, null, null, null, copyByteArray(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue booleanArrayValue(boolean[] value) {
        return new StoredValue(Type.BOOLEAN_ARRAY, null, null, null, null, copyBooleanArray(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue charArrayValue(char[] value) {
        return new StoredValue(Type.CHAR_ARRAY, null, null, null, null, null, copyCharArray(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue shortArrayValue(short[] value) {
        return new StoredValue(Type.SHORT_ARRAY, null, null, null, null, null, null, copyShortArray(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue intArrayValue(int[] value) {
        return new StoredValue(Type.INT_ARRAY, null, null, null, null, null, null, null, copyIntArray(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue longArrayValue(long[] value) {
        return new StoredValue(Type.LONG_ARRAY, null, null, null, null, null, null, null, null, copyLongArray(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue floatArrayValue(float[] value) {
        return new StoredValue(Type.FLOAT_ARRAY, null, null, null, null, null, null, null, null, null, copyFloatArray(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue doubleArrayValue(double[] value) {
        return new StoredValue(Type.DOUBLE_ARRAY, null, null, null, null, null, null, null, null, null, null, copyDoubleArray(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue stringArrayValue(String[] value) {
        return new StoredValue(Type.STRING_ARRAY, null, null, null, null, null, null, null, null, null, null, null, copyStringArray(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue stringArrayListValue(ArrayList<String> value) {
        return new StoredValue(Type.STRING_ARRAY_LIST, null, null, null, null, null, null, null, null, null, null, null, null, copyStringArrayList(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue stringHashMapValue(Map<String, String> value) {
        return new StoredValue(Type.STRING_HASH_MAP, null, null, null, null, null, null, null, null, null, null, null, null, null, copyStringHashMap(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue stringObjectHashMapValue(Map<String, Object> value) {
        return new StoredValue(Type.STRING_OBJECT_HASH_MAP, null, null, null, null, null, null, null, null, null, null, null, null, null, null, copyStringObjectHashMap(value), null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue javaSerializedObjectValue(String className, byte[] serializedValue) {
        return new StoredValue(Type.JAVA_SERIALIZED_OBJECT, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, className, copyByteArray(serializedValue), null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue objectArrayValue(byte[] encodedObjectArrayValue) {
        return new StoredValue(Type.OBJECT_ARRAY, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, copyByteArray(encodedObjectArrayValue), null, null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue objectArrayListValue(byte[] encodedObjectArrayListValue) {
        return new StoredValue(Type.OBJECT_ARRAY_LIST, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, copyByteArray(encodedObjectArrayListValue), null, null, null, null, null, null, null, null, null, null);
    }

    public static StoredValue opaqueGeodeValue(String typeName, byte[] encodedValue) {
        return new StoredValue(Type.OPAQUE_GEODE_VALUE, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, typeName, copyByteArray(encodedValue), null, null, null, null, null, null, null, null);
    }

    public static StoredValue pdxInstanceValue(byte[] encodedValue) {
        return new StoredValue(Type.PDX_INSTANCE, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, copyByteArray(encodedValue), null, null, null, null, null, null, null);
    }

    public static StoredValue shortValue(Short value) {
        return new StoredValue(Type.SHORT, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, value, null, null, null, null, null, null);
    }

    public static StoredValue integerValue(Integer value) {
        return new StoredValue(Type.INTEGER, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, value, null, null, null, null, null);
    }

    public static StoredValue booleanValue(Boolean value) {
        return new StoredValue(Type.BOOLEAN, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, value, null, null, null, null);
    }

    public static StoredValue longValue(Long value) {
        return new StoredValue(Type.LONG, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, value, null, null, null);
    }

    public static StoredValue floatValue(Float value) {
        return new StoredValue(Type.FLOAT, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, value, null, null);
    }

    public static StoredValue doubleValue(Double value) {
        return new StoredValue(Type.DOUBLE, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, value, null);
    }

    public static StoredValue dateValue(Date value) {
        return new StoredValue(Type.DATE, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, copyDate(value));
    }

    public StoredValue {
        Objects.requireNonNull(type, "type must not be null");

        if (byteArrayValue != null) {
            byteArrayValue = copyByteArray(byteArrayValue);
        }

        if (booleanArrayValue != null) {
            booleanArrayValue = copyBooleanArray(booleanArrayValue);
        }

        if (charArrayValue != null) {
            charArrayValue = copyCharArray(charArrayValue);
        }

        if (shortArrayValue != null) {
            shortArrayValue = copyShortArray(shortArrayValue);
        }

        if (intArrayValue != null) {
            intArrayValue = copyIntArray(intArrayValue);
        }

        if (longArrayValue != null) {
            longArrayValue = copyLongArray(longArrayValue);
        }

        if (floatArrayValue != null) {
            floatArrayValue = copyFloatArray(floatArrayValue);
        }

        if (doubleArrayValue != null) {
            doubleArrayValue = copyDoubleArray(doubleArrayValue);
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

        if (objectArrayListValue != null) {
            objectArrayListValue = copyByteArray(objectArrayListValue);
        }

        if (opaqueGeodeValue != null) {
            opaqueGeodeValue = copyByteArray(opaqueGeodeValue);
        }

        if (pdxInstanceValue != null) {
            pdxInstanceValue = copyByteArray(pdxInstanceValue);
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

        if (type == Type.BOOLEAN_ARRAY && booleanArrayValue == null) {
            throw new IllegalArgumentException("BOOLEAN_ARRAY StoredValue requires booleanArrayValue");
        }

        if (type == Type.CHAR_ARRAY && charArrayValue == null) {
            throw new IllegalArgumentException("CHAR_ARRAY StoredValue requires charArrayValue");
        }

        if (type == Type.SHORT_ARRAY && shortArrayValue == null) {
            throw new IllegalArgumentException("SHORT_ARRAY StoredValue requires shortArrayValue");
        }

        if (type == Type.INT_ARRAY && intArrayValue == null) {
            throw new IllegalArgumentException("INT_ARRAY StoredValue requires intArrayValue");
        }

        if (type == Type.LONG_ARRAY && longArrayValue == null) {
            throw new IllegalArgumentException("LONG_ARRAY StoredValue requires longArrayValue");
        }

        if (type == Type.FLOAT_ARRAY && floatArrayValue == null) {
            throw new IllegalArgumentException("FLOAT_ARRAY StoredValue requires floatArrayValue");
        }

        if (type == Type.DOUBLE_ARRAY && doubleArrayValue == null) {
            throw new IllegalArgumentException("DOUBLE_ARRAY StoredValue requires doubleArrayValue");
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

        if (type == Type.OBJECT_ARRAY_LIST) {
            if (objectArrayListValue == null || objectArrayListValue.length == 0) {
                throw new IllegalArgumentException("OBJECT_ARRAY_LIST StoredValue requires objectArrayListValue");
            }

            if ((objectArrayListValue[0] & 0xff) != 0x41) {
                throw new IllegalArgumentException("OBJECT_ARRAY_LIST StoredValue must start with Geode ArrayList marker 0x41");
            }
        }

        if (type == Type.OPAQUE_GEODE_VALUE) {
            if (opaqueGeodeTypeName == null || opaqueGeodeTypeName.isBlank()) {
                throw new IllegalArgumentException("OPAQUE_GEODE_VALUE StoredValue requires opaqueGeodeTypeName");
            }

            if (opaqueGeodeValue == null || opaqueGeodeValue.length == 0) {
                throw new IllegalArgumentException("OPAQUE_GEODE_VALUE StoredValue requires opaqueGeodeValue");
            }
        }

        if (type == Type.PDX_INSTANCE) {
            if (pdxInstanceValue == null || pdxInstanceValue.length == 0) {
                throw new IllegalArgumentException("PDX_INSTANCE StoredValue requires pdxInstanceValue");
            }

            if ((pdxInstanceValue[0] & 0xff) != 0x5d) {
                throw new IllegalArgumentException("PDX_INSTANCE StoredValue must start with Geode PDX marker 0x5d");
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
    public boolean[] booleanArrayValue() {
        return copyBooleanArray(booleanArrayValue);
    }

    @Override
    public char[] charArrayValue() {
        return copyCharArray(charArrayValue);
    }

    @Override
    public short[] shortArrayValue() {
        return copyShortArray(shortArrayValue);
    }

    @Override
    public int[] intArrayValue() {
        return copyIntArray(intArrayValue);
    }

    @Override
    public long[] longArrayValue() {
        return copyLongArray(longArrayValue);
    }

    @Override
    public float[] floatArrayValue() {
        return copyFloatArray(floatArrayValue);
    }

    @Override
    public double[] doubleArrayValue() {
        return copyDoubleArray(doubleArrayValue);
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
    public byte[] objectArrayListValue() {
        return copyByteArray(objectArrayListValue);
    }

    @Override
    public byte[] opaqueGeodeValue() {
        return copyByteArray(opaqueGeodeValue);
    }

    @Override
    public byte[] pdxInstanceValue() {
        return copyByteArray(pdxInstanceValue);
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

    public boolean[] asBooleanArray() {
        if (type != Type.BOOLEAN_ARRAY) {
            throw new IllegalStateException("StoredValue is not BOOLEAN_ARRAY. Actual type: " + type);
        }

        return copyBooleanArray(booleanArrayValue);
    }

    public char[] asCharArray() {
        if (type != Type.CHAR_ARRAY) {
            throw new IllegalStateException("StoredValue is not CHAR_ARRAY. Actual type: " + type);
        }

        return copyCharArray(charArrayValue);
    }

    public short[] asShortArray() {
        if (type != Type.SHORT_ARRAY) {
            throw new IllegalStateException("StoredValue is not SHORT_ARRAY. Actual type: " + type);
        }

        return copyShortArray(shortArrayValue);
    }

    public int[] asIntArray() {
        if (type != Type.INT_ARRAY) {
            throw new IllegalStateException("StoredValue is not INT_ARRAY. Actual type: " + type);
        }

        return copyIntArray(intArrayValue);
    }

    public long[] asLongArray() {
        if (type != Type.LONG_ARRAY) {
            throw new IllegalStateException("StoredValue is not LONG_ARRAY. Actual type: " + type);
        }

        return copyLongArray(longArrayValue);
    }

    public float[] asFloatArray() {
        if (type != Type.FLOAT_ARRAY) {
            throw new IllegalStateException("StoredValue is not FLOAT_ARRAY. Actual type: " + type);
        }

        return copyFloatArray(floatArrayValue);
    }

    public double[] asDoubleArray() {
        if (type != Type.DOUBLE_ARRAY) {
            throw new IllegalStateException("StoredValue is not DOUBLE_ARRAY. Actual type: " + type);
        }

        return copyDoubleArray(doubleArrayValue);
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

    public byte[] asObjectArrayListValue() {
        if (type != Type.OBJECT_ARRAY_LIST) {
            throw new IllegalStateException("StoredValue is not OBJECT_ARRAY_LIST. Actual type: " + type);
        }

        return copyByteArray(objectArrayListValue);
    }

    public String asOpaqueGeodeTypeName() {
        if (type != Type.OPAQUE_GEODE_VALUE) {
            throw new IllegalStateException("StoredValue is not OPAQUE_GEODE_VALUE. Actual type: " + type);
        }

        return opaqueGeodeTypeName;
    }

    public byte[] asOpaqueGeodeValue() {
        if (type != Type.OPAQUE_GEODE_VALUE) {
            throw new IllegalStateException("StoredValue is not OPAQUE_GEODE_VALUE. Actual type: " + type);
        }

        return copyByteArray(opaqueGeodeValue);
    }

    public byte[] asPdxInstanceValue() {
        if (type != Type.PDX_INSTANCE) {
            throw new IllegalStateException("StoredValue is not PDX_INSTANCE. Actual type: " + type);
        }

        return copyByteArray(pdxInstanceValue);
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
                && Arrays.equals(booleanArrayValue, that.booleanArrayValue)
                && Arrays.equals(charArrayValue, that.charArrayValue)
                && Arrays.equals(shortArrayValue, that.shortArrayValue)
                && Arrays.equals(intArrayValue, that.intArrayValue)
                && Arrays.equals(longArrayValue, that.longArrayValue)
                && Arrays.equals(floatArrayValue, that.floatArrayValue)
                && Arrays.equals(doubleArrayValue, that.doubleArrayValue)
                && Arrays.equals(stringArrayValue, that.stringArrayValue)
                && Objects.equals(stringArrayListValue, that.stringArrayListValue)
                && Objects.equals(stringHashMapValue, that.stringHashMapValue)
                && stringObjectHashMapsEqual(stringObjectHashMapValue, that.stringObjectHashMapValue)
                && Objects.equals(javaSerializedClassName, that.javaSerializedClassName)
                && Arrays.equals(javaSerializedValue, that.javaSerializedValue)
                && Arrays.equals(objectArrayValue, that.objectArrayValue)
                && Arrays.equals(objectArrayListValue, that.objectArrayListValue)
                && Objects.equals(opaqueGeodeTypeName, that.opaqueGeodeTypeName)
                && Arrays.equals(opaqueGeodeValue, that.opaqueGeodeValue)
                && Arrays.equals(pdxInstanceValue, that.pdxInstanceValue)
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
                opaqueGeodeTypeName,
                shortValue,
                integerValue,
                booleanValue,
                longValue,
                floatValue,
                doubleValue,
                dateValue
        );

        result = 31 * result + Arrays.hashCode(byteArrayValue);
        result = 31 * result + Arrays.hashCode(booleanArrayValue);
        result = 31 * result + Arrays.hashCode(charArrayValue);
        result = 31 * result + Arrays.hashCode(shortArrayValue);
        result = 31 * result + Arrays.hashCode(intArrayValue);
        result = 31 * result + Arrays.hashCode(longArrayValue);
        result = 31 * result + Arrays.hashCode(floatArrayValue);
        result = 31 * result + Arrays.hashCode(doubleArrayValue);
        result = 31 * result + Arrays.hashCode(stringArrayValue);
        result = 31 * result + stringObjectHashMapHashCode(stringObjectHashMapValue);
        result = 31 * result + Arrays.hashCode(javaSerializedValue);
        result = 31 * result + Arrays.hashCode(objectArrayValue);
        result = 31 * result + Arrays.hashCode(objectArrayListValue);
        result = 31 * result + Arrays.hashCode(opaqueGeodeValue);
        result = 31 * result + Arrays.hashCode(pdxInstanceValue);

        return result;
    }

    private static byte[] copyByteArray(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static boolean[] copyBooleanArray(boolean[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static char[] copyCharArray(char[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static short[] copyShortArray(short[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static int[] copyIntArray(int[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static long[] copyLongArray(long[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static float[] copyFloatArray(float[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static double[] copyDoubleArray(double[] value) {
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
        // Recursive deep copy (nested Map/Object[]/List + the JDK scalar extras) lives in
        // NestedValueSupport, shared with the decode and wire layers.
        return NestedValueSupport.copyValue(value);
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
        if (left == right) {
            return true;
        }

        if (left == null || right == null) {
            return false;
        }

        if (left instanceof byte[] leftBytes && right instanceof byte[] rightBytes) {
            return Arrays.equals(leftBytes, rightBytes);
        }

        if (left instanceof boolean[] leftBooleans && right instanceof boolean[] rightBooleans) {
            return Arrays.equals(leftBooleans, rightBooleans);
        }

        if (left instanceof char[] leftChars && right instanceof char[] rightChars) {
            return Arrays.equals(leftChars, rightChars);
        }

        if (left instanceof short[] leftShorts && right instanceof short[] rightShorts) {
            return Arrays.equals(leftShorts, rightShorts);
        }

        if (left instanceof int[] leftInts && right instanceof int[] rightInts) {
            return Arrays.equals(leftInts, rightInts);
        }

        if (left instanceof long[] leftLongs && right instanceof long[] rightLongs) {
            return Arrays.equals(leftLongs, rightLongs);
        }

        if (left instanceof float[] leftFloats && right instanceof float[] rightFloats) {
            return Arrays.equals(leftFloats, rightFloats);
        }

        if (left instanceof double[] leftDoubles && right instanceof double[] rightDoubles) {
            return Arrays.equals(leftDoubles, rightDoubles);
        }

        // Object[] (covers String[] and the nested object arrays), List, and nested Map are compared
        // by value recursively so arrays-inside-arrays / arrays-inside-maps don't fall back to array
        // identity equality.
        if (left instanceof Object[] leftArray && right instanceof Object[] rightArray) {
            if (leftArray.length != rightArray.length) {
                return false;
            }
            for (int i = 0; i < leftArray.length; i++) {
                if (!mapObjectValuesEqual(leftArray[i], rightArray[i])) {
                    return false;
                }
            }
            return true;
        }

        if (left instanceof List<?> leftList && right instanceof List<?> rightList) {
            if (leftList.size() != rightList.size()) {
                return false;
            }
            for (int i = 0; i < leftList.size(); i++) {
                if (!mapObjectValuesEqual(leftList.get(i), rightList.get(i))) {
                    return false;
                }
            }
            return true;
        }

        if (left instanceof Map<?, ?> leftMap && right instanceof Map<?, ?> rightMap) {
            if (leftMap.size() != rightMap.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : leftMap.entrySet()) {
                if (!rightMap.containsKey(entry.getKey())) {
                    return false;
                }
                if (!mapObjectValuesEqual(entry.getValue(), rightMap.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
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
        if (value == null) {
            return 0;
        }

        if (value instanceof byte[] bytes) {
            return Arrays.hashCode(bytes);
        }

        if (value instanceof boolean[] booleans) {
            return Arrays.hashCode(booleans);
        }

        if (value instanceof char[] chars) {
            return Arrays.hashCode(chars);
        }

        if (value instanceof short[] shorts) {
            return Arrays.hashCode(shorts);
        }

        if (value instanceof int[] ints) {
            return Arrays.hashCode(ints);
        }

        if (value instanceof long[] longs) {
            return Arrays.hashCode(longs);
        }

        if (value instanceof float[] floats) {
            return Arrays.hashCode(floats);
        }

        if (value instanceof double[] doubles) {
            return Arrays.hashCode(doubles);
        }

        // Recursive, order-aware for Object[]/List and order-independent for Map, kept consistent
        // with mapObjectValuesEqual so deep-equal values always hash equal.
        if (value instanceof Object[] array) {
            int result = 1;
            for (Object item : array) {
                result = 31 * result + mapObjectValueHashCode(item);
            }
            return result;
        }

        if (value instanceof List<?> list) {
            int result = 1;
            for (Object item : list) {
                result = 31 * result + mapObjectValueHashCode(item);
            }
            return result;
        }

        if (value instanceof Map<?, ?> map) {
            int result = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result += Objects.hashCode(entry.getKey()) ^ mapObjectValueHashCode(entry.getValue());
            }
            return result;
        }

        return Objects.hashCode(value);
    }
}
