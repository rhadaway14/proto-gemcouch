package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GetAllHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(GetAllHandler.class);

    private final Repository repository;

    public GetAllHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        try {
            log.debug(StructuredLog.event(
                    "handler_get_all_start",
                    "region", region,
                    "part_count", frame.getParts().size(),
                    "txId", frame.getTransactionId()
            ));

            logFrameParts(frame);

            List<String> keys = new ArrayList<>();

            if (frame.getParts().size() > 1) {
                byte[] keyPayload = frame.getParts().get(1).getPayload();

                log.debug(StructuredLog.event(
                        "handler_get_all_keys_payload",
                        "region", region,
                        "hex", ByteBufUtil.hexDump(keyPayload),
                        "text", ByteUtils.bytesToString(keyPayload),
                        "txId", frame.getTransactionId()
                ));

                logGetAllKeyHints(keyPayload);

                try {
                    keys = GeodeSerialization.deserializeGetAllKeys(keyPayload);

                    log.info(StructuredLog.event(
                            "handler_get_all_keys_decoded",
                            "region", region,
                            "key_count", keys.size(),
                            "keys", keys.toString(),
                            "txId", frame.getTransactionId()
                    ));
                } catch (Exception e) {
                    log.warn(StructuredLog.event(
                            "handler_get_all_key_deserialize_error",
                            "region", region,
                            "error", e.getMessage(),
                            "txId", frame.getTransactionId()
                    ), e);
                }
            }

            if (frame.getParts().size() > 2) {
                byte[] callbackPayload = frame.getParts().get(2).getPayload();

                log.debug(StructuredLog.event(
                        "handler_get_all_callback_payload",
                        "region", region,
                        "hex", ByteBufUtil.hexDump(callbackPayload),
                        "intValue", ByteUtils.bytesToInt(callbackPayload),
                        "txId", frame.getTransactionId()
                ));
            }

            Map<String, StoredValue> results;

            if (keys.isEmpty()) {
                log.warn(StructuredLog.event(
                        "handler_get_all_empty_keys",
                        "region", region,
                        "txId", frame.getTransactionId()
                ));

                /*
                 * This preserves the previous test-safe behavior for an empty key list.
                 * The real Geode client should normally provide keys. If it does not,
                 * we return a single absent placeholder so the response writer does not
                 * attempt to create a zero-sized VersionedObjectList.
                 */
                keys = List.of("__protogemcouch_empty_getall_placeholder__");
                results = new LinkedHashMap<>();
                results.put("__protogemcouch_empty_getall_placeholder__", null);
            } else {
                /*
                 * Keep the values typed all the way into GemResponseWriter.
                 *
                 * This is required for GET_ALL with Integer values and mixed
                 * String/Integer values. Flattening to String here would cause
                 * integer values to come back to the Geode client as String.
                 */
                results = repository.getAll(region, keys);
            }

            log.info(StructuredLog.event(
                    "handler_get_all",
                    "region", region,
                    "key_count", keys.size(),
                    "result_count", results.size(),
                    "txId", frame.getTransactionId()
            ));

            byte[] responseBytes = GemResponseWriter.buildGetAllChunkedResponse(
                    frame.getTransactionId(),
                    keys,
                    results
            );

            log.info(StructuredLog.event(
                    "handler_get_all_response_built",
                    "region", region,
                    "response_bytes", responseBytes.length,
                    "txId", frame.getTransactionId()
            ));

            ChannelFuture writeFuture = ctx.writeAndFlush(Unpooled.wrappedBuffer(responseBytes));

            /*
             * In real Netty runtime, writeAndFlush returns a ChannelFuture.
             * In unit tests with an unstubbed Mockito ChannelHandlerContext,
             * it may return null. Do not treat that as a production handler error.
             */
            if (writeFuture == null) {
                log.debug(StructuredLog.event(
                        "handler_get_all_response_write_future_null",
                        "region", region,
                        "response_bytes", responseBytes.length,
                        "txId", frame.getTransactionId()
                ));
                return;
            }

            writeFuture.addListener(future -> {
                if (future.isSuccess()) {
                    log.info(StructuredLog.event(
                            "handler_get_all_response_written",
                            "region", region,
                            "response_bytes", responseBytes.length,
                            "txId", frame.getTransactionId()
                    ));
                } else {
                    log.warn(StructuredLog.event(
                            "handler_get_all_response_write_failed",
                            "region", region,
                            "error", future.cause() == null ? "unknown" : future.cause().getMessage(),
                            "txId", frame.getTransactionId()
                    ), future.cause());
                }
            });
        } catch (Throwable t) {
            log.error(StructuredLog.event(
                    "handler_get_all_unhandled_error",
                    "region", region,
                    "error_type", t.getClass().getName(),
                    "error", t.getMessage(),
                    "txId", frame.getTransactionId()
            ), t);

            ctx.close();
        }
    }

    private void logFrameParts(GemFrame frame) {
        for (int i = 0; i < frame.getParts().size(); i++) {
            GemPart part = frame.getParts().get(i);
            byte[] payload = part.getPayload();

            log.debug(StructuredLog.event(
                    "handler_get_all_part",
                    "index", i,
                    "length", payload.length,
                    "type", String.format("0x%02x", part.getTypeCode()),
                    "hex", ByteBufUtil.hexDump(payload),
                    "text", new String(payload, StandardCharsets.UTF_8).replace("\u0000", "").trim(),
                    "txId", frame.getTransactionId()
            ));
        }
    }

    private void logGetAllKeyHints(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        String[] hints = {
                "proto::getall-1",
                "proto::getall-2",
                "proto::getall-missing",
                "it-getall-1",
                "it-getall-2",
                "it-getall-3",
                "it-getall-existing-1",
                "it-getall-existing-2",
                "it-getall-missing",
                "it-getall-int-1",
                "it-getall-int-2",
                "it-getall-int-3",
                "it-mixed-string-1",
                "it-mixed-string-2",
                "it-mixed-integer-1",
                "it-mixed-integer-2"
        };

        for (String hint : hints) {
            if (text.contains(hint)) {
                log.debug(StructuredLog.event(
                        "handler_get_all_key_hint_found",
                        "hint", hint
                ));
            }
        }
    }
}