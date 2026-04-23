package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.util.DocumentKeyUtil;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class PutAllHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(PutAllHandler.class);

    private final Repository repository;

    public PutAllHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        log.info(StructuredLog.event(
                "handler_put_all_start",
                "region", region,
                "txId", frame.getTransactionId()
        ));

        if (frame.getParts().size() < 5) {
            log.warn(StructuredLog.event(
                    "handler_put_all_frame_too_small",
                    "parts", frame.getParts().size(),
                    "txId", frame.getTransactionId()
            ));
            ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildPutAllChunkedResponse(frame.getTransactionId())));
            return;
        }

        byte[] eventIdPayload = frame.getParts().get(1).getPayload();
        int skipCallbacks = ByteUtils.bytesToInt(frame.getParts().get(2).getPayload());
        int flags = ByteUtils.bytesToInt(frame.getParts().get(3).getPayload());
        int size = ByteUtils.bytesToInt(frame.getParts().get(4).getPayload());

        log.info(StructuredLog.event(
                "handler_put_all_metadata",
                "region", region,
                "eventIdHex", ByteBufUtil.hexDump(eventIdPayload),
                "skipCallbacks", skipCallbacks,
                "flags", flags,
                "size", size,
                "txId", frame.getTransactionId()
        ));

        Map<String, String> entries = new LinkedHashMap<>();

        int partIndex = 5;
        for (int i = 0; i < size; i++) {
            if (partIndex + 1 >= frame.getParts().size()) {
                log.warn(StructuredLog.event(
                        "handler_put_all_truncated",
                        "entryIndex", i,
                        "txId", frame.getTransactionId()
                ));
                break;
            }

            GemPart keyPart = frame.getParts().get(partIndex++);
            GemPart valuePart = frame.getParts().get(partIndex++);

            String key = ByteUtils.bytesToString(keyPart.getPayload());

            Object rawValue;
            try {
                rawValue = GeodeSerialization.deserializeObject(valuePart.getPayload());
            } catch (Exception e) {
                log.warn(StructuredLog.event(
                        "handler_put_all_value_deserialize_error",
                        "key", key,
                        "error", e.getMessage(),
                        "txId", frame.getTransactionId()
                ));
                rawValue = null;
            }

            String value = rawValue == null ? null : String.valueOf(rawValue);

            log.debug(StructuredLog.event(
                    "handler_put_all_entry",
                    "key", key,
                    "hasValue", value != null,
                    "valueHex", ByteBufUtil.hexDump(valuePart.getPayload()),
                    "txId", frame.getTransactionId()
            ));

            entries.put(key, value);
        }

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (entry.getValue() != null) {
                String docId = DocumentKeyUtil.docId(region, entry.getKey());
                repository.put(docId, entry.getValue());

                log.info(StructuredLog.event(
                        "handler_put_all_store_ok",
                        "region", region,
                        "key", entry.getKey(),
                        "docId", docId,
                        "txId", frame.getTransactionId()
                ));
            } else {
                log.warn(StructuredLog.event(
                        "handler_put_all_skip_null",
                        "region", region,
                        "key", entry.getKey(),
                        "txId", frame.getTransactionId()
                ));
            }
        }

        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildPutAllChunkedResponse(frame.getTransactionId())));
    }
}