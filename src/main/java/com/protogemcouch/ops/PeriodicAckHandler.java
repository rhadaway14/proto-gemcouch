package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles PERIODIC_ACK (52): a subscription/CQ client periodically acks the events it has received so
 * the server can trim its subscription queue. The shim keeps no durable queue, so this is a no-op
 * drain — it is fire-and-forget (no reply), but it must be handled here so it does not fall through to
 * the unknown-opcode path (which would log a "UNKNOWN FRAME TYPE" regression marker).
 */
public class PeriodicAckHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(PeriodicAckHandler.class);

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        log.debug(StructuredLog.event(
                "handler_periodic_ack", "parts", frame.getNumberOfParts(), "txId", frame.getTransactionId()));
    }
}
