package com.protogemcouch.wire;

import com.protogemcouch.serialization.ValueEncoding;
import com.protogemcouch.util.ByteUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GemResponseWriter {

    private static final byte[] PUT_REPLY_PART1 = new byte[] {
            0x00
    };

    private static final byte[] PUT_REPLY_PART2 = new byte[] {
            0x00, 0x00, 0x00, 0x04
    };

    /*
     * IMPORTANT:
     * The part type byte is written separately by writePart(...).
     * Do NOT include the part type byte inside the payload.
     *
     * Real PUT capture:
     *   part length = 0x13
     *   part type   = 0x01
     *   payload     = 01 88 00 04 00 00 ff 00 01 00 00 00 01 c0 f1 95 92 dc 33
     */
    private static final byte[] PUT_REPLY_PART3 = new byte[] {
            (byte) 0x01, (byte) 0x88, 0x00, 0x04, 0x00, 0x00, (byte) 0xff, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x01, (byte) 0xc0, (byte) 0xf1, (byte) 0x95,
            (byte) 0x92, (byte) 0xdc, 0x33
    };

    /*
     * DestroyOp.processResponse expects:
     *   part 0: flags int
     *   part 1: VersionTag object if HAS_VERSION_TAG
     *   part 2: OK byte (when not prSingleHop)
     *   part 3: entryNotFound int if HAS_ENTRY_NOT_FOUND_PART
     *
     * From the real Geode DESTROY reply capture:
     *   flags = 3
     *   version tag part type = 1
     *   version tag payload   = 01 88 00 04 00 00 ff 00 02 00 00 00 02 ec c6 ac 94 dc 33
     *   ok byte = 00
     *   entryNotFound = 0
     *
     * IMPORTANT:
     * The part type byte (0x01) is NOT part of the payload array here.
     * It is written separately by writePart(...).
     */
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

    private GemResponseWriter() {
    }

    public static byte[] buildGetResponse(int txId, String value) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(ValueEncoding.encodeStringLikeValue(value), (byte) 0))
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
                List.of(new Part(ByteUtils.intToBytes(contains ? 1 : 0), (byte) 0))
        );
    }

    public static byte[] buildSizeResponse(int txId, int size) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(ByteUtils.intToBytes(size), (byte) 0))
        );
    }

    public static byte[] buildGetAllChunkedResponse(int txId, List<String> keys, Map<String, String> values) {
        List<Part> parts = new ArrayList<>();

        for (String key : keys) {
            String value = values.get(key);
            parts.add(new Part(ValueEncoding.encodeStringLikeValue(value), (byte) 0));
        }

        return buildMessage(MessageTypes.RESPONSE, txId, parts);
    }

    public static byte[] buildPutAllChunkedResponse(int txId) {
        return buildMessage(
                MessageTypes.RESPONSE,
                txId,
                List.of(new Part(new byte[] {0x00}, (byte) 0))
        );
    }

    public static byte[] buildKeySetChunkedResponse(int txId, List<String> keys) {
        List<Part> parts = new ArrayList<>();

        for (String key : keys) {
            parts.add(new Part(ValueEncoding.encodeStringLikeValue(key), (byte) 0));
        }

        return buildMessage(MessageTypes.RESPONSE, txId, parts);
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

    private static int computeMessageLength(List<Part> parts) {
        int total = 0;
        for (Part part : parts) {
            total += 4; // part length
            total += 1; // part type
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