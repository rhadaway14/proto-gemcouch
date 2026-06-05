package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.tx.TxState;
import com.protogemcouch.tx.TransactionRegistry;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.util.DocumentKeyUtil;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import com.protogemcouch.wire.MessageTypes;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContainsHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(ContainsHandler.class);

    private final Repository repository;
    private final TransactionRegistry transactions;

    public ContainsHandler(Repository repository, TransactionRegistry transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    /** Convenience for non-transactional callers/tests: uses a private, empty transaction registry. */
    public ContainsHandler(Repository repository) {
        this(repository, new TransactionRegistry());
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        String key = frame.getParts().size() > 1
                ? ByteUtils.bytesToString(frame.getParts().get(1).getPayload())
                : "";

        int mode = frame.getParts().size() > 2
                ? ByteUtils.bytesToInt(frame.getParts().get(2).getPayload())
                : MessageTypes.CONTAINS_MODE_KEY;

        String docId = DocumentKeyUtil.docId(region, key);

        boolean repositoryContainsResult = false;
        boolean repositoryGetFallbackResult = false;
        String fallbackValueType = "null";
        boolean result;

        // Read-your-writes: a key this transaction has written/removed is answered from the buffer.
        TxState.Op buffered = frame.getTransactionId() >= 0
                ? transactions.peekOp(ctx.channel().id().asLongText(), frame.getTransactionId(), docId)
                : null;
        if (buffered != null && mode != MessageTypes.CONTAINS_MODE_VALUE) {
            // A buffered remove means absent; a buffered put means present (and value-for-key true,
            // since buffered puts always carry a value).
            result = buffered.kind() == TxState.Kind.PUT;
            log.info(StructuredLog.event(
                    "handler_contains_tx_read_your_writes",
                    "docId", docId, "mode", mode, "result", result,
                    "buffered", buffered.kind().name(), "txId", frame.getTransactionId()));
            ctx.writeAndFlush(Unpooled.wrappedBuffer(
                    GemResponseWriter.buildContainsResponse(frame.getTransactionId(), result)));
            return;
        }

        if (mode == MessageTypes.CONTAINS_MODE_KEY) {
            repositoryContainsResult = repository.containsKey(docId);

            if (!repositoryContainsResult) {
                StoredValue fallbackValue = repository.get(docId);
                repositoryGetFallbackResult = fallbackValue != null;
                fallbackValueType = fallbackValue == null ? "null" : fallbackValue.type().name();
            }

            result = repositoryContainsResult || repositoryGetFallbackResult;
        } else if (mode == MessageTypes.CONTAINS_MODE_VALUE_FOR_KEY) {
            repositoryContainsResult = repository.containsValueForKey(docId);

            if (!repositoryContainsResult) {
                StoredValue fallbackValue = repository.get(docId);
                repositoryGetFallbackResult = fallbackValue != null;
                fallbackValueType = fallbackValue == null ? "null" : fallbackValue.type().name();
            }

            result = repositoryContainsResult || repositoryGetFallbackResult;
        } else if (mode == MessageTypes.CONTAINS_MODE_VALUE) {
            result = false;
        } else {
            result = false;
        }

        log.info(StructuredLog.event(
                "handler_contains",
                "region", region,
                "key", key,
                "docId", docId,
                "mode", mode,
                "result", result,
                "repositoryContainsResult", repositoryContainsResult,
                "repositoryGetFallbackResult", repositoryGetFallbackResult,
                "fallbackValueType", fallbackValueType,
                "txId", frame.getTransactionId()
        ));

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildContainsResponse(frame.getTransactionId(), result)
        ));
    }
}