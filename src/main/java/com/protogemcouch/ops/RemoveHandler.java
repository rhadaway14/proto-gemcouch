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

public class RemoveHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(RemoveHandler.class);

    // Geode Operation id for Region.remove(key, value) carried in DESTROY part[3]. Such a request
    // also carries the expected value in part[2]; a plain remove(key) has neither.
    private static final int OP_REMOVE_IF_VALUE = 0x2e;

    private final Repository repository;

    public RemoveHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        int parts = frame.getParts().size();
        String region = parts > 0 ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload()) : "";
        String key = parts > 1 ? ByteUtils.bytesToString(frame.getParts().get(1).getPayload()) : "";
        String docId = DocumentKeyUtil.docId(region, key);
        int txId = frame.getTransactionId();

        // remove(key, value): DESTROY carries the expected value in part[2] and op 0x2e in part[3].
        boolean isCompareRemove = parts > 3
                && firstByte(frame.getParts().get(3).getPayload()) == OP_REMOVE_IF_VALUE
                && frame.getParts().get(2).getPayload() != null
                && frame.getParts().get(2).getPayload().length > 0;

        if (isCompareRemove) {
            StoredValue expected = PutHandler.decodePutValue(frame.getParts().get(2).getPayload(), txId);
            boolean removed = expected != null && repository.removeIfValue(docId, expected);
            // Storage is correct: a mismatch leaves the entry in place (CAS-guarded removeIfValue).
            // The client's boolean return value still needs the DESTROY entry-not-found reply format
            // (documented follow-up), so we send the standard remove reply for now.
            log.info(StructuredLog.event(
                    "handler_remove_if_value",
                    "region", region, "key", key, "docId", docId, "removed", removed, "txId", txId));
            ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildRemoveResponse(txId)));
            return;
        }

        log.info(StructuredLog.event(
                "handler_remove", "region", region, "key", key, "docId", docId, "txId", txId));
        repository.remove(docId);
        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildRemoveResponse(txId)));
    }

    private static int firstByte(byte[] payload) {
        return payload != null && payload.length > 0 ? (payload[0] & 0xff) : -1;
    }
}