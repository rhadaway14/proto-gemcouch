package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.util.DocumentKeyUtil;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import com.protogemcouch.wire.MessageTypes;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class ContainsHandler implements OperationHandler {

    private final Repository repository;

    public ContainsHandler(Repository repository) {
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
        int mode = frame.getParts().size() > 2
                ? ByteUtils.bytesToInt(frame.getParts().get(2).getPayload())
                : MessageTypes.CONTAINS_MODE_KEY;

        String docId = DocumentKeyUtil.docId(region, key);
        boolean result;

        if (mode == MessageTypes.CONTAINS_MODE_KEY) {
            result = repository.containsKey(docId);
            System.out.println("CONTAINS KEY REQUEST RECEIVED region=" + region
                    + " key=" + key
                    + " docId=" + docId
                    + " result=" + result);
        } else if (mode == MessageTypes.CONTAINS_MODE_VALUE_FOR_KEY) {
            result = repository.containsValueForKey(docId);
            System.out.println("CONTAINS VALUE FOR KEY REQUEST RECEIVED region=" + region
                    + " key=" + key
                    + " docId=" + docId
                    + " result=" + result);
        } else if (mode == MessageTypes.CONTAINS_MODE_VALUE) {
            result = false;
            System.out.println("CONTAINS VALUE REQUEST RECEIVED region=" + region
                    + " key=" + key
                    + " docId=" + docId
                    + " result=" + result
                    + " (not implemented)");
        } else {
            result = false;
            System.out.println("UNKNOWN CONTAINS MODE region=" + region
                    + " key=" + key
                    + " docId=" + docId
                    + " mode=" + mode
                    + " result=" + result);
        }

        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildContainsResponse(frame.getTransactionId(), result)));
    }
}