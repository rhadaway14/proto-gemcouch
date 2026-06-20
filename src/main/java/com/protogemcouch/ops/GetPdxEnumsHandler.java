package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles GET_PDX_ENUMS (102): bulk PDX enum registry discovery. Mirrors {@link GetPdxTypesHandler}
 * for enums — the shim replies with a {@code Map<Integer, EnumInfo>} of every enum it has kept
 * (registered via GET_PDX_ID_FOR_ENUM), serving back the exact EnumInfo bytes each client registered.
 */
public class GetPdxEnumsHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(GetPdxEnumsHandler.class);

    private final PdxEnumRegistry pdxEnumRegistry;

    public GetPdxEnumsHandler(PdxEnumRegistry pdxEnumRegistry) {
        if (pdxEnumRegistry == null) {
            throw new IllegalArgumentException("pdxEnumRegistry must not be null");
        }
        this.pdxEnumRegistry = pdxEnumRegistry;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        java.util.Map<Integer, byte[]> enums = pdxEnumRegistry.allSerializedEnums();
        log.info(StructuredLog.event(
                "handler_pdx_get_enums", "txId", frame.getTransactionId(), "count", enums.size()));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildPdxRegistryMapResponse(frame.getTransactionId(), enums)));
    }
}
