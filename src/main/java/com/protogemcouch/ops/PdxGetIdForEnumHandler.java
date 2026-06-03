package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PdxGetIdForEnumHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(PdxGetIdForEnumHandler.class);

    private final PdxEnumRegistry pdxEnumRegistry;

    public PdxGetIdForEnumHandler(PdxEnumRegistry pdxEnumRegistry) {
        if (pdxEnumRegistry == null) {
            throw new IllegalArgumentException("pdxEnumRegistry must not be null");
        }

        this.pdxEnumRegistry = pdxEnumRegistry;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        if (frame.getParts().isEmpty()) {
            throw new IllegalArgumentException("GET_PDX_ID_FOR_ENUM requires one enum part");
        }

        GemPart enumPart = frame.getParts().get(0);
        byte[] encodedEnumInfo = enumPart.getPayload();

        if (encodedEnumInfo == null || encodedEnumInfo.length == 0) {
            throw new IllegalArgumentException("GET_PDX_ID_FOR_ENUM enum payload is empty");
        }

        int enumId = pdxEnumRegistry.getOrCreateEnumId(encodedEnumInfo);

        log.info(StructuredLog.event(
                "handler_pdx_get_id_for_enum_ok",
                "txId", frame.getTransactionId(),
                "payloadLength", encodedEnumInfo.length,
                "enumId", enumId,
                "registrySize", pdxEnumRegistry.size()
        ));

        byte[] response = GemResponseWriter.buildPdxTypeIdResponse(
                frame.getTransactionId(),
                enumId
        );

        ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
    }
}