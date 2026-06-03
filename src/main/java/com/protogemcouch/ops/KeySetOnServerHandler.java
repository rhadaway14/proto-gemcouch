package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.couchbase.RepositoryException;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class KeySetOnServerHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(KeySetOnServerHandler.class);

    private final Repository repository;

    public KeySetOnServerHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        try {
            List<String> keys = repository.keySet(region);

            log.info(StructuredLog.event(
                    "handler_key_set",
                    "region", region,
                    "count", keys.size(),
                    "keys", keys,
                    "txId", frame.getTransactionId()
            ));

            byte[] responseBytes = GemResponseWriter.buildKeySetChunkedResponse(
                    frame.getTransactionId(),
                    keys
            );

            log.info(StructuredLog.event(
                    "handler_key_set_response_built",
                    "region", region,
                    "key_count", keys.size(),
                    "response_bytes", responseBytes.length,
                    "txId", frame.getTransactionId()
            ));

            ChannelFuture writeFuture = ctx.writeAndFlush(Unpooled.wrappedBuffer(responseBytes));

            /*
             * In production Netty runtime, writeAndFlush returns a ChannelFuture.
             * In unit tests with an unstubbed Mockito ChannelHandlerContext, it may
             * return null. Do not treat that as a handler failure.
             */
            if (writeFuture == null) {
                log.debug(StructuredLog.event(
                        "handler_key_set_response_write_future_null",
                        "region", region,
                        "key_count", keys.size(),
                        "response_bytes", responseBytes.length,
                        "txId", frame.getTransactionId()
                ));
                return;
            }

            writeFuture.addListener(future -> {
                if (future.isSuccess()) {
                    log.info(StructuredLog.event(
                            "handler_key_set_response_written",
                            "region", region,
                            "key_count", keys.size(),
                            "response_bytes", responseBytes.length,
                            "txId", frame.getTransactionId()
                    ));
                } else {
                    log.warn(StructuredLog.event(
                            "handler_key_set_response_write_failed",
                            "region", region,
                            "key_count", keys.size(),
                            "response_bytes", responseBytes.length,
                            "error", future.cause() == null ? "unknown" : future.cause().getMessage(),
                            "txId", frame.getTransactionId()
                    ), future.cause());
                }
            });
        } catch (RepositoryException e) {
            // Infrastructure failure (e.g. Couchbase unavailable): propagate so the dispatch loop
            // records it as an operation error and the outage is visible, rather than masking it.
            throw e;
        } catch (Throwable t) {
            log.error(StructuredLog.event(
                    "handler_key_set_unhandled_error",
                    "region", region,
                    "error_type", t.getClass().getName(),
                    "error", t.getMessage(),
                    "txId", frame.getTransactionId()
            ), t);

            ctx.close();
        }
    }
}