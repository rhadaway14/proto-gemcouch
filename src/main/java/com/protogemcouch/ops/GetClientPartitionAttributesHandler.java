package com.protogemcouch.ops;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code GET_CLIENT_PARTITION_ATTRIBUTES} (opcode 73), the client's single-hop
 * partition-metadata probe. Single-hop is a <em>multi-server</em> optimization — it lets a client
 * route a key directly to the server hosting its primary bucket. The shim is a single logical server
 * backed by one Couchbase store with no bucket topology, so there is no second hop to avoid: every
 * operation already reaches the one server in a single hop.
 *
 * <p>This is therefore an intentional, documented <strong>graceful no-op</strong>: the shim sends no
 * partition metadata. A real Geode client treats the absence of a response as "no single-hop
 * available" and falls back to ordinary direct routing, which is correct here. (Empirically, a client
 * against a single-server endpoint typically does not even send this probe.) Not replying is validated
 * by the full integration suite — every region operation works with single-hop enabled. We do not
 * fabricate a partition-attributes reply: with no real benefit to offer, a hand-rolled response would
 * only risk mis-parsing on the client.
 */
public class GetClientPartitionAttributesHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(GetClientPartitionAttributesHandler.class);

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        // Intentional no-op: the region is not partitioned (single-backend shim); the client falls
        // back to direct routing. Logged at debug to avoid noise on the hot path.
        log.debug(StructuredLog.event(
                "handler_get_client_partition_attributes_noop",
                "region", region,
                "reason", "single_backend_not_partitioned",
                "txId", frame.getTransactionId()));
    }
}
