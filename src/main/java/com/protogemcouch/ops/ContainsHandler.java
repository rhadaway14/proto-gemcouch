package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.StoredValue;
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

    public ContainsHandler(Repository repository) {
        this.repository = repository;
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