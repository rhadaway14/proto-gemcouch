package com.protogemcouch.wire;

import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.ByteUtils;
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
     * A valid, zero-region org.apache.geode.internal.cache.TXCommitMessage (DSFID 110 = 0x6e),
     * serialized by Geode's own DataSerializer. It is the COMMIT reply the client deserializes via
     * CommitOp.processObjResponse; with no region content changes there is nothing for the (proxy)
     * client to apply locally, so this fixed skeleton — carrying the shim's stable committing-member
     * identity — is accepted for any commit. The only per-commit dynamic field is TXId.uniqId, the
     * four bytes at offset 6 (after the 01 6e header and the int processorId), which buildCommitResponse
     * patches to the transaction id. Captured + round-trip-validated via TxCommitProbe against a real
     * Geode 1.15 server.
     */
    private static final byte[] TX_COMMIT_MESSAGE_TEMPLATE = ByteUtils.hex(
            "016e0000000000000001040a0000bc0000e29a570014686f73742e646f636b65722e696e7465726e616c09000000000000a1ec0d0057000057000862306436393139375700000000012cff0096000000000000000000000000000000000000000000001904c0a8a0020000a029050a57000131570007736572766572310000000000052dff0000000000000000000100000000012656015c040a0000bc0000e29a570014686f73742e646f636b65722e696e7465726e616c09000000000000a1ec0d0057000057000862306436393139375700000000012cff0096000000000000000000000000000000000000000001ff");

    private static final int TX_COMMIT_UNIQ_ID_OFFSET = 6;

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

    /**
     * PUT reply that carries an old value, for {@code putIfAbsent}/{@code replace} which return the
     * prior value to the client. The Geode client reads part[1] as a flags int: bit {@code 0x01}
     * means "old value present" (read as the object in part[2]). We keep the existing {@code 0x04}
     * bit and add {@code 0x01}, append the old value as an object part, then the version tag — so
     * the version tag lands at the next index the client expects (after the old value).
     */
    public static byte[] buildPutResponseWithOldValue(int txId, StoredValue oldValue) {
        return buildMessage(
                MessageTypes.REPLY,
                txId,
                List.of(
                        new Part(PUT_REPLY_PART1, (byte) 0),
                        new Part(new byte[] {0x00, 0x00, 0x00, 0x05}, (byte) 0),
                        new Part(encodeStoredValueForGetAll(oldValue), (byte) 1),
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

    /**
     * DESTROY reply for {@code remove(key, value)} carrying the {@code entryNotFound} flag. The Geode
     * client reads part[0] flags (bit 0x01 = version tag at part[1], bit 0x02 = entryNotFound), then
     * the {@code entryNotFound} int — at part[2] when partitioned single-hop is off, or part[3] when
     * it is on (the single-hop metadata read consumes the intervening index). We write the flag into
     * both slots so it is read correctly either way; a leading 0x00 byte makes the single-hop
     * metadata read at part[2] a harmless no-op. When set, the client raises EntryNotFoundException,
     * which {@code Region.remove(k,v)} maps to {@code false}.
     */
    public static byte[] buildRemoveResponseWithEntryNotFound(int txId, boolean entryNotFound) {
        byte[] flag = {0x00, 0x00, 0x00, (byte) (entryNotFound ? 0x01 : 0x00)};
        return buildMessage(
                MessageTypes.REPLY,
                txId,
                List.of(
                        new Part(REMOVE_REPLY_PART1, (byte) 0),
                        new Part(REMOVE_REPLY_PART2, (byte) 1),
                        new Part(flag, (byte) 0),
                        new Part(flag, (byte) 0)
                )
        );
    }

    /**
     * COMMIT reply: a RESPONSE message whose single object part is a zero-region TXCommitMessage with
     * its {@code TXId.uniqId} set to {@code txId}. The shim has already applied the transaction's
     * buffered writes to storage; the client reads this as the authoritative "committed" ack.
     */
    public static byte[] buildCommitResponse(int txId) {
        byte[] obj = TX_COMMIT_MESSAGE_TEMPLATE.clone();
        obj[TX_COMMIT_UNIQ_ID_OFFSET] = (byte) (txId >>> 24);
        obj[TX_COMMIT_UNIQ_ID_OFFSET + 1] = (byte) (txId >>> 16);
        obj[TX_COMMIT_UNIQ_ID_OFFSET + 2] = (byte) (txId >>> 8);
        obj[TX_COMMIT_UNIQ_ID_OFFSET + 3] = (byte) txId;
        return buildMessage(MessageTypes.RESPONSE, txId, List.of(new Part(obj, (byte) 1)));
    }

    /**
     * ROLLBACK reply: a plain REPLY ack. RollbackOp.processAck accepts any REPLY-typed message (it
     * only inspects the message type), so a single small no-op part suffices.
     */
    public static byte[] buildRollbackResponse(int txId) {
        return buildMessage(
                MessageTypes.REPLY,
                txId,
                List.of(new Part(new byte[] {0x00}, (byte) 0)));
    }

    /**
     * Build a Geode EXCEPTION response so a failed operation returns a structured error the client
     * can interpret, instead of an abrupt connection close.
     *
     * <p>Part 0 is a Geode-framed, Java-serialized {@link Throwable}; part 1 is the message string.
     * The serialized throwable is a plain {@link Exception} (a type guaranteed to be on every Geode
     * client's classpath) with its stack trace cleared, so the client can always deserialize it and
     * no server-internal class names or stack frames leak across the wire.
     *
     * <p>The byte shape (EXCEPTION message type 2, two parts) is validated against a live Geode
     * client in ProtoGemCouchExceptionResponseIntegrationTest, which confirms the client raises a
     * ServerOperationException carrying this message.
     */
    public static byte[] buildExceptionResponse(int txId, String message) {
        String safeMessage = (message == null || message.isBlank())
                ? "ProtoGemCouch operation failed"
                : message;

        byte[] serializedException = javaSerializeClientSafeException(safeMessage);

        return buildMessage(
                MessageTypes.EXCEPTION,
                txId,
                List.of(
                        new Part(geodeSerializedJavaObject(serializedException), (byte) 1),
                        new Part(ValueEncoding.encodeGeodeStringValue(safeMessage), (byte) 1)
                )
        );
    }

    // --- OQL query (chunked) response ---------------------------------------------------------
    // Byte templates captured from a real Geode 1.15 server's SELECT * response (see GeodeQueryCapture
    // / docs/OQL.md). The CollectionType part is fixed for SELECT * (ResultsCollectionType<Object>).
    private static final byte[] QUERY_COLLECTION_TYPE = ByteUtils.hex(
            "01c52b5700426f72672e6170616368652e67656f64652e63616368652e71756572792e696e7465726e616c2e"
                    + "43756d756c61746976654e6f6e44697374696e6374526573756c747301c32b5700106a6176612e6c616e672e4f626a656374");
    // ObjectPartList header that precedes the element count + elements (non-empty result).
    private static final byte[] QUERY_RESULT_LIST_HEADER = ByteUtils.hex("011900000000");
    // The empty-result form the server sends when there are no rows.
    private static final byte[] QUERY_EMPTY_RESULT = ByteUtils.hex("34002b5700106a6176612e6c616e672e4f626a656374");

    /*
     * Result paging: how many rows go in each chunk of a SELECT * / single-field query response. A
     * large result set is streamed as multiple chunks (each repeating part[0]=CollectionType and
     * carrying part[1]=that batch's result list), with the lastChunk flag set only on the final chunk
     * — exactly as the real Geode server batches query results. Smaller results emit a single chunk
     * (byte-identical to before). Overridable via PGC_QUERY_PAGE_SIZE for testing.
     */
    private static final int QUERY_PAGE_SIZE = queryPageSize();

    private static int queryPageSize() {
        String raw = System.getenv("PGC_QUERY_PAGE_SIZE");
        if (raw != null && !raw.isBlank()) {
            try {
                int v = Integer.parseInt(raw.trim());
                if (v > 0) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return 100;
    }

    /**
     * Build the chunked response for {@code SELECT * FROM /region}. Two parts in one (last) chunk:
     * part[0] is the fixed {@code CollectionType}; part[1] is the result list — an ObjectPartList
     * header, the element count, then each value as {@code 0x00} + its serialized object (the empty
     * case uses the server's empty-result form). Matches the captured real-server bytes.
     */
    public static byte[] buildQueryResponse(int txId, List<StoredValue> values) {
        List<List<Part>> chunks = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            chunks.add(List.of(
                    new Part(QUERY_COLLECTION_TYPE, (byte) 1),
                    new Part(QUERY_EMPTY_RESULT, (byte) 1)));
        } else {
            // Split into row batches; each chunk repeats the CollectionType and carries its batch's
            // result list. The client (QueryOp) reads [part0=CollectionType, part1=results] per chunk
            // and accumulates the results across chunks until the lastChunk flag.
            for (int start = 0; start < values.size(); start += QUERY_PAGE_SIZE) {
                List<StoredValue> batch = values.subList(start, Math.min(start + QUERY_PAGE_SIZE, values.size()));
                chunks.add(List.of(
                        new Part(QUERY_COLLECTION_TYPE, (byte) 1),
                        new Part(buildQueryResultList(batch), (byte) 1)));
            }
        }
        return buildMultiChunkResponse(txId, 2, chunks);
    }

    /** Build one chunk's ObjectPartList result list: the fixed header, the batch count, then each value. */
    private static byte[] buildQueryResultList(List<StoredValue> values) {
        ByteBuf body = Unpooled.buffer();
        try {
            body.writeBytes(QUERY_RESULT_LIST_HEADER);
            writeGeodeArrayLength(body, values.size());
            for (StoredValue value : values) {
                body.writeByte(0x00);
                body.writeBytes(encodeStoredValueForGetAll(value));
            }
            return toByteArrayAndRelease(body);
        } catch (RuntimeException e) {
            body.release();
            throw e;
        }
    }

    // Struct (multi-field) projection templates, also captured from the real Geode server.
    // The CollectionType is a StructType: ResultsCollectionType wrapping Struct with N field$i names
    // and N ObjectType field types. Results are a nested Object[] (0x34): an outer array of structs,
    // each struct an Object[] of its field values.
    private static final byte[] QUERY_STRUCT_COLLECTION_PREFIX = ByteUtils.hex(
            "01c52b5700426f72672e6170616368652e67656f64652e63616368652e71756572792e696e7465726e616c2e"
                    + "43756d756c61746976654e6f6e44697374696e6374526573756c747301c42b5700236f72672e6170616368"
                    + "652e67656f64652e63616368652e71756572792e537472756374");
    // Same StructType as above but wrapped in the "Ordered" CollectionType instead of
    // CumulativeNonDistinctResults, so the client preserves the row order for an ORDER BY + struct
    // projection. Only the leading wrapper class differs (Ordered vs CumulativeNonDistinctResults);
    // the Struct part and the field$i / ObjectType tail are identical. Captured from the real server.
    private static final byte[] QUERY_STRUCT_ORDERED_COLLECTION_PREFIX = ByteUtils.hex(
            "01c52b57002d6f72672e6170616368652e67656f64652e63616368652e71756572792e696e7465726e616c2e4f726465726564"
                    + "01c42b5700236f72672e6170616368652e67656f64652e63616368652e71756572792e537472756374");
    private static final byte[] QUERY_OBJECT_TYPE_CLASS = ByteUtils.hex(
            "2b57002d6f72672e6170616368652e67656f64652e63616368652e71756572792e74797065732e4f626a65637454797065");
    private static final byte[] QUERY_OBJECT_TYPE_ELEMENT = ByteUtils.hex("01c32b5700106a6176612e6c616e672e4f626a656374");
    private static final byte[] QUERY_OBJECT_ARRAY_COMPONENT = ByteUtils.hex("2b5700106a6176612e6c616e672e4f626a656374");
    private static final byte OBJECT_ARRAY_CODE = 0x34;

    // CollectionType for ORDER BY results: ResultsCollectionType wrapping the "Ordered" class (which
    // tells the client to preserve row order), element type java.lang.Object. Captured from the server.
    private static final byte[] QUERY_ORDERED_COLLECTION_TYPE = ByteUtils.hex(
            "01c52b57002d6f72672e6170616368652e67656f64652e63616368652e71756572792e696e7465726e616c2e4f7264657265"
                    + "6401c32b5700106a6176612e6c616e672e4f626a656374");

    /**
     * Build the chunked response for an ORDER BY (single-field / SELECT *) query. Uses the "Ordered"
     * CollectionType and an Object[] ({@code 0x34}) result so the client preserves the row order we
     * sorted into. Matches the captured real-server bytes.
     */
    public static byte[] buildOrderedQueryResponse(int txId, List<StoredValue> values) {
        List<List<Part>> chunks = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            chunks.add(List.of(
                    new Part(QUERY_ORDERED_COLLECTION_TYPE, (byte) 1),
                    new Part(orderedObjectArray(List.of()), (byte) 1)));
        } else {
            // Page like SELECT *: each chunk repeats the Ordered CollectionType + its batch's Object[].
            for (int start = 0; start < values.size(); start += QUERY_PAGE_SIZE) {
                List<StoredValue> batch = values.subList(start, Math.min(start + QUERY_PAGE_SIZE, values.size()));
                chunks.add(List.of(
                        new Part(QUERY_ORDERED_COLLECTION_TYPE, (byte) 1),
                        new Part(orderedObjectArray(batch), (byte) 1)));
            }
        }
        return buildMultiChunkResponse(txId, 2, chunks);
    }

    /** One chunk's ordered result: an Object[] (0x34) of the batch's values. */
    private static byte[] orderedObjectArray(List<StoredValue> values) {
        ByteBuf resultPart = Unpooled.buffer();
        try {
            resultPart.writeByte(OBJECT_ARRAY_CODE);
            writeGeodeArrayLength(resultPart, values.size());
            resultPart.writeBytes(QUERY_OBJECT_ARRAY_COMPONENT);
            for (StoredValue value : values) {
                resultPart.writeBytes(encodeStoredValueForGetAll(value));
            }
            return toByteArrayAndRelease(resultPart);
        } catch (RuntimeException e) {
            resultPart.release();
            throw e;
        }
    }

    /**
     * Build the chunked response for a multi-field (struct) projection: part[0] is a StructType for
     * {@code fieldCount} {@code field$i} columns; part[1] is an outer Object[] of structs, each an
     * Object[] of the row's field values. Matches the captured real-server bytes.
     */
    public static byte[] buildQueryStructResponse(int txId, int fieldCount, List<List<StoredValue>> rows) {
        return buildQueryStructResponse(txId, fieldCount, rows, false);
    }

    /**
     * As {@link #buildQueryStructResponse(int, int, List)}, but when {@code ordered} the StructType is
     * wrapped in the "Ordered" CollectionType so the client preserves the row order of an
     * {@code ORDER BY} struct projection (the rows are already sorted by the handler).
     */
    public static byte[] buildQueryStructResponse(int txId, int fieldCount, List<List<StoredValue>> rows,
                                                  boolean ordered) {
        byte[] structType = buildStructType(fieldCount, ordered);
        List<List<StoredValue>> safeRows = rows == null ? List.of() : rows;

        List<List<Part>> chunks = new ArrayList<>();
        if (safeRows.isEmpty()) {
            chunks.add(List.of(
                    new Part(structType, (byte) 1),
                    new Part(structObjectArray(List.of()), (byte) 1)));
        } else {
            // Page: each chunk repeats the StructType + an outer Object[] of that batch's structs.
            for (int start = 0; start < safeRows.size(); start += QUERY_PAGE_SIZE) {
                List<List<StoredValue>> batch = safeRows.subList(start, Math.min(start + QUERY_PAGE_SIZE, safeRows.size()));
                chunks.add(List.of(
                        new Part(structType, (byte) 1),
                        new Part(structObjectArray(batch), (byte) 1)));
            }
        }
        return buildMultiChunkResponse(txId, 2, chunks);
    }

    /** The StructType CollectionType part: field$i names + ObjectType field types, ordered or not. */
    private static byte[] buildStructType(int fieldCount, boolean ordered) {
        ByteBuf typePart = Unpooled.buffer();
        try {
            typePart.writeBytes(ordered ? QUERY_STRUCT_ORDERED_COLLECTION_PREFIX : QUERY_STRUCT_COLLECTION_PREFIX);
            writeGeodeArrayLength(typePart, fieldCount);
            for (int i = 0; i < fieldCount; i++) {
                typePart.writeBytes(ValueEncoding.encodeGeodeStringValue("field$" + i));
            }
            writeGeodeArrayLength(typePart, fieldCount);
            typePart.writeBytes(QUERY_OBJECT_TYPE_CLASS);
            for (int i = 0; i < fieldCount; i++) {
                typePart.writeBytes(QUERY_OBJECT_TYPE_ELEMENT);
            }
            return toByteArrayAndRelease(typePart);
        } catch (RuntimeException e) {
            typePart.release();
            throw e;
        }
    }

    /** One chunk's struct result: an outer Object[] (0x34) whose elements are per-row Object[] of fields. */
    private static byte[] structObjectArray(List<List<StoredValue>> rows) {
        ByteBuf resultPart = Unpooled.buffer();
        try {
            resultPart.writeByte(OBJECT_ARRAY_CODE);          // outer Object[] of structs
            writeGeodeArrayLength(resultPart, rows.size());
            resultPart.writeBytes(QUERY_OBJECT_ARRAY_COMPONENT);
            for (List<StoredValue> row : rows) {
                resultPart.writeByte(OBJECT_ARRAY_CODE);      // each struct is an Object[] of fields
                writeGeodeArrayLength(resultPart, row.size());
                resultPart.writeBytes(QUERY_OBJECT_ARRAY_COMPONENT);
                for (StoredValue field : row) {
                    resultPart.writeBytes(encodeStoredValueForGetAll(field));
                }
            }
            return toByteArrayAndRelease(resultPart);
        } catch (RuntimeException e) {
            resultPart.release();
            throw e;
        }
    }

    /**
     * Build a chunked query error response: a single part whose object deserializes to a Throwable,
     * which the client raises as a {@code ServerOperationException} (used for unsupported queries).
     */
    public static byte[] buildQueryErrorResponse(int txId, String message) {
        String safeMessage = (message == null || message.isBlank()) ? "query failed" : message;
        byte[] serializedException = javaSerializeClientSafeException(safeMessage);
        return buildChunkedResponse(txId, List.of(
                new Part(geodeSerializedJavaObject(serializedException), (byte) 1)));
    }

    /**
     * Chunked-message framing: a 12-byte header (messageType=RESPONSE, numberOfParts, transactionId)
     * followed by one last chunk (chunkLength int, lastChunk flag byte, then the parts).
     */
    /**
     * Chunked-message framing for a multi-chunk (paged) response: the 12-byte header
     * (messageType=RESPONSE, numberOfParts, transactionId) then each chunk as
     * {@code chunkLength(int), lastChunk-flag(byte), parts}. The {@code lastChunk} flag is set only on
     * the final chunk. Every chunk must carry {@code numberOfParts} parts (the client re-reads that
     * many parts per chunk). A single-chunk list is byte-identical to {@link #buildChunkedResponse}.
     */
    private static byte[] buildMultiChunkResponse(int txId, int numberOfParts, List<List<Part>> chunks) {
        ByteBuf message = Unpooled.buffer();
        try {
            message.writeInt(MessageTypes.RESPONSE);
            message.writeInt(numberOfParts);
            message.writeInt(txId);
            for (int c = 0; c < chunks.size(); c++) {
                ByteBuf chunk = Unpooled.buffer();
                byte[] chunkPayload;
                try {
                    for (Part part : chunks.get(c)) {
                        writePart(chunk, part.payload(), part.typeCode());
                    }
                    chunkPayload = toByteArrayAndRelease(chunk);
                } catch (RuntimeException e) {
                    chunk.release();
                    throw e;
                }
                message.writeInt(chunkPayload.length);
                message.writeByte((byte) (c == chunks.size() - 1 ? 0x01 : 0x00));
                message.writeBytes(chunkPayload);
            }
            return toByteArrayAndRelease(message);
        } catch (RuntimeException e) {
            message.release();
            throw e;
        }
    }

    private static byte[] buildChunkedResponse(int txId, List<Part> parts) {
        ByteBuf chunk = Unpooled.buffer();
        byte[] chunkPayload;
        try {
            for (Part part : parts) {
                writePart(chunk, part.payload(), part.typeCode());
            }
            chunkPayload = toByteArrayAndRelease(chunk);
        } catch (RuntimeException e) {
            chunk.release();
            throw e;
        }

        ByteBuf message = Unpooled.buffer();
        try {
            message.writeInt(MessageTypes.RESPONSE);  // chunked query response message type
            message.writeInt(parts.size());           // number of parts in the chunk
            message.writeInt(txId);
            message.writeInt(chunkPayload.length);     // chunk length
            message.writeByte((byte) 0x01);            // last (and only) chunk
            message.writeBytes(chunkPayload);
            return toByteArrayAndRelease(message);
        } catch (RuntimeException e) {
            message.release();
            throw e;
        }
    }

    private static byte[] javaSerializeClientSafeException(String message) {
        Exception exception = new Exception(message);
        // Drop the stack trace: keeps the payload small and avoids leaking server internals.
        exception.setStackTrace(new StackTraceElement[0]);

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(exception);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize exception response", e);
        }
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

    /**
     * Chunked PUT_ALL error response: a single part whose object deserializes to a Throwable, which
     * the client's PutAllOp raises as a {@code ServerOperationException} (used for partial failures).
     */
    public static byte[] buildPutAllErrorResponse(int txId, String message) {
        String safeMessage = (message == null || message.isBlank()) ? "putAll failed" : message;
        byte[] serializedException = javaSerializeClientSafeException(safeMessage);
        return buildSingleChunkedMessage(
                MessageTypes.RESPONSE,
                txId,
                new Part(geodeSerializedJavaObject(serializedException), (byte) 1)
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
