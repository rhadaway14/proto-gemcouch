package com.protogemcouch.wire;

import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.serialization.ValueEncoding;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GemResponseWriter {

    private static final byte[] PUT_REPLY_PART1 = new byte[] {
            0x00
    };

    private static final byte[] PUT_REPLY_PART2 = new byte[] {
            0x00, 0x00, 0x00, 0x04
    };

    private static final byte[] PUT_REPLY_PART3 = new byte[] {
            (byte) 0x01, (byte) 0x88, 0x00, 0x04, 0x00, 0x00, (byte) 0xff, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x01, (byte) 0xc0, (byte) 0xf1, (byte) 0x95,
            (byte) 0x92, (byte) 0xdc, 0x33
    };

    private static final byte[] REMOVE_REPLY_PART1 = new byte[] {
            0x00, 0x00, 0x00, 0x03
    };

    private static final byte[] REMOVE_REPLY_PART2 = new byte[] {
            (byte) 0x01, (byte) 0x88, 0x00, 0x04, 0x00, 0x00, (byte) 0xff,
            0x00, 0x02, 0x00, 0x00, 0x00, 0x02, (byte) 0xec, (byte) 0xc6,
            (byte) 0xac, (byte) 0x94, (byte) 0xdc, 0x33
    };

    private static final byte[] REMOVE_REPLY_PART3 = new byte[] {
            0x00
    };

    private static final byte[] REMOVE_REPLY_PART4 = new byte[] {
            0x00, 0x00, 0x00, 0x00
    };

    /*
     * Geode DataSerializer byte[] marker observed from ByteArrayShapeTest:
     *
     *   new byte[] {}                         -> 2e 00
     *   new byte[] {0x01}                     -> 2e 01 01
     *   new byte[] {0x01,2,3,4,5}             -> 2e 05 01 02 03 04 05
     *   new byte[] {0,1,0x7f,0x80,0xff}       -> 2e 05 00 01 7f 80 ff
     *
     * This implementation supports the currently validated compact/small-length shape.
     */
    private static final byte GEODE_BYTE_ARRAY_CODE = 0x2e;

    /*
     * Geode DataSerializer int[] marker observed from PrimitiveArrayShapeTest:
     *
     *   new int[] {}                                      -> 30 00
     *   new int[] {1,42,-7,Integer.MAX_VALUE,MIN_VALUE}   -> 30 05 00000001 0000002a fffffff9 7fffffff 80000000
     *
     * Values are stored big-endian, four bytes per int.
     */
    private static final byte GEODE_INT_ARRAY_CODE = 0x30;

    /*
     * Geode DataSerializer primitive array markers observed from PrimitiveArrayShapeTest:
     *
     *   boolean[]  -> 1a <length> <1-byte boolean values>
     *   char[]     -> 1b <length> <2-byte big-endian char values>
     *   short[]    -> 2f <length> <2-byte big-endian short values>
     *   long[]     -> 31 <length> <8-byte big-endian long values>
     *   float[]    -> 32 <length> <4-byte big-endian IEEE-754 float bits>
     *   double[]   -> 33 <length> <8-byte big-endian IEEE-754 double bits>
     */
    private static final byte GEODE_BOOLEAN_ARRAY_CODE = 0x1a;
    private static final byte GEODE_CHAR_ARRAY_CODE = 0x1b;
    private static final byte GEODE_SHORT_ARRAY_CODE = 0x2f;
    private static final byte GEODE_LONG_ARRAY_CODE = 0x31;
    private static final byte GEODE_FLOAT_ARRAY_CODE = 0x32;
    private static final byte GEODE_DOUBLE_ARRAY_CODE = 0x33;

    /*
     * Geode DataSerializer String[] marker observed from StringArrayShapeTest:
     *
     *   new String[] {}                         -> 40 00
     *   new String[] {"one"}                    -> 40 01 57 00 03 6f 6e 65
     *   new String[] {"one","two","three"}     -> 40 03 57 00 03 6f 6e 65 57 00 03 74 77 6f 57 00 05 74 68 72 65 65
     *   new String[] {"","A","hello"}          -> 40 03 57 00 00 57 00 01 41 57 00 05 68 65 6c 6c 6f
     *   new String[] {"one",null,"three"}      -> 40 03 57 00 03 6f 6e 65 45 57 00 05 74 68 72 65 65
     *
     * String elements use the normal Geode string marker 0x57.
     * Null string-array elements use marker 0x45.
     */
    private static final byte GEODE_STRING_ARRAY_CODE = 0x40;
    private static final byte GEODE_NULL_STRING_ARRAY_ELEMENT_CODE = 0x45;

    /*
     * Geode DataSerializer boolean marker:
     *
     *   Boolean.TRUE  -> 35 01
     *   Boolean.FALSE -> 35 00
     */
    private static final byte GEODE_BOOLEAN_CODE = 0x35;

    /*
     * Geode DataSerializer character marker observed from CharacterShapeTest:
     *
     *   Character.valueOf('A') -> 36 00 41
     *   Character.valueOf('Z') -> 36 00 5a
     *   Character.valueOf('0') -> 36 00 30
     *   Character.valueOf(' ') -> 36 00 20
     */
    private static final byte GEODE_CHARACTER_CODE = 0x36;

    /*
     * Geode DataSerializer byte marker observed from ByteShapeTest:
     *
     *   Byte.valueOf((byte) 0)    -> 37 00
     *   Byte.valueOf((byte) 7)    -> 37 07
     *   Byte.valueOf((byte) -7)   -> 37 f9
     *   Byte.MAX_VALUE            -> 37 7f
     *   Byte.MIN_VALUE            -> 37 80
     */
    private static final byte GEODE_BYTE_CODE = 0x37;

    /*
     * Geode DataSerializer short marker observed from ShortShapeTest:
     *
     *   Short.valueOf((short) 7)  -> 38 00 07
     *   Short.valueOf((short) -7) -> 38 ff f9
     *   Short.valueOf((short) 0)  -> 38 00 00
     *   Short.MAX_VALUE           -> 38 7f ff
     *   Short.MIN_VALUE           -> 38 80 00
     */
    private static final byte GEODE_SHORT_CODE = 0x38;

    /*
     * Geode DataSerializer integer marker observed from IntegerShapeTest:
     *
     *   Integer.valueOf(7) -> 39 00 00 00 07
     */
    private static final byte GEODE_INTEGER_CODE = 0x39;

    /*
     * Geode DataSerializer long marker observed from LongShapeTest:
     *
     *   Long.valueOf(7L)           -> 3a 00 00 00 00 00 00 00 07
     *   Long.valueOf(-7L)          -> 3a ff ff ff ff ff ff ff f9
     *   Long.valueOf(9876543210L)  -> 3a 00 00 00 02 4c b0 16 ea
     */
    private static final byte GEODE_LONG_CODE = 0x3a;

    /*
     * Geode DataSerializer float marker observed from FloatShapeTest:
     *
     *   Float.valueOf(7.25f)      -> 3b 40 e8 00 00
     *   Float.valueOf(-7.25f)     -> 3b c0 e8 00 00
     *   Float.valueOf(987654.25f) -> 3b 49 71 20 64
     *   Float.valueOf(0.0f)       -> 3b 00 00 00 00
     */
    private static final byte GEODE_FLOAT_CODE = 0x3b;

    /*
     * Geode DataSerializer double marker observed from DoubleShapeTest:
     *
     *   Double.valueOf(7.25d)        -> 3c 40 1d 00 00 00 00 00 00
     *   Double.valueOf(-7.25d)       -> 3c c0 1d 00 00 00 00 00 00
     *   Double.valueOf(9876543.210d) -> 3c 41 62 d6 87 e6 b8 51 ec
     *   Double.valueOf(0.0d)         -> 3c 00 00 00 00 00 00 00 00
     */
    private static final byte GEODE_DOUBLE_CODE = 0x3c;

    /*
     * Geode DataSerializer Date marker observed from DateShapeTest:
     *
     *   new Date(0L)                 -> 3d 00 00 00 00 00 00 00 00
     *   new Date(1_000L)             -> 3d 00 00 00 00 00 00 03 e8
     *   new Date(1_778_265_266_000L) -> 3d 00 00 01 9e 08 de 97 50
     *   new Date(-1_000L)            -> 3d ff ff ff ff ff ff fc 18
     */
    private static final byte GEODE_DATE_CODE = 0x3d;

    /*
     * Geode DataSerializer ArrayList/List marker observed from StringArrayListShapeTest
     * and key-set response testing:
     *
     *   new ArrayList<>()                         -> 41 00
     *   ["one"]                                   -> 41 01 57 00 03 6f 6e 65
     *   ["one","two","three"]                    -> 41 03 57 00 03 6f 6e 65 57 00 03 74 77 6f 57 00 05 74 68 72 65 65
     *   ["","A","hello"]                         -> 41 03 57 00 00 57 00 01 41 57 00 05 68 65 6c 6c 6f
     *   ["one",null,"three"]                     -> 41 03 57 00 03 6f 6e 65 29 57 00 05 74 68 72 65 65
     *
     * String elements use the normal Geode string marker 0x57.
     * Null ArrayList elements use the normal Geode null marker 0x29.
     */
    private static final byte GEODE_LIST_CODE = 0x41;
    private static final byte GEODE_NULL_CODE = 0x29;

    /*
     * Geode DataSerializer HashMap/LinkedHashMap observations from
     * HashMapStringStringShapeTest:
     *
     *   new HashMap<>()                         -> 43 00
     *   non-empty LinkedHashMap<String,String>  -> 2c ac ed 00 05 ...
     *
     * Empty maps use compact marker 0x43 + size 0.
     * Non-empty LinkedHashMap payloads are Java-serialized behind marker 0x2c.
     */
    private static final byte GEODE_HASH_MAP_CODE = 0x43;

    /*
     * Geode Java-serialized-object marker observed from Serializable POJO
     * and non-empty LinkedHashMap shapes:
     *
     *   2c ac ed 00 05 ...
     *
     * The bytes after 0x2c are standard Java ObjectOutputStream bytes.
     */
    private static final byte GEODE_JAVA_SERIALIZED_CODE = 0x2c;

    /*
     * Geode DataSerializer Object[] marker observed from ObjectArrayShapeTest:
     *
     *   34 <length> 2b 57 0010 java.lang.Object <elements...>
     *
     * We currently preserve Object[] as an opaque encoded payload.
     */
    private static final byte GEODE_OBJECT_ARRAY_CODE = 0x34;

    /*
     * Geode PDX / PdxInstance marker observed from PdxShapeTest:
     *
     *   PdxInstance -> 0x5d <payload...>
     *
     * PDX payloads are preserved opaquely because they can depend on Geode PDX
     * type metadata.
     */
    private static final byte GEODE_PDX_INSTANCE_CODE = 0x5d;

    /*
     * Geode DataSerializer object wrapper for DataSerializableFixedID.VERSIONED_OBJECT_LIST.
     *
     * The VersionedObjectList body begins after this wrapper with a flags byte.
     *
     *   01 07      -> DataSerializableFixedID object wrapper
     *   03         -> VersionedObjectList flags: has keys + has objects
     *
     * Do not instantiate VersionedObjectList in production code. In the shaded
     * runtime container, VersionedObjectList static initialization can fail due
     * to Geode LogService / Log4j caller-class lookup.
     */
    private static final byte[] VERSIONED_OBJECT_LIST_OBJECT_HEADER = new byte[] {
            0x01, 0x07
    };

    private static final byte VERSIONED_OBJECT_LIST_HAS_KEYS_AND_OBJECTS_FLAGS = 0x03;

    private GemResponseWriter() {
    }

    public static byte[] buildGetResponse(int txId, String value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(ValueEncoding.encodeGeodeStringValue(value), (byte) 1))
        );
    }

    public static byte[] buildBooleanGetResponse(int txId, boolean value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedBoolean(value), (byte) 1))
        );
    }

    public static byte[] buildCharacterGetResponse(int txId, char value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedCharacter(value), (byte) 1))
        );
    }

    public static byte[] buildByteGetResponse(int txId, byte value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedByte(value), (byte) 1))
        );
    }

    public static byte[] buildByteArrayGetResponse(int txId, byte[] value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedByteArray(value), (byte) 1))
        );
    }

    public static byte[] buildBooleanArrayGetResponse(int txId, boolean[] value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedBooleanArray(value), (byte) 1))
        );
    }

    public static byte[] buildCharArrayGetResponse(int txId, char[] value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedCharArray(value), (byte) 1))
        );
    }

    public static byte[] buildShortArrayGetResponse(int txId, short[] value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedShortArray(value), (byte) 1))
        );
    }

    public static byte[] buildIntArrayGetResponse(int txId, int[] value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedIntArray(value), (byte) 1))
        );
    }

    public static byte[] buildLongArrayGetResponse(int txId, long[] value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedLongArray(value), (byte) 1))
        );
    }

    public static byte[] buildFloatArrayGetResponse(int txId, float[] value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedFloatArray(value), (byte) 1))
        );
    }

    public static byte[] buildDoubleArrayGetResponse(int txId, double[] value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedDoubleArray(value), (byte) 1))
        );
    }

    public static byte[] buildStringArrayGetResponse(int txId, String[] value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedStringArray(value), (byte) 1))
        );
    }

    public static byte[] buildStringArrayListGetResponse(int txId, ArrayList<String> value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedStringArrayList(value), (byte) 1))
        );
    }

    public static byte[] buildStringHashMapGetResponse(int txId, LinkedHashMap<String, String> value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedStringHashMap(value), (byte) 1))
        );
    }

    public static byte[] buildStringObjectHashMapGetResponse(int txId, LinkedHashMap<String, Object> value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedStringObjectHashMap(value), (byte) 1))
        );
    }

    public static byte[] buildJavaSerializedObjectGetResponse(int txId, byte[] serializedValue) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedJavaObject(serializedValue), (byte) 1))
        );
    }

    public static byte[] buildObjectArrayGetResponse(int txId, byte[] encodedObjectArrayValue) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedObjectArray(encodedObjectArrayValue), (byte) 1))
        );
    }

    public static byte[] buildObjectArrayListGetResponse(int txId, byte[] encodedObjectArrayListValue) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedObjectArrayList(encodedObjectArrayListValue), (byte) 1))
        );
    }

    public static byte[] buildOpaqueGeodeValueGetResponse(int txId, byte[] encodedOpaqueGeodeValue) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedOpaqueGeodeValue(encodedOpaqueGeodeValue), (byte) 1))
        );
    }

    public static byte[] buildPdxInstanceGetResponse(int txId, byte[] encodedPdxInstanceValue) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedPdxInstance(encodedPdxInstanceValue), (byte) 1))
        );
    }

    public static byte[] buildPdxTypeIdResponse(int txId, int typeId) {
        /*
         * GetPDXIdForTypeOp expects a raw BYTE part and calls Part.getInt().
         * Do not use geodeSerializedInteger(...) here, because that produces
         * an OBJECT_CODE part containing the DataSerializer integer marker 0x39.
         */
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(intPartBytes(typeId), (byte) 0))
        );
    }

    public static byte[] buildShortGetResponse(int txId, short value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedShort(value), (byte) 1))
        );
    }

    public static byte[] buildIntegerGetResponse(int txId, int value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedInteger(value), (byte) 1))
        );
    }

    public static byte[] buildLongGetResponse(int txId, long value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedLong(value), (byte) 1))
        );
    }

    public static byte[] buildFloatGetResponse(int txId, float value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedFloat(value), (byte) 1))
        );
    }

    public static byte[] buildDoubleGetResponse(int txId, double value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedDouble(value), (byte) 1))
        );
    }

    public static byte[] buildDateGetResponse(int txId, Date value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedDate(value), (byte) 1))
        );
    }

    public static byte[] buildNullGetResponse(int txId) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(new byte[0], (byte) 0))
        );
    }

    public static byte[] buildPutResponse(int txId) {
        return buildMessage(
                MessageTypes.REPLY,
                txId,
                List.of(
                        new Part(PUT_REPLY_PART1, (byte) 0),
                        new Part(PUT_REPLY_PART2, (byte) 0),
                        new Part(PUT_REPLY_PART3, (byte) 1)
                )
        );
    }

    public static byte[] buildRemoveResponse(int txId) {
        return buildMessage(
                MessageTypes.REPLY,
                txId,
                List.of(
                        new Part(REMOVE_REPLY_PART1, (byte) 0),
                        new Part(REMOVE_REPLY_PART2, (byte) 1),
                        new Part(REMOVE_REPLY_PART3, (byte) 0),
                        new Part(REMOVE_REPLY_PART4, (byte) 0)
                )
        );
    }

    public static byte[] buildSimpleAck(int txId) {
        return buildMessage(
                MessageTypes.REPLY,
                txId,
                List.of(new Part(new byte[] {0x00}, (byte) 0))
        );
    }

    public static byte[] buildContainsResponse(int txId, boolean contains) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedBoolean(contains), (byte) 1))
        );
    }

    public static byte[] buildSizeResponse(int txId, int size) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedInteger(size), (byte) 1))
        );
    }

    /**
     * GET_ALL is returned as a chunked response whose single object part contains
     * a full Geode-serialized VersionedObjectList-compatible payload.
     *
     * Shape:
     *
     *   01 07 03
     *   <key-count>
     *   <geode-string-key-1>
     *   <geode-string-key-2>
     *   ...
     *   <object-count>
     *   <object-marker> <geode-object>
     *   <object-marker> <geode-object>
     *   ...
     *
     * Object markers:
     *
     *   0x01 = present object
     *   0x03 = key not at server / absent
     */
    public static byte[] buildGetAllChunkedResponse(int txId, List<String> keys, Map<String, ?> values) {
        byte[] versionedObjectListPayload = buildManualVersionedObjectListPayload(keys, values);

        return buildSingleChunkedMessage(
                MessageTypes.RESPONSE,
                txId,
                new Part(versionedObjectListPayload, (byte) 1)
        );
    }

    public static byte[] buildPutAllChunkedResponse(int txId) {
        return buildSingleChunkedMessage(
                MessageTypes.RESPONSE,
                txId,
                new Part(new byte[] {0x00}, (byte) 0)
        );
    }

    public static byte[] buildKeySetChunkedResponse(int txId, List<String> keys) {
        byte[] payload = buildManualStringListPayload(keys);

        return buildSingleChunkedMessage(
                MessageTypes.RESPONSE,
                txId,
                new Part(payload, (byte) 1)
        );
    }

    private static byte[] buildManualVersionedObjectListPayload(List<String> keys, Map<String, ?> values) {
        ByteBuf buf = Unpooled.buffer();

        try {
            int size = keys == null ? 0 : keys.size();

            /*
             * DataSerializer object wrapper for DataSerializableFixedID.VERSIONED_OBJECT_LIST.
             *
             * VersionedObjectList.toData(...) starts after this wrapper with a flags byte.
             */
            buf.writeBytes(VERSIONED_OBJECT_LIST_OBJECT_HEADER);

            /*
             * VersionedObjectList body flags.
             *
             * 0x01 = has keys
             * 0x02 = has objects
             * 0x03 = has keys + has objects
             */
            buf.writeByte(VERSIONED_OBJECT_LIST_HAS_KEYS_AND_OBJECTS_FLAGS);

            /*
             * Keys section.
             *
             * VersionedObjectList uses InternalDataSerializer.writeUnsignedVL(...),
             * not the normal DataSerializer array/list length encoding used by
             * ArrayList/String[]/primitive-array payloads.
             */
            writeVersionedObjectListCount(buf, size);

            if (keys != null) {
                for (String key : keys) {
                    buf.writeBytes(ValueEncoding.encodeGeodeStringValue(key));
                }
            }

            /*
             * Objects section.
             */
            writeVersionedObjectListCount(buf, size);

            if (keys != null) {
                for (String key : keys) {
                    Object rawValue = values == null ? null : values.get(key);
                    StoredValue value = normalizeStoredValue(rawValue);

                    if (value == null) {
                        buf.writeByte(0x03); // KEY_NOT_AT_SERVER
                        buf.writeByte(0x29); // Geode null marker
                    } else {
                        buf.writeByte(0x01); // OBJECT
                        buf.writeBytes(encodeStoredValueForGetAll(value));
                    }
                }
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static StoredValue normalizeStoredValue(Object rawValue) {
        if (rawValue == null) {
            return null;
        }

        if (rawValue instanceof StoredValue storedValue) {
            return storedValue;
        }

        if (rawValue instanceof byte[] byteArrayValue) {
            return StoredValue.byteArrayValue(byteArrayValue);
        }

        if (rawValue instanceof boolean[] booleanArrayValue) {
            return StoredValue.booleanArrayValue(booleanArrayValue);
        }

        if (rawValue instanceof char[] charArrayValue) {
            return StoredValue.charArrayValue(charArrayValue);
        }

        if (rawValue instanceof short[] shortArrayValue) {
            return StoredValue.shortArrayValue(shortArrayValue);
        }

        if (rawValue instanceof int[] intArrayValue) {
            return StoredValue.intArrayValue(intArrayValue);
        }

        if (rawValue instanceof long[] longArrayValue) {
            return StoredValue.longArrayValue(longArrayValue);
        }

        if (rawValue instanceof float[] floatArrayValue) {
            return StoredValue.floatArrayValue(floatArrayValue);
        }

        if (rawValue instanceof double[] doubleArrayValue) {
            return StoredValue.doubleArrayValue(doubleArrayValue);
        }

        if (rawValue instanceof String[] stringArrayValue) {
            return StoredValue.stringArrayValue(stringArrayValue);
        }

        if (rawValue instanceof ArrayList<?> arrayListValue && isStringArrayList(arrayListValue)) {
            return StoredValue.stringArrayListValue(toStringArrayList(arrayListValue));
        }

        if (rawValue instanceof Map<?, ?> mapValue && isStringStringMap(mapValue)) {
            return StoredValue.stringHashMapValue(toStringStringLinkedHashMap(mapValue));
        }

        if (rawValue instanceof Map<?, ?> mapValue && isSupportedStringObjectMap(mapValue)) {
            return StoredValue.stringObjectHashMapValue(toStringObjectLinkedHashMap(mapValue));
        }

        if (isSupportedJavaSerializableObject(rawValue)) {
            return StoredValue.javaSerializedObjectValue(
                    rawValue.getClass().getName(),
                    javaSerializedBytes(rawValue)
            );
        }

        if (rawValue instanceof Boolean bool) {
            return StoredValue.booleanValue(bool);
        }

        if (rawValue instanceof Character characterValue) {
            return StoredValue.characterValue(characterValue);
        }

        if (rawValue instanceof Byte byteValue) {
            return StoredValue.byteValue(byteValue);
        }

        if (rawValue instanceof Short shortValue) {
            return StoredValue.shortValue(shortValue);
        }

        if (rawValue instanceof Integer integer) {
            return StoredValue.integerValue(integer);
        }

        if (rawValue instanceof Long longValue) {
            return StoredValue.longValue(longValue);
        }

        if (rawValue instanceof Float floatValue) {
            return StoredValue.floatValue(floatValue);
        }

        if (rawValue instanceof Double doubleValue) {
            return StoredValue.doubleValue(doubleValue);
        }

        if (rawValue instanceof Date dateValue) {
            return StoredValue.dateValue(dateValue);
        }

        return StoredValue.stringValue(String.valueOf(rawValue));
    }

    private static byte[] encodeStoredValueForGetAll(StoredValue value) {
        if (value.type() == StoredValue.Type.BOOLEAN) {
            return geodeSerializedBoolean(value.asBoolean());
        }

        if (value.type() == StoredValue.Type.CHARACTER) {
            return geodeSerializedCharacter(value.asCharacter());
        }

        if (value.type() == StoredValue.Type.BYTE) {
            return geodeSerializedByte(value.asByte());
        }

        if (value.type() == StoredValue.Type.BYTE_ARRAY) {
            return geodeSerializedByteArray(value.asByteArray());
        }

        if (value.type() == StoredValue.Type.BOOLEAN_ARRAY) {
            return geodeSerializedBooleanArray(value.asBooleanArray());
        }

        if (value.type() == StoredValue.Type.CHAR_ARRAY) {
            return geodeSerializedCharArray(value.asCharArray());
        }

        if (value.type() == StoredValue.Type.SHORT_ARRAY) {
            return geodeSerializedShortArray(value.asShortArray());
        }

        if (value.type() == StoredValue.Type.INT_ARRAY) {
            return geodeSerializedIntArray(value.asIntArray());
        }

        if (value.type() == StoredValue.Type.LONG_ARRAY) {
            return geodeSerializedLongArray(value.asLongArray());
        }

        if (value.type() == StoredValue.Type.FLOAT_ARRAY) {
            return geodeSerializedFloatArray(value.asFloatArray());
        }

        if (value.type() == StoredValue.Type.DOUBLE_ARRAY) {
            return geodeSerializedDoubleArray(value.asDoubleArray());
        }

        if (value.type() == StoredValue.Type.STRING_ARRAY) {
            return geodeSerializedStringArray(value.asStringArray());
        }

        if (value.type() == StoredValue.Type.STRING_ARRAY_LIST) {
            return geodeSerializedStringArrayList(value.asStringArrayList());
        }

        if (value.type() == StoredValue.Type.STRING_HASH_MAP) {
            return geodeSerializedStringHashMap(value.asStringHashMap());
        }

        if (value.type() == StoredValue.Type.STRING_OBJECT_HASH_MAP) {
            return geodeSerializedStringObjectHashMap(value.asStringObjectHashMap());
        }

        if (value.type() == StoredValue.Type.JAVA_SERIALIZED_OBJECT) {
            return geodeSerializedJavaObject(value.asJavaSerializedValue());
        }

        if (value.type() == StoredValue.Type.OBJECT_ARRAY) {
            return geodeSerializedObjectArray(value.asObjectArrayValue());
        }

        if (value.type() == StoredValue.Type.OBJECT_ARRAY_LIST) {
            return geodeSerializedObjectArrayList(value.asObjectArrayListValue());
        }

        if (value.type() == StoredValue.Type.OPAQUE_GEODE_VALUE) {
            return geodeSerializedOpaqueGeodeValue(value.asOpaqueGeodeValue());
        }

        if (value.type() == StoredValue.Type.PDX_INSTANCE) {
            return geodeSerializedPdxInstance(value.asPdxInstanceValue());
        }

        if (value.type() == StoredValue.Type.SHORT) {
            return geodeSerializedShort(value.asShort());
        }

        if (value.type() == StoredValue.Type.INTEGER) {
            return geodeSerializedInteger(value.asInteger());
        }

        if (value.type() == StoredValue.Type.LONG) {
            return geodeSerializedLong(value.asLong());
        }

        if (value.type() == StoredValue.Type.FLOAT) {
            return geodeSerializedFloat(value.asFloat());
        }

        if (value.type() == StoredValue.Type.DOUBLE) {
            return geodeSerializedDouble(value.asDouble());
        }

        if (value.type() == StoredValue.Type.DATE) {
            return geodeSerializedDate(value.asDate());
        }

        return ValueEncoding.encodeGeodeStringValue(value.value());
    }

    private static byte[] buildManualStringListPayload(List<String> keys) {
        ByteBuf buf = Unpooled.buffer();

        try {
            int size = keys == null ? 0 : keys.size();

            buf.writeByte(GEODE_LIST_CODE);
            writeGeodeArrayLength(buf, size);

            if (keys != null) {
                for (String key : keys) {
                    buf.writeBytes(ValueEncoding.encodeGeodeStringValue(key));
                }
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static void writeGeodeArrayLength(ByteBuf buf, int count) {
        /*
         * Geode/DataSerializer array/list length encoding.
         *
         * For ArrayList/String[]/primitive-array style payloads, Geode uses a compact
         * array-length encoding:
         *
         *   0..252   -> one byte containing the count
         *   0xfd     -> four following bytes contain the count
         *   0xfe     -> two following bytes contain the count
         *   0xff     -> null array/list marker
         *
         * A previous implementation switched at 127 and used 0x81/0x82/0x84 as
         * length markers. That corrupts ArrayList payloads once keySetOnServer returns
         * more than 127 keys. The client then loses byte alignment and starts reading
         * bytes from key strings as serialization headers, producing errors like:
         *
         *   unexpected typeCode: 98
         */
        if (count < 0) {
            throw new IllegalArgumentException("Count must not be negative. Actual: " + count);
        }

        if (count <= 252) {
            buf.writeByte((byte) count);
            return;
        }

        if (count <= 0xffff) {
            buf.writeByte((byte) 0xfe);
            buf.writeShort(count);
            return;
        }

        buf.writeByte((byte) 0xfd);
        buf.writeInt(count);
    }

    private static void writeVersionedObjectListCount(ByteBuf buf, int count) {
        /*
         * VersionedObjectList count encoding.
         *
         * Geode VersionedObjectList.toData(...) writes counts with:
         *
         *   InternalDataSerializer.writeUnsignedVL(count, out)
         *
         * and VersionedObjectList.fromData(...) reads them with:
         *
         *   InternalDataSerializer.readUnsignedVL(in)
         *
         * This is unsigned variable-length integer encoding:
         *
         *   0      -> 00
         *   1      -> 01
         *   127    -> 7f
         *   128    -> 80 01
         *   150    -> 96 01
         *   253    -> fd 01
         *
         * Keep this separate from writeGeodeArrayLength(...), which is the
         * DataSerializer array/list length encoding used by keySetOnServer's
         * manually encoded ArrayList payload.
         */
        if (count < 0) {
            throw new IllegalArgumentException("Count must not be negative. Actual: " + count);
        }

        int value = count;

        while (true) {
            if ((value & ~0x7f) == 0) {
                buf.writeByte(value);
                return;
            }

            buf.writeByte((value & 0x7f) | 0x80);
            value >>>= 7;
        }
    }

    private static byte[] geodeSerializedByteArray(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("byte[] value must not be null");
        }

        if (value.length > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated byte[] writer currently supports lengths from 0 to 127. Actual: " + value.length
            );
        }

        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_BYTE_ARRAY_CODE);
            buf.writeByte((byte) value.length);
            buf.writeBytes(value);

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedBooleanArray(boolean[] value) {
        if (value == null) {
            throw new IllegalArgumentException("boolean[] value must not be null");
        }

        if (value.length > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated boolean[] writer currently supports lengths from 0 to 127. Actual: " + value.length
            );
        }

        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_BOOLEAN_ARRAY_CODE);
            buf.writeByte((byte) value.length);

            for (boolean item : value) {
                buf.writeByte(item ? 0x01 : 0x00);
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedCharArray(char[] value) {
        if (value == null) {
            throw new IllegalArgumentException("char[] value must not be null");
        }

        if (value.length > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated char[] writer currently supports lengths from 0 to 127. Actual: " + value.length
            );
        }

        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_CHAR_ARRAY_CODE);
            buf.writeByte((byte) value.length);

            for (char item : value) {
                buf.writeChar(item);
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedShortArray(short[] value) {
        if (value == null) {
            throw new IllegalArgumentException("short[] value must not be null");
        }

        if (value.length > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated short[] writer currently supports lengths from 0 to 127. Actual: " + value.length
            );
        }

        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_SHORT_ARRAY_CODE);
            buf.writeByte((byte) value.length);

            for (short item : value) {
                buf.writeShort(item);
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedIntArray(int[] value) {
        if (value == null) {
            throw new IllegalArgumentException("int[] value must not be null");
        }

        if (value.length > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated int[] writer currently supports lengths from 0 to 127. Actual: " + value.length
            );
        }

        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_INT_ARRAY_CODE);
            buf.writeByte((byte) value.length);

            for (int item : value) {
                buf.writeInt(item);
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedLongArray(long[] value) {
        if (value == null) {
            throw new IllegalArgumentException("long[] value must not be null");
        }

        if (value.length > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated long[] writer currently supports lengths from 0 to 127. Actual: " + value.length
            );
        }

        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_LONG_ARRAY_CODE);
            buf.writeByte((byte) value.length);

            for (long item : value) {
                buf.writeLong(item);
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedFloatArray(float[] value) {
        if (value == null) {
            throw new IllegalArgumentException("float[] value must not be null");
        }

        if (value.length > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated float[] writer currently supports lengths from 0 to 127. Actual: " + value.length
            );
        }

        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_FLOAT_ARRAY_CODE);
            buf.writeByte((byte) value.length);

            for (float item : value) {
                buf.writeInt(Float.floatToRawIntBits(item));
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedDoubleArray(double[] value) {
        if (value == null) {
            throw new IllegalArgumentException("double[] value must not be null");
        }

        if (value.length > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated double[] writer currently supports lengths from 0 to 127. Actual: " + value.length
            );
        }

        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_DOUBLE_ARRAY_CODE);
            buf.writeByte((byte) value.length);

            for (double item : value) {
                buf.writeLong(Double.doubleToRawLongBits(item));
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }



    private static byte[] geodeSerializedStringArray(String[] value) {
        if (value == null) {
            throw new IllegalArgumentException("String[] value must not be null");
        }

        if (value.length > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated String[] writer currently supports lengths from 0 to 127. Actual: " + value.length
            );
        }

        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_STRING_ARRAY_CODE);
            buf.writeByte((byte) value.length);

            for (String item : value) {
                if (item == null) {
                    buf.writeByte(GEODE_NULL_STRING_ARRAY_ELEMENT_CODE);
                } else {
                    buf.writeBytes(ValueEncoding.encodeGeodeStringValue(item));
                }
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedStringArrayList(ArrayList<String> value) {
        if (value == null) {
            throw new IllegalArgumentException("ArrayList<String> value must not be null");
        }

        if (value.size() > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated ArrayList<String> writer currently supports sizes from 0 to 127. Actual: " + value.size()
            );
        }

        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_LIST_CODE);
            buf.writeByte((byte) value.size());

            for (String item : value) {
                if (item == null) {
                    buf.writeByte(GEODE_NULL_CODE);
                } else {
                    buf.writeBytes(ValueEncoding.encodeGeodeStringValue(item));
                }
            }

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedStringHashMap(LinkedHashMap<String, String> value) {
        if (value == null) {
            throw new IllegalArgumentException("LinkedHashMap<String, String> value must not be null");
        }

        if (value.size() > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated LinkedHashMap<String,String> writer currently supports sizes from 0 to 127. Actual: "
                            + value.size()
            );
        }

        /*
         * Match the compact empty HashMap shape observed from Geode:
         *
         *   new HashMap<>() -> 43 00
         */
        if (value.isEmpty()) {
            return new byte[] {
                    GEODE_HASH_MAP_CODE,
                    0x00
            };
        }

        /*
         * Match Geode's non-empty LinkedHashMap shape without invoking Geode
         * DataSerializer in the shim process:
         *
         *   2c + Java ObjectOutputStream bytes
         *
         * This avoids DataSerializer static initialization failures while still
         * producing the same wire shape the Geode client expects.
         */
        try {
            ByteArrayOutputStream javaBytes = new ByteArrayOutputStream();

            try (ObjectOutputStream out = new ObjectOutputStream(javaBytes)) {
                out.writeObject(value);
            }

            byte[] serialized = javaBytes.toByteArray();
            byte[] framed = new byte[serialized.length + 1];

            framed[0] = 0x2c;
            System.arraycopy(serialized, 0, framed, 1, serialized.length);

            return framed;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize LinkedHashMap<String,String>", e);
        }
    }

    private static byte[] geodeSerializedStringObjectHashMap(LinkedHashMap<String, Object> value) {
        if (value == null) {
            throw new IllegalArgumentException("LinkedHashMap<String, Object> value must not be null");
        }

        if (value.size() > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated LinkedHashMap<String,Object> writer currently supports sizes from 0 to 127. Actual: "
                            + value.size()
            );
        }

        if (!isSupportedStringObjectMap(value)) {
            throw new IllegalArgumentException("LinkedHashMap<String,Object> contains unsupported key/value types");
        }

        /*
         * Match the compact empty HashMap shape observed from Geode:
         *
         *   new HashMap<>() -> 43 00
         */
        if (value.isEmpty()) {
            return new byte[] {
                    GEODE_HASH_MAP_CODE,
                    0x00
            };
        }

        /*
         * Match Geode's non-empty LinkedHashMap<String,Object> shape:
         *
         *   2c + Java ObjectOutputStream bytes
         */
        try {
            ByteArrayOutputStream javaBytes = new ByteArrayOutputStream();

            try (ObjectOutputStream out = new ObjectOutputStream(javaBytes)) {
                out.writeObject(value);
            }

            byte[] serialized = javaBytes.toByteArray();
            byte[] framed = new byte[serialized.length + 1];

            framed[0] = 0x2c;
            System.arraycopy(serialized, 0, framed, 1, serialized.length);

            return framed;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize LinkedHashMap<String,Object>", e);
        }
    }

    private static byte[] geodeSerializedJavaObject(byte[] serializedValue) {
        if (serializedValue == null || serializedValue.length == 0) {
            throw new IllegalArgumentException("Java serialized object bytes must not be null or empty");
        }

        /*
         * StoredValue keeps the raw ObjectOutputStream bytes without the Geode
         * marker. Reattach 0x2c for the Geode client response:
         *
         *   2c + ac ed 00 05 ...
         */
        byte[] framed = new byte[serializedValue.length + 1];

        framed[0] = GEODE_JAVA_SERIALIZED_CODE;
        System.arraycopy(serializedValue, 0, framed, 1, serializedValue.length);

        return framed;
    }

    private static byte[] geodeSerializedObjectArray(byte[] encodedObjectArrayValue) {
        if (encodedObjectArrayValue == null || encodedObjectArrayValue.length == 0) {
            throw new IllegalArgumentException("Object[] encoded bytes must not be null or empty");
        }

        if (encodedObjectArrayValue[0] != GEODE_OBJECT_ARRAY_CODE) {
            throw new IllegalArgumentException("Object[] encoded bytes must start with Geode Object[] marker 0x34");
        }

        byte[] copy = new byte[encodedObjectArrayValue.length];
        System.arraycopy(encodedObjectArrayValue, 0, copy, 0, encodedObjectArrayValue.length);

        return copy;
    }

    private static byte[] geodeSerializedObjectArrayList(byte[] encodedObjectArrayListValue) {
        if (encodedObjectArrayListValue == null || encodedObjectArrayListValue.length == 0) {
            throw new IllegalArgumentException("ArrayList<Object> encoded bytes must not be null or empty");
        }

        if (encodedObjectArrayListValue[0] != GEODE_LIST_CODE) {
            throw new IllegalArgumentException(
                    "ArrayList<Object> encoded bytes must start with Geode ArrayList/List marker 0x41"
            );
        }

        byte[] copy = new byte[encodedObjectArrayListValue.length];
        System.arraycopy(encodedObjectArrayListValue, 0, copy, 0, encodedObjectArrayListValue.length);

        return copy;
    }

    private static byte[] geodeSerializedOpaqueGeodeValue(byte[] encodedOpaqueGeodeValue) {
        if (encodedOpaqueGeodeValue == null || encodedOpaqueGeodeValue.length == 0) {
            throw new IllegalArgumentException("Opaque Geode encoded bytes must not be null or empty");
        }

        /*
         * Opaque standalone utility values already include their original Geode
         * DataSerializer marker. Return the exact payload unchanged so the Geode
         * client deserializes the original type.
         *
         * Currently used for standalone utility markers:
         *
         *   BigInteger -> 0x5f
         *   BigDecimal -> 0x60
         *   UUID       -> 0x62
         *   Enum       -> 0x65
         */
        byte[] copy = new byte[encodedOpaqueGeodeValue.length];
        System.arraycopy(encodedOpaqueGeodeValue, 0, copy, 0, encodedOpaqueGeodeValue.length);

        return copy;
    }

    private static byte[] geodeSerializedPdxInstance(byte[] encodedPdxInstanceValue) {
        if (encodedPdxInstanceValue == null || encodedPdxInstanceValue.length == 0) {
            throw new IllegalArgumentException("PDX encoded bytes must not be null or empty");
        }

        if (encodedPdxInstanceValue[0] != GEODE_PDX_INSTANCE_CODE) {
            throw new IllegalArgumentException("PDX encoded bytes must start with Geode PDX marker 0x5d");
        }

        /*
         * PDX payloads already include their original Geode marker. Return the
         * exact payload unchanged so the Geode client can deserialize it using
         * its PDX handling and type metadata.
         */
        byte[] copy = new byte[encodedPdxInstanceValue.length];
        System.arraycopy(encodedPdxInstanceValue, 0, copy, 0, encodedPdxInstanceValue.length);

        return copy;
    }



    private static byte[] javaSerializedBytes(Object value) {
        if (!(value instanceof Serializable)) {
            throw new IllegalArgumentException("Value must implement Serializable");
        }

        try {
            ByteArrayOutputStream javaBytes = new ByteArrayOutputStream();

            try (ObjectOutputStream out = new ObjectOutputStream(javaBytes)) {
                out.writeObject(value);
            }

            return javaBytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to Java-serialize object of type " + value.getClass().getName(), e);
        }
    }





    private static boolean isSupportedJavaSerializableObject(Object value) {
        if (!(value instanceof Serializable)) {
            return false;
        }

        if (value instanceof String
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof Date
                || value instanceof byte[]
                || value instanceof boolean[]
                || value instanceof char[]
                || value instanceof short[]
                || value instanceof int[]
                || value instanceof long[]
                || value instanceof float[]
                || value instanceof double[]
                || value instanceof String[]
                || value instanceof ArrayList<?>
                || value instanceof Map<?, ?>
                || value instanceof StoredValue) {
            return false;
        }

        return true;
    }

    private static boolean isStringArrayList(ArrayList<?> value) {
        for (Object item : value) {
            if (item != null && !(item instanceof String)) {
                return false;
            }
        }

        return true;
    }

    private static ArrayList<String> toStringArrayList(ArrayList<?> value) {
        ArrayList<String> out = new ArrayList<>(value.size());

        for (Object item : value) {
            out.add(item == null ? null : String.valueOf(item));
        }

        return out;
    }

    private static boolean isStringStringMap(Map<?, ?> value) {
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();
            Object mapValue = entry.getValue();

            if (key != null && !(key instanceof String)) {
                return false;
            }

            if (mapValue != null && !(mapValue instanceof String)) {
                return false;
            }
        }

        return true;
    }

    private static LinkedHashMap<String, String> toStringStringLinkedHashMap(Map<?, ?> value) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();
            Object mapValue = entry.getValue();

            out.put(
                    key == null ? null : String.valueOf(key),
                    mapValue == null ? null : String.valueOf(mapValue)
            );
        }

        return out;
    }

    private static boolean isSupportedStringObjectMap(Map<?, ?> value) {
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();
            Object mapValue = entry.getValue();

            if (key != null && !(key instanceof String)) {
                return false;
            }

            if (!isSupportedMapObjectValue(mapValue)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isSupportedMapObjectValue(Object value) {
        return value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof Date
                || value instanceof byte[]
                || value instanceof boolean[]
                || value instanceof char[]
                || value instanceof short[]
                || value instanceof int[]
                || value instanceof long[]
                || value instanceof float[]
                || value instanceof double[]
                || value instanceof String[]
                || isSupportedStringArrayListObject(value);
    }

    private static boolean isSupportedStringArrayListObject(Object value) {
        if (!(value instanceof ArrayList<?> list)) {
            return false;
        }

        for (Object item : list) {
            if (item != null && !(item instanceof String)) {
                return false;
            }
        }

        return true;
    }

    private static LinkedHashMap<String, Object> toStringObjectLinkedHashMap(Map<?, ?> value) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();

            out.put(
                    key == null ? null : String.valueOf(key),
                    copySupportedMapObjectValue(entry.getValue())
            );
        }

        return out;
    }

    private static Object copySupportedMapObjectValue(Object value) {
        if (value instanceof byte[] bytes) {
            byte[] copy = new byte[bytes.length];
            System.arraycopy(bytes, 0, copy, 0, bytes.length);
            return copy;
        }

        if (value instanceof boolean[] booleans) {
            boolean[] copy = new boolean[booleans.length];
            System.arraycopy(booleans, 0, copy, 0, booleans.length);
            return copy;
        }

        if (value instanceof char[] chars) {
            char[] copy = new char[chars.length];
            System.arraycopy(chars, 0, copy, 0, chars.length);
            return copy;
        }

        if (value instanceof short[] shorts) {
            short[] copy = new short[shorts.length];
            System.arraycopy(shorts, 0, copy, 0, shorts.length);
            return copy;
        }

        if (value instanceof int[] ints) {
            int[] copy = new int[ints.length];
            System.arraycopy(ints, 0, copy, 0, ints.length);
            return copy;
        }

        if (value instanceof long[] longs) {
            long[] copy = new long[longs.length];
            System.arraycopy(longs, 0, copy, 0, longs.length);
            return copy;
        }

        if (value instanceof float[] floats) {
            float[] copy = new float[floats.length];
            System.arraycopy(floats, 0, copy, 0, floats.length);
            return copy;
        }

        if (value instanceof double[] doubles) {
            double[] copy = new double[doubles.length];
            System.arraycopy(doubles, 0, copy, 0, doubles.length);
            return copy;
        }

        if (value instanceof String[] strings) {
            String[] copy = new String[strings.length];
            System.arraycopy(strings, 0, copy, 0, strings.length);
            return copy;
        }

        if (value instanceof ArrayList<?> list) {
            ArrayList<String> copy = new ArrayList<>(list.size());

            for (Object item : list) {
                copy.add(item == null ? null : String.valueOf(item));
            }

            return copy;
        }

        if (value instanceof Date date) {
            return new Date(date.getTime());
        }

        return value;
    }

    private static byte[] intPartBytes(int value) {
        return new byte[] {
                (byte) ((value >>> 24) & 0xff),
                (byte) ((value >>> 16) & 0xff),
                (byte) ((value >>> 8) & 0xff),
                (byte) (value & 0xff)
        };
    }

    private static byte[] geodeSerializedBoolean(boolean value) {
        return new byte[] {
                GEODE_BOOLEAN_CODE,
                (byte) (value ? 0x01 : 0x00)
        };
    }

    private static byte[] geodeSerializedCharacter(char value) {
        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_CHARACTER_CODE);
            buf.writeChar(value);

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedByte(byte value) {
        return new byte[] {
                GEODE_BYTE_CODE,
                value
        };
    }

    private static byte[] geodeSerializedShort(short value) {
        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_SHORT_CODE);
            buf.writeShort(value);

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedInteger(int value) {
        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_INTEGER_CODE);
            buf.writeInt(value);

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedLong(long value) {
        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_LONG_CODE);
            buf.writeLong(value);

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedFloat(float value) {
        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_FLOAT_CODE);
            buf.writeInt(Float.floatToRawIntBits(value));

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedDouble(double value) {
        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_DOUBLE_CODE);
            buf.writeLong(Double.doubleToRawLongBits(value));

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] geodeSerializedDate(Date value) {
        ByteBuf buf = Unpooled.buffer();

        try {
            buf.writeByte(GEODE_DATE_CODE);
            buf.writeLong(value.getTime());

            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static byte[] buildMessage(int messageType, int txId, List<Part> parts) {
        int messageLength = computeMessageLength(parts);

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(messageType);
        buf.writeInt(messageLength);
        buf.writeInt(parts.size());
        buf.writeInt(txId);
        buf.writeByte((byte) 0);

        for (Part part : parts) {
            writePart(buf, part.payload(), part.typeCode());
        }

        return toByteArrayAndRelease(buf);
    }

    private static byte[] buildSingleChunkedMessage(int messageType, int txId, Part part) {
        int numberOfParts = 1;
        int chunkLength = 4 + 1 + part.payload().length;
        byte lastChunkFlag = 0x01;

        ByteBuf buf = Unpooled.buffer();

        buf.writeInt(messageType);
        buf.writeInt(numberOfParts);
        buf.writeInt(txId);

        buf.writeInt(chunkLength);
        buf.writeByte(lastChunkFlag);
        writePart(buf, part.payload(), part.typeCode());

        return toByteArrayAndRelease(buf);
    }

    private static int computeMessageLength(List<Part> parts) {
        int total = 0;

        for (Part part : parts) {
            total += 4;
            total += 1;
            total += part.payload().length;
        }

        return total;
    }

    private static void writePart(ByteBuf buf, byte[] payload, byte typeCode) {
        buf.writeInt(payload.length);
        buf.writeByte(typeCode);
        buf.writeBytes(payload);
    }

    private static byte[] toByteArrayAndRelease(ByteBuf buf) {
        try {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private record Part(byte[] payload, byte typeCode) {
    }
}
