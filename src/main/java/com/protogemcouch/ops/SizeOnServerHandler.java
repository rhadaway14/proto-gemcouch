package com.protogemcouch.ops;

import com.protogemcouch.couchbase.CouchbaseRepository;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class SizeOnServerHandler implements OperationHandler {

    private final CouchbaseRepository repository;

    public SizeOnServerHandler(CouchbaseRepository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        System.out.println("SIZE REQUEST RECEIVED region=" + region + " txId=" + frame.getTransactionId());

        int size = repository.size(region);

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildSizeResponse(frame.getTransactionId(), size)
        ));
    }
}