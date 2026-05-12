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