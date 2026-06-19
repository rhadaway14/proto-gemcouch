package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.subscription.Interest;
import com.protogemcouch.subscription.SubscriptionRegistry;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.GemResponseWriter;
import com.protogemcouch.wire.MessageTypes;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/**
 * Handles REGISTER_INTEREST (20) and REGISTER_INTEREST_LIST (24) on the subscription-control
 * connection. Parses the interest specification so the feed pushes only matching keys (per-key
 * filtering), and replies per the InterestResultPolicy: KEYS_VALUES returns the region's current
 * snapshot as a VersionedObjectList (the GII); NONE/KEYS return the empty chunked ack.
 *
 * <p>Request layouts (captured from a real Geode 1.15 client):
 * <ul>
 *   <li><b>REGISTER_INTEREST (20)</b>, 7 parts: part[0]=region, part[1]=interestType int
 *       ({@value #INTEREST_KEY}=KEY, {@value #INTEREST_REGEX}=regex), part[2]=policy
 *       ({@code 01 25 <ord>}), part[3]=durable byte, <b>part[4]=the key or regex as a raw string</b>,
 *       part[5..6]=trailing flags. The sentinel key {@code "ALL_KEYS"} (and regex {@code ".*"}) means
 *       all keys.</li>
 *   <li><b>REGISTER_INTEREST_LIST (24)</b>, 6 parts: part[0]=region, part[1]=policy, part[2]=durable,
 *       <b>part[3]=a Java-serialized {@code List} of keys</b> (DSCODE {@code 0x2c} + serialized),
 *       part[4..5]=trailing flags.</li>
 * </ul>
 */
public class RegisterInterestHandler implements OperationHandler {

    private static final Logger log = LoggerFactory.getLogger(RegisterInterestHandler.class);

    private static final int POLICY_KEYS_VALUES = 2;
    private static final int INTEREST_KEY = 0;
    private static final int INTEREST_REGEX = 1;
    private static final String ALL_KEYS = "ALL_KEYS";
    private static final int JAVA_SERIALIZED = 0x2c; // Geode DSCODE for a Java-serialized object

    private final Repository repository;
    private final SubscriptionRegistry subscriptions;

    public RegisterInterestHandler(Repository repository, SubscriptionRegistry subscriptions) {
        this.repository = repository;
        this.subscriptions = subscriptions;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        List<GemPart> parts = frame.getParts();
        String region = parts.isEmpty() ? "" : ByteUtils.bytesToString(parts.get(0).getPayload());
        int policy = interestResultPolicy(frame);

        Interest interest = parseInterest(frame, parts);
        subscriptions.registerInterest(SubscriptionRegistry.clientId(ctx), region, interest);

        byte[] reply;
        if (policy == POLICY_KEYS_VALUES) {
            List<String> keys = repository.keySet(region);
            Map<String, StoredValue> values = repository.getAll(region, keys);
            reply = GemResponseWriter.buildRegisterInterestKeysValuesReply(keys, values);
            log.info(StructuredLog.event(
                    "handler_register_interest", "region", region, "policy", "KEYS_VALUES",
                    "interest", interest.getClass().getSimpleName(), "keys", keys.size(),
                    "feeds", subscriptions.feedCount(), "txId", frame.getTransactionId()));
        } else {
            reply = GemResponseWriter.buildRegisterInterestReply();
            log.info(StructuredLog.event(
                    "handler_register_interest", "region", region, "policy", policy,
                    "interest", interest.getClass().getSimpleName(),
                    "feeds", subscriptions.feedCount(), "txId", frame.getTransactionId()));
        }

        ctx.writeAndFlush(Unpooled.wrappedBuffer(reply));
    }

    /** Parse the registered interest (all-keys / specific key / key-list / regex) from the request. */
    private static Interest parseInterest(GemFrame frame, List<GemPart> parts) {
        if (frame.getMessageType() == MessageTypes.REGISTER_INTEREST_LIST) {
            Set<String> keys = parseKeyList(parts);
            return keys.isEmpty() ? Interest.allKeys() : Interest.keys(keys);
        }
        // REGISTER_INTEREST (20): interestType int at part[1], key/regex raw string at part[4].
        int interestType = parts.size() > 1 ? readInt(parts.get(1).getPayload()) : INTEREST_KEY;
        String spec = parts.size() > 4 ? ByteUtils.bytesToString(parts.get(4).getPayload()) : "";

        if (interestType == INTEREST_REGEX) {
            if (spec.isEmpty() || ".*".equals(spec)) {
                return Interest.allKeys();
            }
            try {
                return Interest.regex(spec);
            } catch (PatternSyntaxException e) {
                log.warn(StructuredLog.event("register_interest_bad_regex", "regex", spec, "error", e.getMessage()));
                return Interest.allKeys();
            }
        }
        // KEY interest: the "ALL_KEYS" sentinel (or an empty/unparsed key) means the whole region.
        if (spec.isEmpty() || ALL_KEYS.equals(spec)) {
            return Interest.allKeys();
        }
        return Interest.keys(Set.of(spec));
    }

    /** Deserialize the Java-serialized key List in a REGISTER_INTEREST_LIST request (the 0x2c part). */
    private static Set<String> parseKeyList(List<GemPart> parts) {
        for (GemPart part : parts) {
            byte[] d = part.getPayload();
            if (d == null || d.length <= 1 || (d[0] & 0xff) != JAVA_SERIALIZED) {
                continue;
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(d, 1, d.length - 1))) {
                Object o = in.readObject();
                if (o instanceof Collection<?> collection) {
                    Set<String> keys = new HashSet<>();
                    for (Object element : collection) {
                        if (element != null) {
                            keys.add(element.toString());
                        }
                    }
                    return keys;
                }
            } catch (Exception e) {
                log.warn(StructuredLog.event("register_interest_bad_key_list", "error", e.getMessage()));
            }
        }
        return Set.of();
    }

    private static int readInt(byte[] d) {
        if (d == null || d.length < 4) {
            return INTEREST_KEY;
        }
        return ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
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
