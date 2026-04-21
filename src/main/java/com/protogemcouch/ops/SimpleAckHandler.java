package com.protogemcouch.ops;

import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class SimpleAckHandler implements OperationHandler {

    private final String label;

    public SimpleAckHandler(String label) {
        this.label = label;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        System.out.println(label + " received");
        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildSimpleAck(frame.getTransactionId())));
    }
}