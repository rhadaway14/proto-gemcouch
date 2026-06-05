package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.tx.TxState;
import com.protogemcouch.tx.TransactionRegistry;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles ROLLBACK (opcode 87): discards the transaction's buffered writes without applying them and
 * returns a plain REPLY ack.
 */
public class RollbackHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(RollbackHandler.class);

    private final TransactionRegistry transactions;

    public RollbackHandler(TransactionRegistry transactions) {
        this.transactions = transactions;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        int txId = frame.getTransactionId();
        String channelId = ctx.channel().id().asLongText();
        TxState discarded = transactions.remove(channelId, txId);

        log.info(StructuredLog.event(
                "handler_rollback_ok",
                "txId", txId,
                "discarded", discarded == null ? 0 : discarded.size()));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildRollbackResponse(txId)));
    }
}
