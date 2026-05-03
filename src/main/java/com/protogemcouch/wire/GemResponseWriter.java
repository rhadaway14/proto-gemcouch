package com.protogemcouch.wire;

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
     * Geode DataSerializer integer marker observed from IntegerShapeTest:
     *
     *   Integer.valueOf(7) -> 39 00 00 00 07
     */
    private static final byte GEODE_INTEGER_CODE = 0x39;

    /*
     * Geode DataSerializer List marker observed from ListShapeTest:
     *
     *   List.of("key-1", "key-2", "key-3")
     *
     *   41 03
     *   57 00 05 6b65792d31
     *   57 00 05 6b65792d32
     *   57 00 05 6b65792d33
     *
     * Note:
     * keySetOnServer() expects this payload to deserialize to a java.util.List.
     * A Geode Set payload starts with 0x49, but that caused a client-side
     * ClassCastException because the client attempted to cast LinkedHashSet to List.
     */
    private static final byte GEODE_LIST_CODE = 0x41;

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
        /*
         * The real Geode client sizeOnServer path expects an object response.
         * Send a Geode-serialized Integer as an object part rather than raw int
         * bytes as a non-object part.
         */
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(geodeSerializedInteger(size), (byte) 1))
        );
    }

    /**
     * GET_ALL is returned as a chunked response whose single object part
     * contains a Geode VersionedObjectList-compatible payload.
     *
     * We intentionally do not instantiate VersionedObjectList or BlobHelper here.
     * In the shaded container runtime, those Geode internals trigger logging/static
     * initialization failures. Instead, this manually writes the validated small
     * string-key/string-value VersionedObjectList shape.
     *
     * Validated example:
     *
     *   keys:   key-1, key-2, missing
     *   values: value-1, value-2, absent
     *
     *   01070303
     *   5700056b65792d31
     *   5700056b65792d32
     *   5700076d697373696e67
     *   03
     *   01 57000776616c75652d31
     *   01 57000776616c75652d32
     *   03 29
     */
    public static byte[] buildGetAllChunkedResponse(int txId, List<String> keys, Map<String, String> values) {
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
        /*
         * The Geode client keySetOnServer path expects a List, not a Set.
         *
         * Geode DataSerializer List<String> shape observed from ListShapeTest:
         *
         *   41
         *   small-count
         *   geode-string-key-1
         *   geode-string-key-2
         *   ...
         *
         * Example for [key-1, key-2, key-3]:
         *
         *   41 03
         *   57 00 05 6b65792d31
         *   57 00 05 6b65792d32
         *   57 00 05 6b65792d33
         */
        byte[] payload = buildManualStringListPayload(keys);

        return buildSingleChunkedMessage(
                MessageTypes.RESPONSE,
                txId,
                new Part(payload, (byte) 1)
        );
    }

    private static byte[] buildManualVersionedObjectListPayload(List<String> keys, Map<String, String> values) {
        ByteBuf buf = Unpooled.buffer();

        try {
            int size = keys == null ? 0 : keys.size();

            /*
             * Observed VersionedObjectList serialized header for size=3:
             *
             *   01 07 03 03
             *
             * The third byte is the small list size.
             * The fourth byte is the observed object-part-list mode/flags byte.
             */
            buf.writeByte(0x01);
            buf.writeByte(0x07);
            writeSmallCount(buf, size);
            buf.writeByte(0x03);

            if (keys != null) {
                for (String key : keys) {
                    buf.writeBytes(ValueEncoding.encodeGeodeStringValue(key));
                }
            }

            /*
             * Observed second count before object values:
             *
             *   03
             */
            writeSmallCount(buf, size);

            if (keys != null) {
                for (String key : keys) {
                    String value = values == null ? null : values.get(key);

                    if (value == null) {
                        /*
                         * Observed absent-key marker:
                         *
                         *   03 29
                         */
                        buf.writeByte(0x03);
                        buf.writeByte(0x29);
                    } else {
                        /*
                         * Observed present-value marker:
                         *
                         *   01
                         *   followed by Geode String object bytes
                         */
                        buf.writeByte(0x01);
                        buf.writeBytes(ValueEncoding.encodeGeodeStringValue(value));
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
                0x35,
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

        /*
         * ChunkedMessage main header:
         *   int messageType
         *   int numberOfParts
         *   int transactionId
         */
        buf.writeInt(messageType);
        buf.writeInt(numberOfParts);
        buf.writeInt(txId);

        /*
         * Single final chunk:
         *   int chunkLength
         *   byte lastChunkFlag
         *   part
         */
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