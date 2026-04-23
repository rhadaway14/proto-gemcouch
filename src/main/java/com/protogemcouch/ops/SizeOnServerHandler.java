package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SizeOnServerHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(SizeOnServerHandler.class);

    private final Repository repository;

    public SizeOnServerHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        int size = repository.size(region);

        log.info(StructuredLog.event(
                "handler_size",
                "region", region,
                "size", size,
                "txId", frame.getTransactionId()
        ));

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildSizeResponse(frame.getTransactionId(), size)
        ));
    }
}