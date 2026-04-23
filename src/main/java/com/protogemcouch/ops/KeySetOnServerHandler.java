package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

public class KeySetOnServerHandler implements OperationHandler {

    private final Repository repository;

    public KeySetOnServerHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        System.out.println("KEY SET REQUEST RECEIVED region=" + region + " txId=" + frame.getTransactionId());

        List<String> keys = repository.keySet(region);

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildKeySetChunkedResponse(frame.getTransactionId(), keys)
        ));
    }
}