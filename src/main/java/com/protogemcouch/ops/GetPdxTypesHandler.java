package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles GET_PDX_TYPES (101): bulk PDX type registry discovery. A client syncing its registry asks
 * for every registered type at once; the shim replies with a {@code Map<Integer, PdxType>} of all the
 * types it has kept (registered via GET_PDX_ID_FOR_TYPE). Complements the per-type forward
 * (GET_PDX_ID_FOR_TYPE) and reverse (GET_PDX_TYPE_BY_ID) lookups.
 */
public class GetPdxTypesHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(GetPdxTypesHandler.class);

    private final PdxTypeRegistry pdxTypeRegistry;

    public GetPdxTypesHandler(PdxTypeRegistry pdxTypeRegistry) {
        if (pdxTypeRegistry == null) {
            throw new IllegalArgumentException("pdxTypeRegistry must not be null");
        }
        this.pdxTypeRegistry = pdxTypeRegistry;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        java.util.Map<Integer, byte[]> types = pdxTypeRegistry.allSerializedTypes();
        log.info(StructuredLog.event(
                "handler_pdx_get_types", "txId", frame.getTransactionId(), "count", types.size()));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildPdxRegistryMapResponse(frame.getTransactionId(), types)));
    }
}
