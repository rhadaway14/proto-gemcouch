package com.protogemcouch.ops;

import com.protogemcouch.couchbase.CouchbaseRepository;
import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class GetHandler implements OperationHandler {

    private final Repository repository;

    public GetHandler(Repository repository) {
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

        String docId = CouchbaseRepository.docId(region, key);
        System.out.println("GET REQUEST RECEIVED region=" + region + " key=" + key + " docId=" + docId);

        String value = repository.get(docId);

        byte[] response = (value != null)
                ? GemResponseWriter.buildGetResponse(frame.getTransactionId(), value)
                : GemResponseWriter.buildNullGetResponse(frame.getTransactionId());

        ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
    }
}