package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
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
            } catch (Exception e) {
                log.warn(StructuredLog.event(
                        "handler_get_all_key_deserialize_error",
                        "region", region,
                        "error", e.getMessage(),
                        "txId", frame.getTransactionId()
                ));
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

        Map<String, String> results;

        if (keys.isEmpty()) {
            log.warn(StructuredLog.event(
                    "handler_get_all_empty_keys",
                    "region", region,
                    "txId", frame.getTransactionId()
            ));

            // Geode's ObjectPartList/VersionedObjectList path rejects size=0.
            // Return a safe "missing key" style payload instead of throwing.
            keys = List.of("__protogemcouch_empty_getall_placeholder__");
            results = new LinkedHashMap<>();
            results.put("__protogemcouch_empty_getall_placeholder__", null);
        } else {
            results = repository.getAll(region, keys);
        }

        log.info(StructuredLog.event(
                "handler_get_all",
                "region", region,
                "key_count", keys.size(),
                "result_count", results.size(),
                "txId", frame.getTransactionId()
        ));

        byte[] responseBytes = GemResponseWriter.buildGetAllChunkedResponse(frame.getTransactionId(), keys, results);
        ctx.writeAndFlush(Unpooled.wrappedBuffer(responseBytes));
    }

    private void logGetAllKeyHints(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        String[] hints = {
                "proto::getall-1",
                "proto::getall-2",
                "proto::getall-missing"
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