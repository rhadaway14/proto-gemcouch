package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.util.DocumentKeyUtil;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles Geode {@code Region.getEntry(key)} (GET_ENTRY, opcode 89). Request parts: region(0),
 * key(1). The client reads an object response. This first cut returns the value object (like GET) so
 * we can validate against the real client whether {@code getEntry} accepts that shape or requires a
 * Geode {@code EntrySnapshot}.
 */
public class GetEntryHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(GetEntryHandler.class);

    private final Repository repository;

    public GetEntryHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        int parts = frame.getParts().size();
        String region = parts > 0 ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload()) : "";
        String key = parts > 1 ? ByteUtils.bytesToString(frame.getParts().get(1).getPayload()) : "";
        String docId = DocumentKeyUtil.docId(region, key);
        int txId = frame.getTransactionId();

        StoredValue value = repository.get(docId);

        log.info(StructuredLog.event(
                "handler_get_entry", "region", region, "key", key, "docId", docId,
                "present", value != null, "txId", txId));

        byte[] response = (value == null)
                ? GemResponseWriter.buildNullGetResponse(txId)
                : GemResponseWriter.buildGetResponse(txId, value.value());
        ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
    }
}
