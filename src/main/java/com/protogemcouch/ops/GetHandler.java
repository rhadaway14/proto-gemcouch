package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.util.DocumentKeyUtil;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(GetHandler.class);

    private final Repository repository;

    public GetHandler(Repository repository) {
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

        String docId = DocumentKeyUtil.docId(region, key);

        log.info(StructuredLog.event(
                "handler_get",
                "region", region,
                "key", key,
                "docId", docId,
                "txId", frame.getTransactionId()
        ));

        StoredValue value = repository.get(docId);

        byte[] response;

        if (value == null) {
            response = GemResponseWriter.buildNullGetResponse(frame.getTransactionId());
        } else if (value.type() == StoredValue.Type.BOOLEAN) {
            response = GemResponseWriter.buildBooleanGetResponse(
                    frame.getTransactionId(),
                    value.asBoolean()
            );
        } else if (value.type() == StoredValue.Type.CHARACTER) {
            response = GemResponseWriter.buildCharacterGetResponse(
                    frame.getTransactionId(),
                    value.asCharacter()
            );
        } else if (value.type() == StoredValue.Type.BYTE) {
            response = GemResponseWriter.buildByteGetResponse(
                    frame.getTransactionId(),
                    value.asByte()
            );
        } else if (value.type() == StoredValue.Type.BYTE_ARRAY) {
            response = GemResponseWriter.buildByteArrayGetResponse(
                    frame.getTransactionId(),
                    value.asByteArray()
            );
        } else if (value.type() == StoredValue.Type.BOOLEAN_ARRAY) {
            response = GemResponseWriter.buildBooleanArrayGetResponse(
                    frame.getTransactionId(),
                    value.asBooleanArray()
            );
        } else if (value.type() == StoredValue.Type.CHAR_ARRAY) {
            response = GemResponseWriter.buildCharArrayGetResponse(
                    frame.getTransactionId(),
                    value.asCharArray()
            );
        } else if (value.type() == StoredValue.Type.SHORT_ARRAY) {
            response = GemResponseWriter.buildShortArrayGetResponse(
                    frame.getTransactionId(),
                    value.asShortArray()
            );
        } else if (value.type() == StoredValue.Type.INT_ARRAY) {
            response = GemResponseWriter.buildIntArrayGetResponse(
                    frame.getTransactionId(),
                    value.asIntArray()
            );
        } else if (value.type() == StoredValue.Type.LONG_ARRAY) {
            response = GemResponseWriter.buildLongArrayGetResponse(
                    frame.getTransactionId(),
                    value.asLongArray()
            );
        } else if (value.type() == StoredValue.Type.FLOAT_ARRAY) {
            response = GemResponseWriter.buildFloatArrayGetResponse(
                    frame.getTransactionId(),
                    value.asFloatArray()
            );
        } else if (value.type() == StoredValue.Type.DOUBLE_ARRAY) {
            response = GemResponseWriter.buildDoubleArrayGetResponse(
                    frame.getTransactionId(),
                    value.asDoubleArray()
            );
        } else if (value.type() == StoredValue.Type.STRING_ARRAY) {
            response = GemResponseWriter.buildStringArrayGetResponse(
                    frame.getTransactionId(),
                    value.asStringArray()
            );
        } else if (value.type() == StoredValue.Type.STRING_ARRAY_LIST) {
            response = GemResponseWriter.buildStringArrayListGetResponse(
                    frame.getTransactionId(),
                    value.asStringArrayList()
            );
        } else if (value.type() == StoredValue.Type.STRING_HASH_MAP) {
            response = GemResponseWriter.buildStringHashMapGetResponse(
                    frame.getTransactionId(),
                    value.asStringHashMap()
            );
        } else if (value.type() == StoredValue.Type.STRING_OBJECT_HASH_MAP) {
            response = GemResponseWriter.buildStringObjectHashMapGetResponse(
                    frame.getTransactionId(),
                    value.asStringObjectHashMap()
            );
        } else if (value.type() == StoredValue.Type.JAVA_SERIALIZED_OBJECT) {
            response = GemResponseWriter.buildJavaSerializedObjectGetResponse(
                    frame.getTransactionId(),
                    value.asJavaSerializedValue()
            );
        } else if (value.type() == StoredValue.Type.OBJECT_ARRAY) {
            response = GemResponseWriter.buildObjectArrayGetResponse(
                    frame.getTransactionId(),
                    value.asObjectArrayValue()
            );
        } else if (value.type() == StoredValue.Type.OBJECT_ARRAY_LIST) {
            response = GemResponseWriter.buildObjectArrayListGetResponse(
                    frame.getTransactionId(),
                    value.asObjectArrayListValue()
            );
        } else if (value.type() == StoredValue.Type.OPAQUE_GEODE_VALUE) {
            response = GemResponseWriter.buildOpaqueGeodeValueGetResponse(
                    frame.getTransactionId(),
                    value.asOpaqueGeodeValue()
            );
        } else if (value.type() == StoredValue.Type.PDX_INSTANCE) {
            response = GemResponseWriter.buildPdxInstanceGetResponse(
                    frame.getTransactionId(),
                    value.asPdxInstanceValue()
            );
        } else if (value.type() == StoredValue.Type.SHORT) {
            response = GemResponseWriter.buildShortGetResponse(
                    frame.getTransactionId(),
                    value.asShort()
            );
        } else if (value.type() == StoredValue.Type.INTEGER) {
            response = GemResponseWriter.buildIntegerGetResponse(
                    frame.getTransactionId(),
                    value.asInteger()
            );
        } else if (value.type() == StoredValue.Type.LONG) {
            response = GemResponseWriter.buildLongGetResponse(
                    frame.getTransactionId(),
                    value.asLong()
            );
        } else if (value.type() == StoredValue.Type.FLOAT) {
            response = GemResponseWriter.buildFloatGetResponse(
                    frame.getTransactionId(),
                    value.asFloat()
            );
        } else if (value.type() == StoredValue.Type.DOUBLE) {
            response = GemResponseWriter.buildDoubleGetResponse(
                    frame.getTransactionId(),
                    value.asDouble()
            );
        } else if (value.type() == StoredValue.Type.DATE) {
            response = GemResponseWriter.buildDateGetResponse(
                    frame.getTransactionId(),
                    value.asDate()
            );
        } else {
            response = GemResponseWriter.buildGetResponse(
                    frame.getTransactionId(),
                    value.value()
            );
        }

        ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
    }
}