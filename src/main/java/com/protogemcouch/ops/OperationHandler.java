package com.protogemcouch.ops;

import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;

public interface OperationHandler {
    void handle(ChannelHandlerContext ctx, GemFrame frame);
}