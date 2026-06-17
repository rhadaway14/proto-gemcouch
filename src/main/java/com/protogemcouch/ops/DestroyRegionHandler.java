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
 * Handles Geode {@code Region.destroyRegion()} (DESTROY_REGION, opcode 11). Request part 0 is the
 * region name (the rest is a callback/event argument the shim ignores). The shim is schemaless, so
 * destroying a region just removes all of its entries and its keyset metadata — the same operation as
 * {@code clear()} — and the region re-materializes implicitly on the next write. The client acks the
 * reply, identical in shape to the CLEAR_REGION reply.
 *
 * <p>There is no matching CREATE_REGION handler: a client {@code PROXY}/{@code CACHING_PROXY} region is
 * created locally and never sends a server-side create, and the schemaless shim serves any region name
 * on first use, so creation needs no wire support.
 */
public class DestroyRegionHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(DestroyRegionHandler.class);

    private final Repository repository;

    public DestroyRegionHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        repository.clear(region);

        log.info(StructuredLog.event(
                "handler_destroy_region", "region", region, "txId", frame.getTransactionId()));

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildRemoveResponse(frame.getTransactionId())));
    }
}
