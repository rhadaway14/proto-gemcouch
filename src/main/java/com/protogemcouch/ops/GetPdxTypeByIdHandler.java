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
 * Handles GET_PDX_TYPE_BY_ID (92): the reverse of type registration. A client that reads a PDX value
 * it did not write (e.g. a CQ/subscription event value pushed from another client, or a get of
 * another client's entry) sends part[0] = the int type id and expects the serialized {@code PdxType}
 * back so it can decode the instance's fields. The shim serves the type it kept when the writing
 * client registered it (GET_PDX_ID_FOR_TYPE), letting a second client deserialize PDX it did not write.
 */
public class GetPdxTypeByIdHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(GetPdxTypeByIdHandler.class);

    private final PdxTypeRegistry pdxTypeRegistry;

    public GetPdxTypeByIdHandler(PdxTypeRegistry pdxTypeRegistry) {
        if (pdxTypeRegistry == null) {
            throw new IllegalArgumentException("pdxTypeRegistry must not be null");
        }
        this.pdxTypeRegistry = pdxTypeRegistry;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        if (frame.getParts().isEmpty()) {
            throw new IllegalArgumentException("GET_PDX_TYPE_BY_ID requires one int type-id part");
        }
        int typeId = ByteUtils.bytesToInt(frame.getParts().get(0).getPayload());
        byte[] serialized = pdxTypeRegistry.serializedPdxType(typeId);

        log.info(StructuredLog.event(
                "handler_pdx_get_type_by_id",
                "txId", frame.getTransactionId(),
                "typeId", typeId,
                "found", serialized != null));

        // The type is always registered before any instance referencing it is stored, so a miss is not
        // expected; fall back to an empty object part rather than risk an NPE in the response builder.
        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildPdxTypeByIdResponse(
                frame.getTransactionId(), serialized == null ? new byte[0] : serialized)));
    }
}
