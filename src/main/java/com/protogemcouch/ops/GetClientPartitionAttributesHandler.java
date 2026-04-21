package com.protogemcouch.ops;

import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;

public class GetClientPartitionAttributesHandler implements OperationHandler {

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";
        System.out.println("GET_CLIENT_PARTITION_ATTRIBUTES observed for region=" + region + " txId=" + frame.getTransactionId());
        System.out.println("No explicit response implemented yet for type=73");
    }
}