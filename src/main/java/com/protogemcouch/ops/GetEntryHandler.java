package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.tx.TransactionRegistry;
import com.protogemcouch.tx.TxState;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.util.DocumentKeyUtil;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles Geode {@code Region.getEntry(key)} (GET_ENTRY, opcode 89). Request parts: region(0), key(1).
 *
 * <p>A plain (non-transactional) {@code getEntry} on a client region is served locally by the client
 * and never reaches the server — opcode 89 is only sent <em>inside a client transaction</em> (the
 * transaction needs the server's view of the entry). So this handler honors read-your-writes against
 * the transaction buffer (a buffered put is returned, a buffered remove reads as absent) before
 * falling through to committed storage.
 *
 * <p>The reply is a real Geode {@code EntrySnapshot} for a present key (so the client's {@code getEntry}
 * returns an {@code Entry} whose {@code getValue()} is the stored value) and a null object for an
 * absent key (the client returns {@code null}). The byte shape was captured from a real Geode 1.15.1
 * server (see {@code tools/GetEntryCapture}) and is reproduced by {@code GemResponseWriter}.
 */
public class GetEntryHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(GetEntryHandler.class);

    private final Repository repository;
    private final TransactionRegistry transactions;

    public GetEntryHandler(Repository repository, TransactionRegistry transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    /** Convenience for non-transactional callers/tests: uses a private, empty transaction registry. */
    public GetEntryHandler(Repository repository) {
        this(repository, new TransactionRegistry());
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        int parts = frame.getParts().size();
        String region = parts > 0 ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload()) : "";
        String key = parts > 1 ? ByteUtils.bytesToString(frame.getParts().get(1).getPayload()) : "";
        String docId = DocumentKeyUtil.docId(region, key);
        int txId = frame.getTransactionId();

        // Read-your-writes inside a transaction; otherwise committed storage.
        StoredValue value;
        TxState.Op buffered = txId >= 0
                ? transactions.peekOp(ctx.channel().id().asLongText(), txId, docId)
                : null;
        if (buffered != null) {
            value = buffered.kind() == TxState.Kind.REMOVE ? null : buffered.value();
        } else {
            value = repository.get(docId);
        }

        log.info(StructuredLog.event(
                "handler_get_entry", "region", region, "key", key, "docId", docId,
                "present", value != null, "txId", txId,
                "buffered", buffered == null ? "none" : buffered.kind().name()));

        byte[] response = (value == null)
                ? GemResponseWriter.buildGetEntryNotFoundResponse(txId)
                : GemResponseWriter.buildGetEntryResponse(txId, key, value);
        ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
    }
}
