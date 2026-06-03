package com.protogemcouch.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GemFrameDecoderTest {

    private static final byte PART_TYPE = (byte) 0x00;

    private static final class RecordingListener implements MalformedFrameListener {
        private int calls;
        private String lastReason;
        private long lastOffendingValue;

        @Override
        public void onMalformedFrame(String reason, SocketAddress remote, long offendingValue) {
            calls++;
            lastReason = reason;
            lastOffendingValue = offendingValue;
        }
    }

    /** Encode a single-part frame with a well-formed header. */
    private static ByteBuf singlePartFrame(int messageType, int txId, byte[] partPayload) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(messageType);
        buf.writeInt(5 + partPayload.length); // payloadLength = part header (5) + part data
        buf.writeInt(1);                       // numberOfParts
        buf.writeInt(txId);
        buf.writeByte(0);                      // flags
        buf.writeInt(partPayload.length);      // part length
        buf.writeByte(PART_TYPE);              // part type code
        buf.writeBytes(partPayload);
        return buf;
    }

    @Test
    void decodesAWellFormedSinglePartFrame() {
        RecordingListener listener = new RecordingListener();
        EmbeddedChannel channel = new EmbeddedChannel(new GemFrameDecoder(FrameLimits.defaults(), listener));

        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        boolean produced = channel.writeInbound(singlePartFrame(MessageTypes.PUT, 42, payload));

        assertTrue(produced);
        GemFrame frame = channel.readInbound();
        assertNotNull(frame);
        assertEquals(MessageTypes.PUT, frame.getMessageType());
        assertEquals(1, frame.getNumberOfParts());
        assertEquals(42, frame.getTransactionId());
        assertEquals(1, frame.getParts().size());
        assertArrayEquals(payload, frame.getParts().get(0).getPayload());

        assertEquals(0, listener.calls);
        assertTrue(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    void waitsForMoreBytesWhenHeaderIncomplete() {
        RecordingListener listener = new RecordingListener();
        EmbeddedChannel channel = new EmbeddedChannel(new GemFrameDecoder(FrameLimits.defaults(), listener));

        ByteBuf partial = Unpooled.buffer();
        partial.writeInt(MessageTypes.PUT);
        partial.writeInt(10); // only 8 of the 17 header bytes provided

        boolean produced = channel.writeInbound(partial);

        assertFalse(produced);
        assertNull(channel.readInbound());
        assertEquals(0, listener.calls);
        assertTrue(channel.isOpen(), "Incomplete header should wait, not be treated as malformed");
        channel.finishAndReleaseAll();
    }

    @Test
    void waitsForMoreBytesWhenPayloadIncomplete() {
        RecordingListener listener = new RecordingListener();
        EmbeddedChannel channel = new EmbeddedChannel(new GemFrameDecoder(FrameLimits.defaults(), listener));

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(MessageTypes.PUT);
        buf.writeInt(100); // declares 100 payload bytes
        buf.writeInt(1);
        buf.writeInt(7);
        buf.writeByte(0);
        buf.writeBytes(new byte[10]); // but only 10 arrive

        boolean produced = channel.writeInbound(buf);

        assertFalse(produced);
        assertNull(channel.readInbound());
        assertEquals(0, listener.calls);
        assertTrue(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    void rejectsOversizedPayloadLength() {
        RecordingListener listener = new RecordingListener();
        FrameLimits limits = new FrameLimits(1024, 10);
        EmbeddedChannel channel = new EmbeddedChannel(new GemFrameDecoder(limits, listener));

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(MessageTypes.PUT);
        buf.writeInt(Integer.MAX_VALUE); // payloadLength way over the 1024 cap
        buf.writeInt(1);
        buf.writeInt(0);
        buf.writeByte(0);

        boolean produced = channel.writeInbound(buf);

        assertFalse(produced);
        assertNull(channel.readInbound());
        assertEquals(1, listener.calls);
        assertEquals("payload_length_out_of_bounds", listener.lastReason);
        assertEquals(Integer.MAX_VALUE, listener.lastOffendingValue);
        assertFalse(channel.isOpen(), "Malformed frame should close the connection");
        channel.finishAndReleaseAll();
    }

    @Test
    void rejectsOversizedNumberOfParts() {
        RecordingListener listener = new RecordingListener();
        FrameLimits limits = new FrameLimits(1024, 10);
        EmbeddedChannel channel = new EmbeddedChannel(new GemFrameDecoder(limits, listener));

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(MessageTypes.PUT);
        buf.writeInt(0);
        buf.writeInt(11); // over the maxParts cap of 10
        buf.writeInt(0);
        buf.writeByte(0);

        channel.writeInbound(buf);

        assertEquals(1, listener.calls);
        assertEquals("number_of_parts_out_of_bounds", listener.lastReason);
        assertEquals(11, listener.lastOffendingValue);
        assertFalse(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    void rejectsNegativePartLength() {
        RecordingListener listener = new RecordingListener();
        EmbeddedChannel channel = new EmbeddedChannel(new GemFrameDecoder(FrameLimits.defaults(), listener));

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(MessageTypes.PUT);
        buf.writeInt(5);  // payloadLength = one empty part header
        buf.writeInt(1);
        buf.writeInt(0);
        buf.writeByte(0);
        buf.writeInt(-1); // negative part length
        buf.writeByte(PART_TYPE);

        channel.writeInbound(buf);

        assertNull(channel.readInbound());
        assertEquals(1, listener.calls);
        assertEquals("part_length_out_of_bounds", listener.lastReason);
        assertFalse(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    void rejectsPartLengthOverFrameCap() {
        RecordingListener listener = new RecordingListener();
        FrameLimits limits = new FrameLimits(1024, 10);
        EmbeddedChannel channel = new EmbeddedChannel(new GemFrameDecoder(limits, listener));

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(MessageTypes.PUT);
        buf.writeInt(5);        // payloadLength satisfied by the 5-byte part header below
        buf.writeInt(1);
        buf.writeInt(0);
        buf.writeByte(0);
        buf.writeInt(1_000_000); // part claims ~1MB, over the 1024 cap
        buf.writeByte(PART_TYPE);

        channel.writeInbound(buf);

        assertNull(channel.readInbound());
        assertEquals(1, listener.calls);
        assertEquals("part_length_out_of_bounds", listener.lastReason);
        assertEquals(1_000_000, listener.lastOffendingValue);
        assertFalse(channel.isOpen());
        channel.finishAndReleaseAll();
    }
}
