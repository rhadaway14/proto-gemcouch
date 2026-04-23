package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.util.DocumentKeyUtil;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class RemoveHandler implements OperationHandler {

    private final Repository repository;

    public RemoveHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";
        String key = frame.getParts().size() > 1
                ? ByteUtils.bytesToString(frame.getParts().get(1).getPayload())
                : "";

        String docId = DocumentKeyUtil.docId(region, key);
        System.out.println("REMOVE REQUEST RECEIVED region=" + region + " key=" + key + " docId=" + docId);

        repository.remove(docId);

        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildRemoveResponse(frame.getTransactionId())));
    }
}