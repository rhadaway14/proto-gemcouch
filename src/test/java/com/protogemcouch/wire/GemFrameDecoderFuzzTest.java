package com.protogemcouch.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Property / fuzz testing for {@link GemFrameDecoder}: the decoder parses untrusted bytes straight off
 * the network, so for <em>any</em> input — random garbage, hostile headers, truncations, fragmentation,
 * pipelined frames — it must uphold a single invariant: it never throws, hangs, or over-allocates; it
 * either decodes a self-consistent {@link GemFrame}, waits for more bytes, or cleanly rejects the frame
 * and closes the connection (recorded via the {@link MalformedFrameListener}). Seeds are fixed so any
 * failure is reproducible, and the failing input is printed.
 */
class GemFrameDecoderFuzzTest {

    private static final long SEED = 0xC0FFEEL;

    private static final class RecordingListener implements MalformedFrameListener {
        int calls;

        @Override
        public void onMalformedFrame(String reason, SocketAddress remote, long offendingValue) {
            calls++;
        }
    }

    /**
     * The core invariant: feeding {@code input} must not throw / hang / over-allocate, and every frame
     * the decoder emits must be internally consistent (declared part count and lengths match, and the
     * parts fit within the declared payload — never over-reading past the frame).
     */
    private static void assertDecoderSafe(byte[] input, FrameLimits limits, String context) {
        RecordingListener listener = new RecordingListener();
        EmbeddedChannel channel = new EmbeddedChannel(new GemFrameDecoder(limits, listener));
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(input));
            channel.checkException(); // rethrows any exception the decoder raised in the pipeline
            Object message;
            while ((message = channel.readInbound()) != null) {
                assertTrue(message instanceof GemFrame, context + ": produced a non-frame");
                GemFrame frame = (GemFrame) message;
                assertEquals(frame.getNumberOfParts(), frame.getParts().size(),
                        context + ": part count mismatch");
                int consumed = 0;
                for (GemPart part : frame.getParts()) {
                    assertNotNull(part.getPayload(), context + ": null part payload");
                    assertEquals(part.getLength(), part.getPayload().length,
                            context + ": part length mismatch");
                    assertTrue(part.getLength() >= 0, context + ": negative part length");
                    consumed += PART_HEADER_SIZE + part.getLength();
                }
                assertTrue(consumed <= frame.getPayloadLength(),
                        context + ": parts (" + consumed + " bytes) exceed declared payload "
                                + frame.getPayloadLength());
            }
        } catch (Throwable t) {
            fail(context + ": decoder failed on input [" + ByteBufUtil.hexDump(input) + "]: " + t);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static final int PART_HEADER_SIZE = 5;
    private static final int HEADER_SIZE = 17;

    private static byte[] frame(int messageType, int payloadLength, int numberOfParts, int txId,
                                byte flags, byte[] body) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(messageType);
        buf.writeInt(payloadLength);
        buf.writeInt(numberOfParts);
        buf.writeInt(txId);
        buf.writeByte(flags);
        if (body != null) {
            buf.writeBytes(body);
        }
        return release(buf);
    }

    private static byte[] validFrame(int messageType, int txId, List<byte[]> parts) {
        int payloadLength = 0;
        for (byte[] part : parts) {
            payloadLength += PART_HEADER_SIZE + part.length;
        }
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(messageType);
        buf.writeInt(payloadLength);
        buf.writeInt(parts.size());
        buf.writeInt(txId);
        buf.writeByte(0);
        for (byte[] part : parts) {
            buf.writeInt(part.length);
            buf.writeByte(0);
            buf.writeBytes(part);
        }
        return release(buf);
    }

    private static byte[] release(ByteBuf buf) {
        byte[] out = ByteBufUtil.getBytes(buf);
        buf.release();
        return out;
    }

    // ---------------------------------------------------------------- fuzz

    @Test
    void arbitraryRandomBytesNeverCrash() {
        Random random = new Random(SEED);
        FrameLimits defaults = FrameLimits.defaults();
        FrameLimits tight = new FrameLimits(4096, 64);
        for (int i = 0; i < 25_000; i++) {
            byte[] input = new byte[random.nextInt(2048)];
            random.nextBytes(input);
            assertDecoderSafe(input, defaults, "random#" + i + "/defaults");
            assertDecoderSafe(input, tight, "random#" + i + "/tight");
        }
    }

    @Test
    void hostileHeaderFieldsNeverCrash() {
        Random random = new Random(SEED + 1);
        FrameLimits limits = FrameLimits.defaults();
        int[] nasty = {Integer.MIN_VALUE, -1, 0, 1, limits.maxParts(), limits.maxParts() + 1,
                limits.maxFrameBytes(), limits.maxFrameBytes() + 1, Integer.MAX_VALUE};
        for (int iter = 0; iter < 4000; iter++) {
            int payloadLength = nasty[random.nextInt(nasty.length)];
            int numberOfParts = nasty[random.nextInt(nasty.length)];
            byte[] body = new byte[random.nextInt(64)];
            random.nextBytes(body);
            byte[] input = frame(random.nextInt(), payloadLength, numberOfParts,
                    random.nextInt(), (byte) random.nextInt(), body);
            assertDecoderSafe(input, limits, "hostile#" + iter
                    + " payloadLength=" + payloadLength + " numberOfParts=" + numberOfParts);
        }
    }

    @Test
    void everyTruncationPrefixIsSafe() {
        byte[] full = validFrame(MessageTypes.PUT, 7,
                List.of("alpha".getBytes(), new byte[0], "gamma-payload".getBytes()));
        for (int prefix = 0; prefix <= full.length; prefix++) {
            byte[] slice = new byte[prefix];
            System.arraycopy(full, 0, slice, 0, prefix);
            assertDecoderSafe(slice, FrameLimits.defaults(), "truncation@" + prefix);
        }
    }

    // ---------------------------------------------------------------- framing correctness

    @Test
    void fragmentedDeliveryStillDecodesExactlyOnce() {
        byte[] full = validFrame(MessageTypes.PUT, 99,
                List.of("one".getBytes(), "two".getBytes(), "three".getBytes()));
        RecordingListener listener = new RecordingListener();
        EmbeddedChannel channel = new EmbeddedChannel(new GemFrameDecoder(FrameLimits.defaults(), listener));

        // Feed one byte at a time — the decoder must buffer until the full frame has arrived.
        for (int i = 0; i < full.length; i++) {
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {full[i]}));
        }
        channel.checkException();

        GemFrame frame = channel.readInbound();
        assertNotNull(frame, "fragmented frame should decode once fully arrived");
        assertEquals(3, frame.getParts().size());
        assertEquals("three", new String(frame.getParts().get(2).getPayload()));
        assertNull(channel.readInbound());
        assertEquals(0, listener.calls, "fragmentation is not a malformed frame");
        assertTrue(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    void pipelinedValidFramesEachDecodeWithoutDesync() {
        byte[] a = validFrame(MessageTypes.PUT, 1, List.of("a".getBytes()));
        byte[] b = validFrame(MessageTypes.GET, 2, List.of("bb".getBytes(), "ccc".getBytes()));
        byte[] c = validFrame(MessageTypes.REMOVE, 3, List.of(new byte[0]));
        ByteBuf combined = Unpooled.buffer().writeBytes(a).writeBytes(b).writeBytes(c);

        RecordingListener listener = new RecordingListener();
        EmbeddedChannel channel = new EmbeddedChannel(new GemFrameDecoder(FrameLimits.defaults(), listener));
        channel.writeInbound(combined);
        channel.checkException();

        GemFrame f1 = channel.readInbound();
        GemFrame f2 = channel.readInbound();
        GemFrame f3 = channel.readInbound();
        assertEquals(1, f1.getTransactionId());
        assertEquals(2, f2.getTransactionId());
        assertEquals(2, f2.getParts().size());
        assertEquals(3, f3.getTransactionId());
        assertNull(channel.readInbound());
        assertEquals(0, listener.calls);
        channel.finishAndReleaseAll();
    }

    @Test
    void partLengthCannotOverreadIntoTheNextFrame() {
        // Frame A declares payloadLength=5 (room for one empty part header) but its single part claims
        // 64 bytes — which would, without payload-window bounding, read into frame B's bytes. It must be
        // rejected instead, keeping the stream from desyncing.
        ByteBuf hostile = Unpooled.buffer();
        hostile.writeInt(MessageTypes.PUT);
        hostile.writeInt(5);            // payloadLength: exactly one part header
        hostile.writeInt(1);            // numberOfParts
        hostile.writeInt(42);           // txId
        hostile.writeByte(0);           // flags
        hostile.writeInt(64);           // part claims 64 bytes (past the 5-byte payload window)
        hostile.writeByte(0);           // part type
        hostile.writeBytes(validFrame(MessageTypes.GET, 7, List.of("victim".getBytes()))); // frame B

        RecordingListener listener = new RecordingListener();
        EmbeddedChannel channel = new EmbeddedChannel(new GemFrameDecoder(FrameLimits.defaults(), listener));
        channel.writeInbound(hostile);
        channel.checkException();

        assertNull(channel.readInbound(), "the over-reading frame must not decode");
        assertEquals(1, listener.calls, "the over-reading part is rejected as malformed");
        assertFalse(channel.isOpen(), "a malformed frame closes the connection");
        channel.finishAndReleaseAll();
    }

    @Test
    void zeroPartAndZeroLengthPartFramesDecode() {
        assertDecoderSafe(validFrame(MessageTypes.PING, 0, new ArrayList<>()), FrameLimits.defaults(),
                "zero-part");
        assertDecoderSafe(validFrame(MessageTypes.PUT, 0, List.of(new byte[0])), FrameLimits.defaults(),
                "zero-length-part");
    }
}
