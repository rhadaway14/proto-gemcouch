package com.protogemcouch.wire;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Decodes the Geode client wire protocol into {@link GemFrame} instances.
 *
 * <p>All length and count fields in the protocol are attacker-controlled 32-bit integers. Every one
 * is validated against {@link FrameLimits} before it is used to allocate memory or read bytes, so a
 * corrupt or hostile frame is rejected and its connection closed rather than crashing the shim with
 * an {@link OutOfMemoryError} or {@link NegativeArraySizeException}.
 */
public class GemFrameDecoder extends ByteToMessageDecoder {

    private static final int HEADER_SIZE = 17;
    private static final int PART_HEADER_SIZE = 5; // int length + byte typeCode

    private final FrameLimits limits;
    private final MalformedFrameListener malformedFrameListener;

    public GemFrameDecoder() {
        this(FrameLimits.defaults(), MalformedFrameListener.NO_OP);
    }

    public GemFrameDecoder(FrameLimits limits, MalformedFrameListener malformedFrameListener) {
        this.limits = limits == null ? FrameLimits.defaults() : limits;
        this.malformedFrameListener = malformedFrameListener == null
                ? MalformedFrameListener.NO_OP
                : malformedFrameListener;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < HEADER_SIZE) {
            return;
        }

        in.markReaderIndex();

        int messageType = in.readInt();
        int payloadLength = in.readInt();
        int numberOfParts = in.readInt();
        int transactionId = in.readInt();
        byte flags = in.readByte();

        // Validate the declared sizes before trusting them for allocation or reads.
        if (payloadLength < 0 || payloadLength > limits.maxFrameBytes()) {
            rejectMalformed(ctx, in, "payload_length_out_of_bounds", payloadLength);
            return;
        }

        if (numberOfParts < 0 || numberOfParts > limits.maxParts()) {
            rejectMalformed(ctx, in, "number_of_parts_out_of_bounds", numberOfParts);
            return;
        }

        // Wait until the full declared payload has arrived before parsing parts.
        if (in.readableBytes() < payloadLength) {
            in.resetReaderIndex();
            return;
        }

        GemFrame frame = new GemFrame();
        frame.setMessageType(messageType);
        frame.setPayloadLength(payloadLength);
        frame.setNumberOfParts(numberOfParts);
        frame.setTransactionId(transactionId);
        frame.setFlags(flags);

        for (int i = 0; i < numberOfParts; i++) {
            if (in.readableBytes() < PART_HEADER_SIZE) {
                rejectMalformed(ctx, in, "truncated_part_header", i);
                return;
            }

            int partLength = in.readInt();
            byte typeCode = in.readByte();

            if (partLength < 0
                    || partLength > limits.maxFrameBytes()
                    || partLength > in.readableBytes()) {
                rejectMalformed(ctx, in, "part_length_out_of_bounds", partLength);
                return;
            }

            byte[] payload = new byte[partLength];
            in.readBytes(payload);
            frame.addPart(new GemPart(partLength, typeCode, payload));
        }

        out.add(frame);
    }

    /**
     * Reject a malformed frame: notify the listener, discard all buffered bytes (the stream can no
     * longer be trusted to be frame-aligned), and close the connection.
     */
    private void rejectMalformed(ChannelHandlerContext ctx, ByteBuf in, String reason, long offendingValue) {
        malformedFrameListener.onMalformedFrame(reason, ctx.channel().remoteAddress(), offendingValue);
        if (in.isReadable()) {
            in.skipBytes(in.readableBytes());
        }
        ctx.close();
    }
}
