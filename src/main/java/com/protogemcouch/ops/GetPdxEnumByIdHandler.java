package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles GET_PDX_ENUM_BY_ID (98): the reverse enum lookup — the enum-side counterpart of
 * GET_PDX_TYPE_BY_ID. A client that reads a PDX enum value it did not write sends part[0] = the int
 * enum id and expects the serialized {@code EnumInfo} back so it can decode the value. The shim serves
 * the exact bytes the writing client registered (GET_PDX_ID_FOR_ENUM).
 */
public class GetPdxEnumByIdHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(GetPdxEnumByIdHandler.class);

    private final PdxEnumRegistry pdxEnumRegistry;

    public GetPdxEnumByIdHandler(PdxEnumRegistry pdxEnumRegistry) {
        if (pdxEnumRegistry == null) {
            throw new IllegalArgumentException("pdxEnumRegistry must not be null");
        }
        this.pdxEnumRegistry = pdxEnumRegistry;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        if (frame.getParts().isEmpty()) {
            throw new IllegalArgumentException("GET_PDX_ENUM_BY_ID requires one int enum-id part");
        }
        int enumId = ByteUtils.bytesToInt(frame.getParts().get(0).getPayload());
        byte[] serialized = pdxEnumRegistry.serializedEnum(enumId);

        log.info(StructuredLog.event(
                "handler_pdx_get_enum_by_id",
                "txId", frame.getTransactionId(), "enumId", enumId, "found", serialized != null));

        // The enum is always registered before any value referencing it is stored, so a miss is not
        // expected; fall back to an empty object part rather than risk an NPE in the response builder.
        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildPdxEnumByIdResponse(
                frame.getTransactionId(), serialized == null ? new byte[0] : serialized)));
    }
}
