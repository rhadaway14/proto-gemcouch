package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles Geode {@code TX_FAILOVER} (opcode 88). A client with single-hop enabled (the default) sends
 * this to nominate the "transaction host" server before a transactional server read such as
 * {@code getEntry}. Because the shim's partition metadata is a graceful no-op, the client cannot infer
 * the host from metadata and always sends {@code TX_FAILOVER}; the single-backend shim is always the
 * host, so we ack it. {@code TXFailoverOp.processResponse} accepts any {@code REPLY} (it only checks the
 * message type), so a plain REPLY ack suffices — the same shape used for {@code ROLLBACK}.
 *
 * <p>Without this ack the client retries {@code TX_FAILOVER} indefinitely and the transactional read
 * hangs, even though the read itself (e.g. {@code GET_ENTRY}) is answered correctly.
 */
public class TxFailoverHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(TxFailoverHandler.class);

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        log.info(StructuredLog.event("handler_tx_failover", "txId", frame.getTransactionId()));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildSimpleAck(frame.getTransactionId())));
    }
}
