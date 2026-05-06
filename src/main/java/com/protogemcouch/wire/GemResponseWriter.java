package com.protogemcouch.wire;

import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.serialization.ValueEncoding;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

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
     * Geode DataSerializer boolean marker:
     *
     *   Boolean.TRUE  -> 35 01
     *   Boolean.FALSE -> 35 00
     */
    private static final byte GEODE_BOOLEAN_CODE = 0x35;

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
     * Geode DataSerializer List marker observed from ListShapeTest:
     *
     *   List.of("key-1", "key-2", "key-3")
     *
     *   41 03
     *   57 00 05 6b65792d31
     *   57 00 05 6b65792d32
     *   57 00 05 6b65792d33
     */
    private static final byte GEODE_LIST_CODE = 0x41;

    /*
     * Full Geode DataSerializer object header for VersionedObjectList.
     *
     * Observed from DataSerializer.writeObject(new VersionedObjectList(...)):
     *
     *   01 07 03
     *
     * Then the VersionedObjectList body follows:
     *
     *   <key-count>
     *   <keys>
     *   <object-count>
     *   <object entries>
     *
     * Do not instantiate VersionedObjectList in production code. In the shaded
     * runtime container, VersionedObjectList static initialization can fail due
     * to Geode LogService / Log4j caller-class lookup.
     */
    private static final byte[] VERSIONED_OBJECT_LIST_OBJECT_HEADER = new byte[] {
            0x01, 0x07, 0x03
    };

    private GemResponseWriter() {
    }

    public static byte[] buildGetResponse(int txId, String value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(ValueEncoding.encodeGeodeStringValue(value), (byte) 1))
        );
    }

    public static byte[] buildIntegerGetResponse(int txId, int value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedInteger(value), (byte) 1))
        );
    }

    public static byte[] buildBooleanGetResponse(int txId, boolean value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedBoolean(value), (byte) 1))
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
             * Full DataSerializer object header for VersionedObjectList.
             *
             * This is the wrapper the Geode client expects when Part.getObject()
             * deserializes the GET_ALL response part.
             */
            buf.writeBytes(VERSIONED_OBJECT_LIST_OBJECT_HEADER);

            /*
             * Keys section.
             */
            writeSmallCount(buf, size);

            if (keys != null) {
                for (String key : keys) {
                    buf.writeBytes(ValueEncoding.encodeGeodeStringValue(key));
                }
            }

            /*
             * Objects section.
             */
            writeSmallCount(buf, size);

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

        if (rawValue instanceof Boolean bool) {
            return StoredValue.booleanValue(bool);
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

        return StoredValue.stringValue(String.valueOf(rawValue));
    }

    private static byte[] encodeStoredValueForGetAll(StoredValue value) {
        if (value.type() == StoredValue.Type.BOOLEAN) {
            return geodeSerializedBoolean(value.asBoolean());
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

        return ValueEncoding.encodeGeodeStringValue(value.value());
    }

    private static byte[] buildManualStringListPayload(List<String> keys) {
        ByteBuf buf = Unpooled.buffer();

        try {
            int size = keys == null ? 0 : keys.size();

            buf.writeByte(GEODE_LIST_CODE);
            writeSmallCount(buf, size);

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

    private static void writeSmallCount(ByteBuf buf, int count) {
        if (count < 0 || count > 0x7f) {
            throw new IllegalArgumentException(
                    "Validated manual writer only supports counts from 0 to 127. Actual: " + count
            );
        }

        buf.writeByte((byte) count);
    }

    private static byte[] geodeSerializedBoolean(boolean value) {
        return new byte[] {
                GEODE_BOOLEAN_CODE,
                (byte) (value ? 0x01 : 0x00)
        };
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