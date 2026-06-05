package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.tx.TxState;
import com.protogemcouch.tx.TransactionRegistry;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.util.DocumentKeyUtil;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

public class SizeOnServerHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(SizeOnServerHandler.class);

    private final Repository repository;
    private final TransactionRegistry transactions;

    public SizeOnServerHandler(Repository repository, TransactionRegistry transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    /** Convenience for non-transactional callers/tests: uses a private, empty transaction registry. */
    public SizeOnServerHandler(Repository repository) {
        this(repository, new TransactionRegistry());
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        int size;
        int txId = frame.getTransactionId();
        TxState state = txId >= 0 ? transactions.peek(ctx.channel().id().asLongText(), txId) : null;
        TxState.RegionOverlay overlay = state == null ? null : state.regionOverlay(DocumentKeyUtil.regionPrefix(region));
        if (overlay == null || overlay.isEmpty()) {
            size = repository.size(region);
        } else {
            // Read-your-writes: count committed keys with this transaction's buffered adds/removes
            // applied. Uses keySet so the count reflects which buffered keys are genuinely new.
            Set<String> keys = new LinkedHashSet<>(repository.keySet(region));
            keys.addAll(overlay.puts().keySet());
            keys.removeAll(overlay.removed());
            size = keys.size();
        }

        log.info(StructuredLog.event(
                "handler_size",
                "region", region,
                "size", size,
                "txId", frame.getTransactionId()
        ));

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildSizeResponse(frame.getTransactionId(), size)
        ));
    }
}