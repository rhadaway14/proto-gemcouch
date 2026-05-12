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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

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
        StoredValue value = null;

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
                    "valueType", value.type(),
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

    private StoredValue decodePutValue(byte[] valuePayload, int txId) {
        String geodeStringValue = ValueEncoding.decodeGeodeStringValue(valuePayload);

        if (geodeStringValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-string",
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
         *   String[] {"one"}                       -> 40 01 57 00 03 6f 6e 65
         *   ArrayList<String> ["one"]              -> 41 01 57 00 03 6f 6e 65
         *   Empty HashMap                          -> 43 00
         *   Non-empty LinkedHashMap                -> 2c ac ed 00 05 ...
         *   byte[] {1,2,3} via DataSerializer      -> 2e 03 01 02 03
         *   Boolean.TRUE                           -> 35 01
         *   Character 'A'                          -> 36 00 41
         *   Byte 7                                 -> 37 07
         *   Short 7                                -> 38 00 07
         *   Integer 100                            -> 39 00 00 00 64
         *   Long 100L                              -> 3a 00 00 00 00 00 00 00 64
         *   Float 7.25f                            -> 3b 40 e8 00 00
         *   Double 7.25d                           -> 3c 40 1d 00 00 00 00 00 00
         *   Date(1000L)                            -> 3d 00 00 00 00 00 00 03 e8
         *
         * can be incorrectly treated as text or raw binary.
         */
        String[] stringArrayValue = ValueDecoding.decodeStringArrayValue(valuePayload);

        if (stringArrayValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-string-array",
                    "valueType", "STRING_ARRAY",
                    "txId", txId
            ));
            return StoredValue.stringArrayValue(stringArrayValue);
        }

        ArrayList<String> stringArrayListValue = ValueDecoding.decodeStringArrayListValue(valuePayload);

        if (stringArrayListValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-string-array-list",
                    "valueType", "STRING_ARRAY_LIST",
                    "txId", txId
            ));
            return StoredValue.stringArrayListValue(stringArrayListValue);
        }

        LinkedHashMap<String, String> stringHashMapValue = ValueDecoding.decodeStringHashMapValue(valuePayload);

        if (stringHashMapValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-string-hash-map",
                    "valueType", "STRING_HASH_MAP",
                    "txId", txId
            ));
            return StoredValue.stringHashMapValue(stringHashMapValue);
        }

        LinkedHashMap<String, Object> stringObjectHashMapValue = ValueDecoding.decodeStringObjectHashMapValue(valuePayload);

        if (stringObjectHashMapValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-string-object-hash-map",
                    "valueType", "STRING_OBJECT_HASH_MAP",
                    "txId", txId
            ));
            return StoredValue.stringObjectHashMapValue(stringObjectHashMapValue);
        }

        byte[] byteArrayValue = ValueDecoding.decodeByteArrayValue(valuePayload);

        if (byteArrayValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-byte-array",
                    "valueType", "BYTE_ARRAY",
                    "txId", txId
            ));
            return StoredValue.byteArrayValue(byteArrayValue);
        }

        Boolean booleanValue = ValueDecoding.decodeBooleanValue(valuePayload);

        if (booleanValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-boolean",
                    "valueType", "BOOLEAN",
                    "txId", txId
            ));
            return StoredValue.booleanValue(booleanValue);
        }

        Character characterValue = ValueDecoding.decodeCharacterValue(valuePayload);

        if (characterValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-character",
                    "valueType", "CHARACTER",
                    "txId", txId
            ));
            return StoredValue.characterValue(characterValue);
        }

        Byte byteValue = ValueDecoding.decodeByteValue(valuePayload);

        if (byteValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-byte",
                    "valueType", "BYTE",
                    "txId", txId
            ));
            return StoredValue.byteValue(byteValue);
        }

        Short shortValue = ValueDecoding.decodeShortValue(valuePayload);

        if (shortValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-short",
                    "valueType", "SHORT",
                    "txId", txId
            ));
            return StoredValue.shortValue(shortValue);
        }

        Integer integerValue = ValueDecoding.decodeIntegerValue(valuePayload);

        if (integerValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-integer",
                    "valueType", "INTEGER",
                    "txId", txId
            ));
            return StoredValue.integerValue(integerValue);
        }

        Long longValue = ValueDecoding.decodeLongValue(valuePayload);

        if (longValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-long",
                    "valueType", "LONG",
                    "txId", txId
            ));
            return StoredValue.longValue(longValue);
        }

        Float floatValue = ValueDecoding.decodeFloatValue(valuePayload);

        if (floatValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-float",
                    "valueType", "FLOAT",
                    "txId", txId
            ));
            return StoredValue.floatValue(floatValue);
        }

        Double doubleValue = ValueDecoding.decodeDoubleValue(valuePayload);

        if (doubleValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-double",
                    "valueType", "DOUBLE",
                    "txId", txId
            ));
            return StoredValue.doubleValue(doubleValue);
        }

        Date dateValue = ValueDecoding.decodeDateValue(valuePayload);

        if (dateValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "geode-date",
                    "valueType", "DATE",
                    "txId", txId
            ));
            return StoredValue.dateValue(dateValue);
        }

        /*
         * Real Geode client Region.put(key, byte[]) sends the value part as raw
         * bytes instead of the DataSerializer byte-array wrapper.
         *
         * This must run after all known typed Geode decoders and before the
         * generic string-like fallback.
         */
        byte[] rawByteArrayValue = ValueDecoding.decodeRawByteArrayValue(valuePayload);

        if (rawByteArrayValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "raw-byte-array",
                    "valueType", "BYTE_ARRAY",
                    "txId", txId
            ));
            return StoredValue.byteArrayValue(rawByteArrayValue);
        }

        String stringLikeValue = ValueDecoding.decodeStringLikeValue(valuePayload);

        if (stringLikeValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_value_decode_ok",
                    "encoding", "string-like",
                    "valueType", "STRING",
                    "txId", txId
            ));
            return StoredValue.stringValue(stringLikeValue);
        }

        try {
            Object rawValue = GeodeSerialization.deserializeObject(valuePayload);

            if (rawValue instanceof String[] stringArrayObject) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "STRING_ARRAY",
                        "txId", txId
                ));
                return StoredValue.stringArrayValue(stringArrayObject);
            }

            if (rawValue instanceof ArrayList<?> arrayListObject && isStringArrayList(arrayListObject)) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "STRING_ARRAY_LIST",
                        "txId", txId
                ));
                return StoredValue.stringArrayListValue(toStringArrayList(arrayListObject));
            }

            if (rawValue instanceof Map<?, ?> mapObject && isStringStringMap(mapObject)) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "STRING_HASH_MAP",
                        "txId", txId
                ));
                return StoredValue.stringHashMapValue(toStringStringLinkedHashMap(mapObject));
            }

            if (rawValue instanceof Map<?, ?> mapObject && isSupportedStringObjectMap(mapObject)) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "STRING_OBJECT_HASH_MAP",
                        "txId", txId
                ));
                return StoredValue.stringObjectHashMapValue(toStringObjectLinkedHashMap(mapObject));
            }

            if (rawValue instanceof byte[] byteArrayObject) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "BYTE_ARRAY",
                        "txId", txId
                ));
                return StoredValue.byteArrayValue(byteArrayObject);
            }

            if (rawValue instanceof Boolean bool) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "BOOLEAN",
                        "txId", txId
                ));
                return StoredValue.booleanValue(bool);
            }

            if (rawValue instanceof Character characterObject) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "CHARACTER",
                        "txId", txId
                ));
                return StoredValue.characterValue(characterObject);
            }

            if (rawValue instanceof Byte byteObject) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "BYTE",
                        "txId", txId
                ));
                return StoredValue.byteValue(byteObject);
            }

            if (rawValue instanceof Short shortObject) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "SHORT",
                        "txId", txId
                ));
                return StoredValue.shortValue(shortObject);
            }

            if (rawValue instanceof Integer integer) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "INTEGER",
                        "txId", txId
                ));
                return StoredValue.integerValue(integer);
            }

            if (rawValue instanceof Long longObject) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "LONG",
                        "txId", txId
                ));
                return StoredValue.longValue(longObject);
            }

            if (rawValue instanceof Float floatObject) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "FLOAT",
                        "txId", txId
                ));
                return StoredValue.floatValue(floatObject);
            }

            if (rawValue instanceof Double doubleObject) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "DOUBLE",
                        "txId", txId
                ));
                return StoredValue.doubleValue(doubleObject);
            }

            if (rawValue instanceof Date dateObject) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "DATE",
                        "txId", txId
                ));
                return StoredValue.dateValue(dateObject);
            }

            if (rawValue != null) {
                log.info(StructuredLog.event(
                        "handler_put_value_decode_ok",
                        "encoding", "geode-dataserializer",
                        "type", rawValue.getClass().getName(),
                        "valueType", "STRING",
                        "txId", txId
                ));
                return StoredValue.stringValue(String.valueOf(rawValue));
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
                "payloadHex", ByteBufUtil.hexDump(valuePayload),
                "payloadLength", valuePayload == null ? -1 : valuePayload.length,
                "txId", txId
        ));

        return null;
    }

    private static boolean isStringArrayList(ArrayList<?> value) {
        for (Object item : value) {
            if (item != null && !(item instanceof String)) {
                return false;
            }
        }

        return true;
    }

    private static ArrayList<String> toStringArrayList(ArrayList<?> value) {
        ArrayList<String> out = new ArrayList<>(value.size());

        for (Object item : value) {
            out.add(item == null ? null : String.valueOf(item));
        }

        return out;
    }

    private static boolean isStringStringMap(Map<?, ?> value) {
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();
            Object mapValue = entry.getValue();

            if (key != null && !(key instanceof String)) {
                return false;
            }

            if (mapValue != null && !(mapValue instanceof String)) {
                return false;
            }
        }

        return true;
    }

    private static LinkedHashMap<String, String> toStringStringLinkedHashMap(Map<?, ?> value) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();
            Object mapValue = entry.getValue();

            out.put(
                    key == null ? null : String.valueOf(key),
                    mapValue == null ? null : String.valueOf(mapValue)
            );
        }

        return out;
    }

    private static boolean isSupportedStringObjectMap(Map<?, ?> value) {
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();
            Object mapValue = entry.getValue();

            if (key != null && !(key instanceof String)) {
                return false;
            }

            if (!isSupportedMapObjectValue(mapValue)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isSupportedMapObjectValue(Object value) {
        return value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof Date
                || value instanceof byte[]
                || value instanceof String[]
                || isSupportedStringArrayListObject(value);
    }

    private static boolean isSupportedStringArrayListObject(Object value) {
        if (!(value instanceof ArrayList<?> list)) {
            return false;
        }

        for (Object item : list) {
            if (item != null && !(item instanceof String)) {
                return false;
            }
        }

        return true;
    }

    private static LinkedHashMap<String, Object> toStringObjectLinkedHashMap(Map<?, ?> value) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : value.entrySet()) {
            Object key = entry.getKey();

            out.put(
                    key == null ? null : String.valueOf(key),
                    copySupportedMapObjectValue(entry.getValue())
            );
        }

        return out;
    }

    private static Object copySupportedMapObjectValue(Object value) {
        if (value instanceof byte[] bytes) {
            byte[] copy = new byte[bytes.length];
            System.arraycopy(bytes, 0, copy, 0, bytes.length);
            return copy;
        }

        if (value instanceof String[] strings) {
            String[] copy = new String[strings.length];
            System.arraycopy(strings, 0, copy, 0, strings.length);
            return copy;
        }

        if (value instanceof ArrayList<?> list) {
            ArrayList<String> copy = new ArrayList<>(list.size());

            for (Object item : list) {
                copy.add(item == null ? null : String.valueOf(item));
            }

            return copy;
        }

        if (value instanceof Date date) {
            return new Date(date.getTime());
        }

        return value;
    }
}