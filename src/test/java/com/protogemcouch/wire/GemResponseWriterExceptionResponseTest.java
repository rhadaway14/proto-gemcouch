package com.protogemcouch.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GemResponseWriterExceptionResponseTest {

    @Test
    void buildsWellFormedExceptionFrame() {
        byte[] frame = GemResponseWriter.buildExceptionResponse(4242, "backend unavailable");

        ByteBuf buf = Unpooled.wrappedBuffer(frame);
        int messageType = buf.readInt();
        int messageLength = buf.readInt();
        int numberOfParts = buf.readInt();
        int txId = buf.readInt();
        byte flags = buf.readByte();

        assertEquals(MessageTypes.EXCEPTION, messageType, "should be a Geode EXCEPTION message");
        assertEquals(4242, txId, "transaction id should be echoed");
        assertEquals(2, numberOfParts, "exception object part + message part");
        assertEquals(0, flags);

        // The remaining bytes are the two parts; their total must match the declared message length.
        assertEquals(messageLength, buf.readableBytes(), "declared payload length must match actual");

        // Part 0: serialized throwable object (length-prefixed, type code = object).
        int part0Length = buf.readInt();
        byte part0Type = buf.readByte();
        assertTrue(part0Length > 0, "serialized exception part must be non-empty");
        assertEquals(1, part0Type, "exception part is an object part");
        buf.skipBytes(part0Length);

        // Part 1: message string object.
        int part1Length = buf.readInt();
        byte part1Type = buf.readByte();
        assertTrue(part1Length > 0, "message part must be non-empty");
        assertEquals(1, part1Type, "message part is an object part");
    }

    @Test
    void usesFallbackMessageWhenBlank() {
        // Should not throw and should still produce a valid frame for a null/blank message.
        byte[] frame = GemResponseWriter.buildExceptionResponse(1, null);

        ByteBuf buf = Unpooled.wrappedBuffer(frame);
        assertEquals(MessageTypes.EXCEPTION, buf.readInt());
    }
}
