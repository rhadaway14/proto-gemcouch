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

import java.util.Date;
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
         * Decode typed values before the generic string-like fallback.
         *
         * Otherwise payloads such as:
         *
         *   byte[] {1,2,3} via DataSerializer -> 2e 03 01 02 03
         *   Boolean.TRUE                      -> 35 01
         *   Character 'A'                     -> 36 00 41
         *   Byte 7                            -> 37 07
         *   Short 7                           -> 38 00 07
         *   Integer 101                       -> 39 00 00 00 65
         *   Long 101L                         -> 3a 00 00 00 00 00 00 00 65
         *   Float 7.25f                       -> 3b 40 e8 00 00
         *   Double 7.25d                      -> 3c 40 1d 00 00 00 00 00 00
         *   Date(1000L)                       -> 3d 00 00 00 00 00 00 03 e8
         *
         * can be incorrectly decoded as text.
         */
        byte[] byteArrayValue = ValueDecoding.decodeByteArrayValue(valuePayload);

        if (byteArrayValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-byte-array",
                    "key", key,
                    "valueType", "BYTE_ARRAY",
                    "txId", txId
            ));
            return StoredValue.byteArrayValue(byteArrayValue);
        }

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

        Character characterValue = ValueDecoding.decodeCharacterValue(valuePayload);

        if (characterValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-character",
                    "key", key,
                    "valueType", "CHARACTER",
                    "txId", txId
            ));
            return StoredValue.characterValue(characterValue);
        }

        Byte byteValue = ValueDecoding.decodeByteValue(valuePayload);

        if (byteValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-byte",
                    "key", key,
                    "valueType", "BYTE",
                    "txId", txId
            ));
            return StoredValue.byteValue(byteValue);
        }

        Short shortValue = ValueDecoding.decodeShortValue(valuePayload);

        if (shortValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-short",
                    "key", key,
                    "valueType", "SHORT",
                    "txId", txId
            ));
            return StoredValue.shortValue(shortValue);
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

        Date dateValue = ValueDecoding.decodeDateValue(valuePayload);

        if (dateValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-date",
                    "key", key,
                    "valueType", "DATE",
                    "txId", txId
            ));
            return StoredValue.dateValue(dateValue);
        }

        /*
         * Real Geode client Region.putAll(... byte[]) sends byte[] value parts as
         * raw payloads instead of the DataSerializer byte-array wrapper.
         *
         * This must run after all known typed Geode decoders and before the
         * generic string-like fallback.
         */
        byte[] rawByteArrayValue = ValueDecoding.decodeRawByteArrayValue(valuePayload);

        if (rawByteArrayValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "raw-byte-array",
                    "key", key,
                    "valueType", "BYTE_ARRAY",
                    "txId", txId
            ));
            return StoredValue.byteArrayValue(rawByteArrayValue);
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

            if (rawValue instanceof byte[] byteArrayObject) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "BYTE_ARRAY",
                        "txId", txId
                ));
                return StoredValue.byteArrayValue(byteArrayObject);
            }

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

            if (rawValue instanceof Character characterObject) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "CHARACTER",
                        "txId", txId
                ));
                return StoredValue.characterValue(characterObject);
            }

            if (rawValue instanceof Byte byteObject) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "BYTE",
                        "txId", txId
                ));
                return StoredValue.byteValue(byteObject);
            }

            if (rawValue instanceof Short shortObject) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "SHORT",
                        "txId", txId
                ));
                return StoredValue.shortValue(shortObject);
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

            if (rawValue instanceof Date dateObject) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "DATE",
                        "txId", txId
                ));
                return StoredValue.dateValue(dateObject);
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
                "payloadHex", ByteBufUtil.hexDump(valuePayload),
                "payloadLength", valuePayload == null ? -1 : valuePayload.length,
                "txId", txId
        ));

        return null;
    }
}
