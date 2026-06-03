package com.protogemcouch.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GemResponseWriterTest {

    @Test
    void buildGetResponse_returns_non_empty_bytes() {
        byte[] bytes = GemResponseWriter.buildGetResponse(123, "value");
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        assertEquals(MessageTypes.RESPONSE, buf.readInt());
        buf.release();
    }

    @Test
    void buildNullGetResponse_returns_non_empty_bytes() {
        byte[] bytes = GemResponseWriter.buildNullGetResponse(123);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        assertEquals(MessageTypes.RESPONSE, buf.readInt());
        buf.release();
    }

    @Test
    void buildPutResponse_returns_reply_message() {
        byte[] bytes = GemResponseWriter.buildPutResponse(123);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        assertEquals(MessageTypes.REPLY, buf.readInt());
        buf.release();
    }

    @Test
    void buildRemoveResponse_returns_reply_message() {
        byte[] bytes = GemResponseWriter.buildRemoveResponse(123);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        assertEquals(MessageTypes.REPLY, buf.readInt());
        buf.release();
    }

    @Test
    void buildContainsResponse_true_returns_response_message() {
        byte[] bytes = GemResponseWriter.buildContainsResponse(123, true);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        assertEquals(MessageTypes.RESPONSE, buf.readInt());
        buf.release();
    }

    @Test
    void buildContainsResponse_false_returns_response_message() {
        byte[] bytes = GemResponseWriter.buildContainsResponse(123, false);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        assertEquals(MessageTypes.RESPONSE, buf.readInt());
        buf.release();
    }

    @Test
    void buildContainsResponse_true_and_false_are_different() {
        byte[] trueBytes = GemResponseWriter.buildContainsResponse(123, true);
        byte[] falseBytes = GemResponseWriter.buildContainsResponse(123, false);

        assertNotNull(trueBytes);
        assertNotNull(falseBytes);
        assertTrue(trueBytes.length > 0);
        assertTrue(falseBytes.length > 0);
        assertFalse(java.util.Arrays.equals(trueBytes, falseBytes));
    }

    @Test
    void buildSizeResponse_returns_response_message() {
        byte[] bytes = GemResponseWriter.buildSizeResponse(123, 42);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        assertEquals(MessageTypes.RESPONSE, buf.readInt());
        buf.release();
    }

    @Test
    void buildSimpleAck_returns_reply_message() {
        byte[] bytes = GemResponseWriter.buildSimpleAck(123);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        assertEquals(MessageTypes.REPLY, buf.readInt());
        buf.release();
    }

    @Test
    void buildGetAllChunkedResponse_returns_response_message() {
        byte[] bytes = GemResponseWriter.buildGetAllChunkedResponse(
                123,
                List.of("k1", "k2"),
                Map.of("k1", "v1", "k2", "v2")
        );
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        assertEquals(MessageTypes.RESPONSE, buf.readInt());
        buf.release();
    }

    @Test
    void buildGetAllChunkedResponse_with_null_value_returns_non_empty_bytes() {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("k1", "v1");
        values.put("missing", null);

        byte[] bytes = GemResponseWriter.buildGetAllChunkedResponse(
                123,
                List.of("k1", "missing"),
                values
        );
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
    }

    @Test
    void buildPutAllChunkedResponse_returns_response_message() {
        byte[] bytes = GemResponseWriter.buildPutAllChunkedResponse(123);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        assertEquals(MessageTypes.RESPONSE, buf.readInt());
        buf.release();
    }

    @Test
    void buildKeySetChunkedResponse_returns_response_message() {
        byte[] bytes = GemResponseWriter.buildKeySetChunkedResponse(123, List.of("a", "b"));
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        assertEquals(MessageTypes.RESPONSE, buf.readInt());
        buf.release();
    }

    @Test
    void buildKeySetChunkedResponse_empty_list_returns_non_empty_bytes() {
        byte[] bytes = GemResponseWriter.buildKeySetChunkedResponse(123, List.of());
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
    }



    @Test
    void buildKeySetChunkedResponse_with150Keys_usesInlineGeodeArrayLength() {
        byte[] bytes = GemResponseWriter.buildKeySetChunkedResponse(123, sequentialKeys("ks-150-", 150));

        byte[] payload = singleChunkedPartPayload(bytes);

        assertEquals(0x41, payload[0] & 0xff, "Expected Geode ArrayList/List marker");
        assertEquals(0x96, payload[1] & 0xff, "150 should be encoded inline for Geode array/list length");
        assertEquals(0x57, payload[2] & 0xff, "First list item should begin with Geode string marker");
    }

    @Test
    void buildKeySetChunkedResponse_with253Keys_usesExtendedShortGeodeArrayLength() {
        byte[] bytes = GemResponseWriter.buildKeySetChunkedResponse(123, sequentialKeys("ks-253-", 253));

        byte[] payload = singleChunkedPartPayload(bytes);

        assertEquals(0x41, payload[0] & 0xff, "Expected Geode ArrayList/List marker");
        assertEquals(0xfe, payload[1] & 0xff, "253 should use Geode extended short length marker");
        assertEquals(0x00, payload[2] & 0xff);
        assertEquals(0xfd, payload[3] & 0xff);
        assertEquals(0x57, payload[4] & 0xff, "First list item should begin with Geode string marker");
    }

    @Test
    void buildGetAllChunkedResponse_with150Keys_usesUnsignedVlVersionedObjectListCount() {
        List<String> keys = sequentialKeys("ga-150-", 150);
        byte[] bytes = GemResponseWriter.buildGetAllChunkedResponse(123, keys, stringValues(keys));

        byte[] payload = singleChunkedPartPayload(bytes);

        assertEquals(0x01, payload[0] & 0xff, "Expected VersionedObjectList DSFID wrapper byte 1");
        assertEquals(0x07, payload[1] & 0xff, "Expected VersionedObjectList DSFID wrapper byte 2");
        assertEquals(0x03, payload[2] & 0xff, "Expected hasKeys + hasObjects flags");
        assertEquals(0x96, payload[3] & 0xff, "150 should be unsigned-VL encoded as 0x96 0x01");
        assertEquals(0x01, payload[4] & 0xff, "150 should be unsigned-VL encoded as 0x96 0x01");
        assertEquals(0x57, payload[5] & 0xff, "First key should begin with Geode string marker");
    }

    @Test
    void buildGetAllChunkedResponse_with253Keys_usesUnsignedVlVersionedObjectListCount() {
        List<String> keys = sequentialKeys("ga-253-", 253);
        byte[] bytes = GemResponseWriter.buildGetAllChunkedResponse(123, keys, stringValues(keys));

        byte[] payload = singleChunkedPartPayload(bytes);

        assertEquals(0x01, payload[0] & 0xff, "Expected VersionedObjectList DSFID wrapper byte 1");
        assertEquals(0x07, payload[1] & 0xff, "Expected VersionedObjectList DSFID wrapper byte 2");
        assertEquals(0x03, payload[2] & 0xff, "Expected hasKeys + hasObjects flags");
        assertEquals(0xfd, payload[3] & 0xff, "253 should be unsigned-VL encoded as 0xfd 0x01");
        assertEquals(0x01, payload[4] & 0xff, "253 should be unsigned-VL encoded as 0xfd 0x01");
        assertEquals(0x57, payload[5] & 0xff, "First key should begin with Geode string marker");
    }

    @Test
    void buildGetAllChunkedResponse_doesNotUseGeodeArrayLengthEncodingForVersionedObjectListCount() {
        List<String> keys = sequentialKeys("ga-guard-", 253);
        byte[] bytes = GemResponseWriter.buildGetAllChunkedResponse(123, keys, stringValues(keys));

        byte[] payload = singleChunkedPartPayload(bytes);

        assertEquals(0x01, payload[0] & 0xff);
        assertEquals(0x07, payload[1] & 0xff);
        assertEquals(0x03, payload[2] & 0xff);

        /*
         * Geode array/list length encoding for 253 would be:
         *
         *   fe 00 fd
         *
         * VersionedObjectList must not use that encoding. It uses unsigned-VL:
         *
         *   fd 01
         */
        assertFalse(
                (payload[3] & 0xff) == 0xfe
                        && (payload[4] & 0xff) == 0x00
                        && (payload[5] & 0xff) == 0xfd,
                "VersionedObjectList count must not use Geode array/list length encoding"
        );
    }

    @Test
    void different_response_builders_produce_different_payloads() {
        byte[] getBytes = GemResponseWriter.buildGetResponse(123, "value");
        byte[] putBytes = GemResponseWriter.buildPutResponse(123);
        byte[] removeBytes = GemResponseWriter.buildRemoveResponse(123);

        assertFalse(java.util.Arrays.equals(getBytes, putBytes));
        assertFalse(java.util.Arrays.equals(getBytes, removeBytes));
    }


    private static List<String> sequentialKeys(String prefix, int count) {
        List<String> keys = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            keys.add(prefix + i);
        }

        return keys;
    }

    private static Map<String, Object> stringValues(List<String> keys) {
        Map<String, Object> values = new LinkedHashMap<>();

        for (String key : keys) {
            values.put(key, "value-for-" + key);
        }

        return values;
    }

    private static byte[] singleChunkedPartPayload(byte[] messageBytes) {
        ByteBuf buf = Unpooled.wrappedBuffer(messageBytes);

        try {
            assertEquals(MessageTypes.RESPONSE, buf.readInt(), "Expected chunked response message type");

            int partCount = buf.readInt();
            assertEquals(1, partCount, "Expected a single chunked response part");

            buf.readInt(); // transaction id

            int chunkLength = buf.readInt();
            assertTrue(chunkLength > 0, "Expected positive chunk length");

            int lastChunkFlag = buf.readByte() & 0xff;
            assertEquals(0x01, lastChunkFlag, "Expected final chunk flag");

            int payloadLength = buf.readInt();
            int typeCode = buf.readByte() & 0xff;
            assertEquals(0x01, typeCode, "Expected object part type code");

            byte[] payload = new byte[payloadLength];
            buf.readBytes(payload);

            return payload;
        } finally {
            buf.release();
        }
    }
}
