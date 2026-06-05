package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.tx.TxState;
import com.protogemcouch.tx.TransactionRegistry;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Handles COMMIT (opcode 85). Applies the transaction's buffered writes to the repository in
 * first-seen order, then returns a zero-region {@code TXCommitMessage} the client accepts as the
 * commit ack. Apply is best-effort sequential (not cross-key atomic); a mid-apply failure surfaces a
 * Geode EXCEPTION response after the partial apply, which is honest about the bounded semantics.
 */
public class CommitHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(CommitHandler.class);

    private final Repository repository;
    private final TransactionRegistry transactions;

    public CommitHandler(Repository repository, TransactionRegistry transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        int txId = frame.getTransactionId();
        String channelId = ctx.channel().id().asLongText();
        TxState state = transactions.remove(channelId, txId);

        List<TxState.Op> ops = state == null ? List.of() : state.ops();
        int applied = 0;
        try {
            for (TxState.Op op : ops) {
                if (op.kind() == TxState.Kind.REMOVE) {
                    repository.remove(op.docId());
                } else {
                    repository.put(op.docId(), op.value());
                }
                applied++;
            }
        } catch (RuntimeException e) {
            log.error(StructuredLog.event(
                    "handler_commit_failed",
                    "txId", txId,
                    "applied", applied,
                    "buffered", ops.size(),
                    "error", e.getMessage()));
            ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildExceptionResponse(
                    txId,
                    "commit failed after applying " + applied + " of " + ops.size()
                            + " operations: " + e.getMessage())));
            return;
        }

        log.info(StructuredLog.event(
                "handler_commit_ok",
                "txId", txId,
                "applied", applied));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildCommitResponse(txId)));
    }
}
