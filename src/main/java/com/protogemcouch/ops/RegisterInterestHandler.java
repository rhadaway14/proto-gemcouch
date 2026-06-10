package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.subscription.SubscriptionRegistry;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Handles REGISTER_INTEREST (20) and REGISTER_INTEREST_LIST (24) on the subscription-control
 * connection. Records interest in the region (part[0]) so the event feed pushes its mutations, and
 * replies according to the InterestResultPolicy: KEYS_VALUES returns the region's current snapshot as
 * a VersionedObjectList (the initial image / GII); NONE/KEYS return the empty chunked ack.
 *
 * <p>The policy travels as a 3-byte part {@code 01 25 <ordinal>} (NONE=0, KEYS=1, KEYS_VALUES=2).
 */
public class RegisterInterestHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(RegisterInterestHandler.class);

    private static final int POLICY_KEYS_VALUES = 2;

    private final Repository repository;
    private final SubscriptionRegistry subscriptions;

    public RegisterInterestHandler(Repository repository, SubscriptionRegistry subscriptions) {
        this.repository = repository;
        this.subscriptions = subscriptions;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().isEmpty()
                ? ""
                : ByteUtils.bytesToString(frame.getParts().get(0).getPayload());
        int policy = interestResultPolicy(frame);

        subscriptions.registerInterest(SubscriptionRegistry.clientId(ctx), region);

        byte[] reply;
        if (policy == POLICY_KEYS_VALUES) {
            List<String> keys = repository.keySet(region);
            Map<String, StoredValue> values = repository.getAll(region, keys);
            reply = GemResponseWriter.buildRegisterInterestKeysValuesReply(keys, values);
            log.info(StructuredLog.event(
                    "handler_register_interest", "region", region, "policy", "KEYS_VALUES",
                    "keys", keys.size(), "feeds", subscriptions.feedCount(), "txId", frame.getTransactionId()));
        } else {
            reply = GemResponseWriter.buildRegisterInterestReply();
            log.info(StructuredLog.event(
                    "handler_register_interest", "region", region, "policy", policy,
                    "feeds", subscriptions.feedCount(), "txId", frame.getTransactionId()));
        }

        ctx.writeAndFlush(Unpooled.wrappedBuffer(reply));
    }

    /** The InterestResultPolicy ordinal from the {@code 01 25 <ordinal>} request part, or -1. */
    private static int interestResultPolicy(GemFrame frame) {
        for (GemPart part : frame.getParts()) {
            byte[] d = part.getPayload();
            if (d != null && d.length == 3 && (d[0] & 0xff) == 0x01 && (d[1] & 0xff) == 0x25) {
                return d[2] & 0xff;
            }
        }
        return -1;
    }
}
