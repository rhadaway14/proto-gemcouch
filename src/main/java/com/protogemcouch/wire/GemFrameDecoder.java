package com.protogemcouch.wire;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class GemFrameDecoder extends ByteToMessageDecoder {

    private static final int HEADER_SIZE = 17;

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
            int partLength = in.readInt();
            byte typeCode = in.readByte();
            byte[] payload = new byte[partLength];
            in.readBytes(payload);
            frame.addPart(new GemPart(partLength, typeCode, payload));
        }

        out.add(frame);
    }
}