package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.subscription.SubscriptionRegistry;
import com.protogemcouch.tx.TransactionRegistry;
import com.protogemcouch.tx.TxState;
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
    private final TransactionRegistry transactions;
    private final SubscriptionRegistry subscriptions;

    public RemoveHandler(Repository repository, TransactionRegistry transactions,
                         SubscriptionRegistry subscriptions) {
        this.repository = repository;
        this.transactions = transactions;
        this.subscriptions = subscriptions;
    }

    public RemoveHandler(Repository repository, TransactionRegistry transactions) {
        this(repository, transactions, new SubscriptionRegistry());
    }

    /** Convenience for non-transactional callers/tests: uses private, empty registries. */
    public RemoveHandler(Repository repository) {
        this(repository, new TransactionRegistry(), new SubscriptionRegistry());
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
        StoredValue expected = isCompareRemove
                ? PutHandler.decodePutValue(frame.getParts().get(2).getPayload(), txId) : null;

        // Transactional remove: buffer the delete in the transaction's state; it is applied on COMMIT
        // and discarded on ROLLBACK. remove(k,v) honors its compare semantics against the transaction's
        // effective view (read-your-writes over committed state).
        if (txId >= 0) {
            TxState tx = transactions.getOrCreate(ctx.channel().id().asLongText(), txId);
            if (isCompareRemove) {
                StoredValue effective = effectiveValue(tx, docId);
                boolean removed = expected != null && effective != null && effective.equals(expected);
                if (removed) {
                    tx.remove(docId);
                }
                log.info(StructuredLog.event("handler_remove_if_value_tx",
                        "region", region, "key", key, "docId", docId, "removed", removed, "txId", txId));
                ctx.writeAndFlush(Unpooled.wrappedBuffer(
                        GemResponseWriter.buildRemoveResponseWithEntryNotFound(txId, !removed)));
                return;
            }
            tx.remove(docId);
            log.info(StructuredLog.event(
                    "handler_remove_tx_buffered",
                    "region", region, "key", key, "docId", docId, "txId", txId));
            ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildRemoveResponse(txId)));
            return;
        }

        if (isCompareRemove) {
            boolean removed = expected != null && repository.removeIfValue(docId, expected);
            // entryNotFound = !removed: on a value mismatch (or miss) the client raises
            // EntryNotFoundException, which Region.remove(k,v) maps to a false return.
            log.info(StructuredLog.event(
                    "handler_remove_if_value",
                    "region", region, "key", key, "docId", docId, "removed", removed, "txId", txId));
            ctx.writeAndFlush(Unpooled.wrappedBuffer(
                    GemResponseWriter.buildRemoveResponseWithEntryNotFound(txId, !removed)));
            return;
        }

        log.info(StructuredLog.event(
                "handler_remove", "region", region, "key", key, "docId", docId, "txId", txId));
        // A CQ DESTROY must evaluate the predicate against the value before removal, so read it first
        // (only when a CQ exists on this region, to avoid the extra read otherwise).
        StoredValue priorValue = subscriptions.hasCqOnRegion(region) ? repository.get(docId) : null;
        repository.remove(docId);
        String originClientId = SubscriptionRegistry.clientId(ctx);
        subscriptions.publishDestroy(region, key, originClientId);
        subscriptions.publishCqDestroy(region, key, priorValue, originClientId);
        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildRemoveResponse(txId)));
    }

    /** The value a transaction currently sees for {@code docId} (buffered write, or committed). */
    private StoredValue effectiveValue(TxState tx, String docId) {
        TxState.Op op = tx.lookup(docId);
        if (op != null) {
            return op.kind() == TxState.Kind.PUT ? op.value() : null;
        }
        return repository.get(docId);
    }

    private static int firstByte(byte[] payload) {
        return payload != null && payload.length > 0 ? (payload[0] & 0xff) : -1;
    }
}