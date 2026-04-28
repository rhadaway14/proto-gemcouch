package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.serialization.ValueDecoding;
import com.protogemcouch.serialization.ValueEncoding;
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

import java.nio.charset.StandardCharsets;

public class PutHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(PutHandler.class);

    private final Repository repository;

    public PutHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = null;
        String key = null;
        String value = null;

        for (int i = 0; i < frame.getParts().size(); i++) {
            GemPart part = frame.getParts().get(i);
            byte[] payload = part.getPayload();

            log.debug(StructuredLog.event(
                    "handler_put_part",
                    "index", i,
                    "hex", ByteBufUtil.hexDump(payload),
                    "text", new String(payload, StandardCharsets.UTF_8).replace("\u0000", "").trim()
            ));
        }

        if (frame.getParts().size() > 0) {
            region = ByteUtils.bytesToString(frame.getParts().get(0).getPayload());
        }

        if (frame.getParts().size() > 3) {
            key = ByteUtils.bytesToString(frame.getParts().get(3).getPayload());
        }

        if (frame.getParts().size() > 5) {
            byte[] valuePayload = frame.getParts().get(5).getPayload();
            value = decodePutValue(valuePayload, frame.getTransactionId());
        }

        if (region != null && !region.isBlank()
                && key != null && !key.isBlank()
                && value != null) {
            String docId = DocumentKeyUtil.docId(region, key);
            repository.put(docId, value);

            log.info(StructuredLog.event(
                    "handler_put_ok",
                    "region", region,
                    "key", key,
                    "docId", docId,
                    "txId", frame.getTransactionId()
            ));
        } else {
            log.warn(StructuredLog.event(
                    "handler_put_parse_failed",
                    "region", region,
                    "key", key,
                    "has_value", value != null,
                    "txId", frame.getTransactionId()
            ));
        }

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildPutResponse(frame.getTransactionId())
        ));
    }

    private String decodePutValue(byte[] valuePayload, int txId) {
        String geodeStringValue = ValueEncoding.decodeGeodeStringValue(valuePayload);

        if (geodeStringValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-string",
                    "txId", txId
            ));
            return geodeStringValue;
        }

        String stringLikeValue = ValueDecoding.decodeStringLikeValue(valuePayload);

        if (stringLikeValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "string-like",
                    "txId", txId
            ));
            return stringLikeValue;
        }

        try {
            Object rawValue = GeodeSerialization.deserializeObject(valuePayload);
            if (rawValue != null) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "txId", txId
                ));
                return String.valueOf(rawValue);
            }
        } catch (Throwable t) {
            log.debug(StructuredLog.event(
                    "handler_put_value_dataserializer_decode_skipped",
                    "error", t.getMessage(),
                    "txId", txId
            ));
        }

        log.warn(StructuredLog.event(
                "handler_put_value_decode_failed",
                "txId", txId
        ));

        return null;
    }
}