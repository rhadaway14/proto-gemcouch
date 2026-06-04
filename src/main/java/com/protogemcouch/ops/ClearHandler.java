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

/**
 * Handles Geode {@code Region.clear()} (CLEAR_REGION, opcode 36). Request part 0 is the region name.
 * Removes every entry in the region and clears its keyset metadata. The client just acks the reply.
 */
public class ClearHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(ClearHandler.class);

    private final Repository repository;

    public ClearHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        repository.clear(region);

        log.info(StructuredLog.event(
                "handler_clear", "region", region, "txId", frame.getTransactionId()));

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildRemoveResponse(frame.getTransactionId())));
    }
}
