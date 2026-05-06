package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.serialization.StoredValue;
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

            ctx.writeAndFlush(Unpooled.wrappedBuffer(
                    GemResponseWriter.buildPutAllChunkedResponse(frame.getTransactionId())
            ));
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

        Map<String, StoredValue> entries = new LinkedHashMap<>();

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
            byte[] valuePayload = valuePart.getPayload();

            StoredValue value = decodePutAllValue(key, valuePayload, frame.getTransactionId());

            log.debug(StructuredLog.event(
                    "handler_put_all_entry",
                    "key", key,
                    "hasValue", value != null,
                    "valueType", value == null ? "null" : value.type().name(),
                    "valueHex", ByteBufUtil.hexDump(valuePayload),
                    "txId", frame.getTransactionId()
            ));

            entries.put(key, value);
        }

        for (Map.Entry<String, StoredValue> entry : entries.entrySet()) {
            if (entry.getValue() != null) {
                String docId = DocumentKeyUtil.docId(region, entry.getKey());

                repository.put(docId, entry.getValue());

                log.info(StructuredLog.event(
                        "handler_put_all_store_ok",
                        "region", region,
                        "key", entry.getKey(),
                        "docId", docId,
                        "valueType", entry.getValue().type(),
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

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildPutAllChunkedResponse(frame.getTransactionId())
        ));
    }

    private StoredValue decodePutAllValue(String key, byte[] valuePayload, int txId) {
        String geodeStringValue = ValueEncoding.decodeGeodeStringValue(valuePayload);

        if (geodeStringValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-string",
                    "key", key,
                    "valueType", "STRING",
                    "txId", txId
            ));
            return StoredValue.stringValue(geodeStringValue);
        }

        /*
         * Important:
         * Decode typed primitives before the generic string-like fallback.
         *
         * Otherwise payloads such as:
         *
         *   Boolean.TRUE -> 35 01
         *   Integer 101  -> 39 00 00 00 65
         *   Long 101L    -> 3a 00 00 00 00 00 00 00 65
         *   Float 7.25f  -> 3b 40 e8 00 00
         *   Double 7.25d -> 3c 40 1d 00 00 00 00 00 00
         *
         * can be incorrectly decoded as text.
         */
        Boolean booleanValue = ValueDecoding.decodeBooleanValue(valuePayload);

        if (booleanValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-boolean",
                    "key", key,
                    "valueType", "BOOLEAN",
                    "txId", txId
            ));
            return StoredValue.booleanValue(booleanValue);
        }

        Integer integerValue = ValueDecoding.decodeIntegerValue(valuePayload);

        if (integerValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-integer",
                    "key", key,
                    "valueType", "INTEGER",
                    "txId", txId
            ));
            return StoredValue.integerValue(integerValue);
        }

        Long longValue = ValueDecoding.decodeLongValue(valuePayload);

        if (longValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-long",
                    "key", key,
                    "valueType", "LONG",
                    "txId", txId
            ));
            return StoredValue.longValue(longValue);
        }

        Float floatValue = ValueDecoding.decodeFloatValue(valuePayload);

        if (floatValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-float",
                    "key", key,
                    "valueType", "FLOAT",
                    "txId", txId
            ));
            return StoredValue.floatValue(floatValue);
        }

        Double doubleValue = ValueDecoding.decodeDoubleValue(valuePayload);

        if (doubleValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-double",
                    "key", key,
                    "valueType", "DOUBLE",
                    "txId", txId
            ));
            return StoredValue.doubleValue(doubleValue);
        }

        String stringLikeValue = ValueDecoding.decodeStringLikeValue(valuePayload);

        if (stringLikeValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "string-like",
                    "key", key,
                    "valueType", "STRING",
                    "txId", txId
            ));
            return StoredValue.stringValue(stringLikeValue);
        }

        try {
            Object rawValue = GeodeSerialization.deserializeObject(valuePayload);

            if (rawValue instanceof Boolean bool) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "BOOLEAN",
                        "txId", txId
                ));
                return StoredValue.booleanValue(bool);
            }

            if (rawValue instanceof Integer integer) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "INTEGER",
                        "txId", txId
                ));
                return StoredValue.integerValue(integer);
            }

            if (rawValue instanceof Long longObject) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "LONG",
                        "txId", txId
                ));
                return StoredValue.longValue(longObject);
            }

            if (rawValue instanceof Float floatObject) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "FLOAT",
                        "txId", txId
                ));
                return StoredValue.floatValue(floatObject);
            }

            if (rawValue instanceof Double doubleObject) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "DOUBLE",
                        "txId", txId
                ));
                return StoredValue.doubleValue(doubleObject);
            }

            if (rawValue != null) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "STRING",
                        "txId", txId
                ));
                return StoredValue.stringValue(String.valueOf(rawValue));
            }
        } catch (Throwable t) {
            log.warn(StructuredLog.event(
                    "handler_put_all_value_deserialize_error",
                    "key", key,
                    "error", t.getMessage(),
                    "txId", txId
            ));
        }

        log.warn(StructuredLog.event(
                "handler_put_all_value_decode_failed",
                "key", key,
                "txId", txId
        ));

        return null;
    }
}