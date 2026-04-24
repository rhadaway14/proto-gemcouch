package com.protogemcouch.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

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
    void different_response_builders_produce_different_payloads() {
        byte[] getBytes = GemResponseWriter.buildGetResponse(123, "value");
        byte[] putBytes = GemResponseWriter.buildPutResponse(123);
        byte[] removeBytes = GemResponseWriter.buildRemoveResponse(123);

        assertFalse(java.util.Arrays.equals(getBytes, putBytes));
        assertFalse(java.util.Arrays.equals(getBytes, removeBytes));
    }
}