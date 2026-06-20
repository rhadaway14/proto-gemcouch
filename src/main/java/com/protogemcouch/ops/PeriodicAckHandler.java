package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles PERIODIC_ACK (52): a subscription/CQ client periodically acks the events it has received so
 * the server can trim its subscription queue. It is fire-and-forget (no reply), drained here so it does
 * not fall through to the unknown-opcode path (which would log a "UNKNOWN FRAME TYPE" regression
 * marker). The ack also keeps the feed's connection non-idle.
 *
 * <p>The shim queues events only for a <em>disconnected</em> durable client (see
 * {@code SubscriptionRegistry}); while a client is connected its events are delivered live rather than
 * queued, and the disconnected queue is replayed wholesale on reconnect — so there is no live queue for
 * an ack to trim, and draining is the correct behavior.
 */
public class PeriodicAckHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(PeriodicAckHandler.class);

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        log.debug(StructuredLog.event(
                "handler_periodic_ack", "parts", frame.getNumberOfParts(), "txId", frame.getTransactionId()));
    }
}
