package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PdxGetIdForTypeHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(PdxGetIdForTypeHandler.class);

    private final PdxTypeRegistry pdxTypeRegistry;

    public PdxGetIdForTypeHandler(PdxTypeRegistry pdxTypeRegistry) {
        if (pdxTypeRegistry == null) {
            throw new IllegalArgumentException("pdxTypeRegistry must not be null");
        }

        this.pdxTypeRegistry = pdxTypeRegistry;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        if (frame.getParts().isEmpty()) {
            throw new IllegalArgumentException("GET_PDX_ID_FOR_TYPE requires one PdxType part");
        }

        GemPart pdxTypePart = frame.getParts().get(0);
        byte[] encodedPdxType = pdxTypePart.getPayload();

        if (encodedPdxType == null || encodedPdxType.length == 0) {
            throw new IllegalArgumentException("GET_PDX_ID_FOR_TYPE PdxType payload is empty");
        }

        int typeId = pdxTypeRegistry.getOrCreateTypeId(encodedPdxType);

        log.info(StructuredLog.event(
                "handler_pdx_get_id_for_type_ok",
                "txId", frame.getTransactionId(),
                "payloadLength", encodedPdxType.length,
                "typeId", typeId,
                "registrySize", pdxTypeRegistry.size()
        ));

        byte[] response = GemResponseWriter.buildPdxTypeIdResponse(
                frame.getTransactionId(),
                typeId
        );

        ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
    }
}