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

import java.util.ArrayList;
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
         *   String[] {"one"}                       -> 40 01 57 00 03 6f 6e 65
         *   ArrayList<String> ["one"]              -> 41 01 57 00 03 6f 6e 65
         *   Empty HashMap                          -> 43 00
         *   Non-empty LinkedHashMap                -> 2c ac ed 00 05 ...
         *   byte[] {1,2,3} via DataSerializer      -> 2e 03 01 02 03
         *   int[] {1,42,-7}                         -> 30 03 00000001 0000002a fffffff9
         *   Boolean.TRUE                           -> 35 01
         *   Character 'A'                          -> 36 00 41
         *   Byte 7                                 -> 37 07
         *   Short 7                                -> 38 00 07
         *   Integer 101                            -> 39 00 00 00 65
         *   Long 101L                              -> 3a 00 00 00 00 00 00 00 65
         *   Float 7.25f                            -> 3b 40 e8 00 00
         *   Double 7.25d                           -> 3c 40 1d 00 00 00 00 00 00
         *   Date(1000L)                            -> 3d 00 00 00 00 00 00 03 e8
         *
         * can be incorrectly decoded as text or raw binary.
         */
        String[] stringArrayValue = ValueDecoding.decodeStringArrayValue(valuePayload);

        if (stringArrayValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-string-array",
                    "key", key,
                    "valueType", "STRING_ARRAY",
                    "txId", txId
            ));
            return StoredValue.stringArrayValue(stringArrayValue);
        }

        ArrayList<String> stringArrayListValue = ValueDecoding.decodeStringArrayListValue(valuePayload);

        if (stringArrayListValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-string-array-list",
                    "key", key,
                    "valueType", "STRING_ARRAY_LIST",
                    "txId", txId
            ));
            return StoredValue.stringArrayListValue(stringArrayListValue);
        }

        ValueDecoding.ObjectArrayList objectArrayListValue =
                ValueDecoding.decodeObjectArrayListValue(valuePayload);

        if (objectArrayListValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-object-array-list",
                    "key", key,
                    "valueType", "OBJECT_ARRAY_LIST",
                    "txId", txId
            ));
            return StoredValue.objectArrayListValue(objectArrayListValue.encodedValue());
        }

        LinkedHashMap<String, String> stringHashMapValue = ValueDecoding.decodeStringHashMapValue(valuePayload);

        if (stringHashMapValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-string-hash-map",
                    "key", key,
                    "valueType", "STRING_HASH_MAP",
                    "txId", txId
            ));
            return StoredValue.stringHashMapValue(stringHashMapValue);
        }

        LinkedHashMap<String, Object> stringObjectHashMapValue = ValueDecoding.decodeStringObjectHashMapValue(valuePayload);

        if (stringObjectHashMapValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-string-object-hash-map",
                    "key", key,
                    "valueType", "STRING_OBJECT_HASH_MAP",
                    "txId", txId
            ));
            return StoredValue.stringObjectHashMapValue(stringObjectHashMapValue);
        }

        ValueDecoding.ObjectArray objectArrayValue =
                ValueDecoding.decodeObjectArrayValue(valuePayload);

        if (objectArrayValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-object-array",
                    "key", key,
                    "valueType", "OBJECT_ARRAY",
                    "txId", txId
            ));
            return StoredValue.objectArrayValue(objectArrayValue.encodedValue());
        }

        ValueDecoding.JavaSerializedObject javaSerializedObjectValue =
                ValueDecoding.decodeJavaSerializedObjectValue(valuePayload);

        if (javaSerializedObjectValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-java-serialized-object",
                    "key", key,
                    "type", javaSerializedObjectValue.className(),
                    "valueType", "JAVA_SERIALIZED_OBJECT",
                    "txId", txId
            ));
            return StoredValue.javaSerializedObjectValue(
                    javaSerializedObjectValue.className(),
                    javaSerializedObjectValue.serializedValue()
            );
        }

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

        int[] intArrayValue = ValueDecoding.decodeIntArrayValue(valuePayload);

        if (intArrayValue != null) {
            log.info(StructuredLog.event(
                    "handler_put_all_value_decode_ok",
                    "encoding", "geode-int-array",
                    "key", key,
                    "valueType", "INT_ARRAY",
                    "txId", txId
            ));
            return StoredValue.intArrayValue(intArrayValue);
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

            if (rawValue instanceof String[] stringArrayObject) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "STRING_ARRAY",
                        "txId", txId
                ));
                return StoredValue.stringArrayValue(stringArrayObject);
            }

            if (rawValue instanceof ArrayList<?> arrayListObject && isStringArrayList(arrayListObject)) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "STRING_ARRAY_LIST",
                        "txId", txId
                ));
                return StoredValue.stringArrayListValue(toStringArrayList(arrayListObject));
            }

            if (rawValue instanceof Map<?, ?> mapObject && isStringStringMap(mapObject)) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "STRING_HASH_MAP",
                        "txId", txId
                ));
                return StoredValue.stringHashMapValue(toStringStringLinkedHashMap(mapObject));
            }

            if (rawValue instanceof Map<?, ?> mapObject && isSupportedStringObjectMap(mapObject)) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "STRING_OBJECT_HASH_MAP",
                        "txId", txId
                ));
                return StoredValue.stringObjectHashMapValue(toStringObjectLinkedHashMap(mapObject));
            }

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

            if (rawValue instanceof int[] intArrayObject) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "type", rawValue.getClass().getName(),
                        "valueType", "INT_ARRAY",
                        "txId", txId
                ));
                return StoredValue.intArrayValue(intArrayObject);
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

            ValueDecoding.ObjectArrayList fallbackObjectArrayListValue =
                    ValueDecoding.decodeObjectArrayListValue(valuePayload);

            if (fallbackObjectArrayListValue != null) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "encoding", "geode-dataserializer-object-array-list",
                        "valueType", "OBJECT_ARRAY_LIST",
                        "txId", txId
                ));
                return StoredValue.objectArrayListValue(fallbackObjectArrayListValue.encodedValue());
            }

            ValueDecoding.ObjectArray fallbackObjectArrayValue =
                    ValueDecoding.decodeObjectArrayValue(valuePayload);

            if (fallbackObjectArrayValue != null) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "encoding", "geode-dataserializer-object-array",
                        "valueType", "OBJECT_ARRAY",
                        "txId", txId
                ));
                return StoredValue.objectArrayValue(fallbackObjectArrayValue.encodedValue());
            }

            ValueDecoding.JavaSerializedObject fallbackJavaSerializedObjectValue =
                    ValueDecoding.decodeJavaSerializedObjectValue(valuePayload);

            if (fallbackJavaSerializedObjectValue != null) {
                log.info(StructuredLog.event(
                        "handler_put_all_value_deserialize_ok",
                        "key", key,
                        "encoding", "geode-dataserializer-java-serialized-object",
                        "type", fallbackJavaSerializedObjectValue.className(),
                        "valueType", "JAVA_SERIALIZED_OBJECT",
                        "txId", txId
                ));
                return StoredValue.javaSerializedObjectValue(
                        fallbackJavaSerializedObjectValue.className(),
                        fallbackJavaSerializedObjectValue.serializedValue()
                );
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