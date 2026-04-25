package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.serialization.ValueDecoding;
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

            try {
                Object rawValue = GeodeSerialization.deserializeObject(valuePayload);
                if (rawValue != null) {
                    value = String.valueOf(rawValue);
                }
            } catch (Throwable t) {
                log.warn(StructuredLog.event(
                        "handler_put_value_deserialize_error",
                        "error", t.getMessage(),
                        "txId", frame.getTransactionId()
                ));

                value = ValueDecoding.decodeStringLikeValue(valuePayload);

                if (value != null) {
                    log.info(StructuredLog.event(
                            "handler_put_value_fallback_decode_ok",
                            "txId", frame.getTransactionId()
                    ));
                } else {
                    log.warn(StructuredLog.event(
                            "handler_put_value_fallback_decode_failed",
                            "txId", frame.getTransactionId()
                    ));
                }
            }
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
}