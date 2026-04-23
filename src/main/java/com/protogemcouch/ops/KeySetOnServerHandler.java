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

import java.util.List;

public class KeySetOnServerHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(KeySetOnServerHandler.class);

    private final Repository repository;

    public KeySetOnServerHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        List<String> keys = repository.keySet(region);

        log.info(StructuredLog.event(
                "handler_key_set",
                "region", region,
                "count", keys.size(),
                "txId", frame.getTransactionId()
        ));

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildKeySetChunkedResponse(frame.getTransactionId(), keys)
        ));
    }
}