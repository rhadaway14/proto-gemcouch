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
    void buildContainsResponse_returns_response_message() {
        byte[] bytes = GemResponseWriter.buildContainsResponse(123, true);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        assertEquals(MessageTypes.RESPONSE, buf.readInt());
        buf.release();
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
}