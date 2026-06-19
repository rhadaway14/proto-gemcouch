package com.protogemcouch.subscription;

import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.query.OqlQuery;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemResponseWriter;
import com.protogemcouch.wire.MessageTypes;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.apache.geode.DataSerializer;
import org.apache.geode.internal.cache.EventID;
import org.apache.geode.internal.cache.versions.VMVersionTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks client subscriptions for the server&rarr;client event feed (register-interest) and pushes
 * mutation notifications down the feed channels. Holds the open feed channels (the
 * {@code PrimaryServerToClient}, mode-101 connections) and the set of regions any client registered
 * interest in.
 *
 * <p>P1 scope: interest is tracked per region (ALL_KEYS), not per client; events from a PUT/REMOVE on
 * this shim instance are pushed to every feed whose region is of interest, preceded once per feed by a
 * CLIENT_MARKER. Per-client interest filtering, create/update distinction, and cross-replica
 * propagation are later phases (see {@code docs/SUBSCRIPTIONS.md}). All state is in-memory and
 * single-instance.
 */
public final class SubscriptionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRegistry.class);

    private static final AttributeKey<Boolean> MARKER_SENT =
            AttributeKey.valueOf("protogemcouch.subscription.markerSent");

    /**
     * Stable client identity, set on every connection at handshake from the ClientProxyMembershipID
     * bytes (identical across a client's op/control/feed connections). Used to suppress echoing a
     * client's own mutations back to its own feed.
     */
    public static final AttributeKey<String> CLIENT_ID =
            AttributeKey.valueOf("protogemcouch.subscription.clientId");

    private final Set<Channel> feeds = ConcurrentHashMap.newKeySet();
    // Per-client interest: client id -> region -> the interests that client registered there (the union
    // of all-keys / specific keys / regex). A feed receives an event only when one of its client's
    // interests for that region matches the mutated key (see docs/SUBSCRIPTIONS.md).
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.ConcurrentMap<String, java.util.List<Interest>>> interests =
            new ConcurrentHashMap<>();

    /** A registered continuous query: the parsed OQL (its region path is the CQ's region). */
    public record Cq(String cqName, String region, OqlQuery query) {
    }

    // Per-client continuous queries: client id -> (cqName -> Cq).
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.ConcurrentMap<String, Cq>> cqs =
            new ConcurrentHashMap<>();

    // Cross-replica eventing. Each publish* both delivers to this replica's local feeds AND broadcasts a
    // RemoteEvent on the backplane; events the backplane echoes back from this replica are dropped by
    // instanceId. Default is no-op (single-instance) — see EventBackplane.
    private final String instanceId = java.util.UUID.randomUUID().toString();
    private volatile EventBackplane backplane = new NoOpEventBackplane();

    // A stable fabricated membership identity for events from this shim (used by the client only as an
    // opaque dedup/version key), plus monotonic counters for EventID sequence and entry/region versions.
    private final byte[] memberId = ByteUtils.hex("0a0000bc0000e29a");
    // Data events use thread 1 with a monotonic sequence; the CLIENT_MARKER uses a separate thread so
    // its EventID never shadows a data event in the client's (membershipId, threadId, seq) dedup.
    private static final long DATA_THREAD_ID = 1L;
    private static final long MARKER_THREAD_ID = 0L;
    private final AtomicLong sequence = new AtomicLong(0);
    private final AtomicLong markerSequence = new AtomicLong(0);
    private final AtomicInteger entryVersion = new AtomicInteger(0);
    private final AtomicLong regionVersion = new AtomicLong(0);

    // Resolves CQ-predicate fields against stored values. Defaults to map-typed resolution; the handler
    // factory swaps in a PDX-aware resolver so CQ predicates match PDX object fields exactly as the
    // QUERY path does (set once at startup, hence volatile rather than synchronized).
    private volatile OqlQuery.FieldResolver cqFieldResolver = OqlQuery.MAP_RESOLVER;

    /**
     * Installs the resolver CQ predicates use to read fields from stored values (e.g. a PDX-aware
     * resolver so {@code r.status = 'active'} matches PDX objects). Defaults to {@code MAP_RESOLVER}.
     */
    public void setCqFieldResolver(OqlQuery.FieldResolver resolver) {
        this.cqFieldResolver = resolver == null ? OqlQuery.MAP_RESOLVER : resolver;
    }

    /** This replica's stable id — the {@code originInstanceId} stamped on events it broadcasts. */
    public String instanceId() {
        return instanceId;
    }

    /**
     * Install the cross-replica backplane and start receiving remote events. Null restores the no-op
     * (single-instance) default. Called once at startup when multi-replica eventing is configured.
     */
    public void setBackplane(EventBackplane backplane) {
        this.backplane = (backplane == null) ? new NoOpEventBackplane() : backplane;
        this.backplane.subscribe(this::applyRemote);
    }

    /**
     * Apply an event broadcast by another replica to this replica's local feeds. Drops this replica's
     * own echoed events (already delivered locally when published) by {@code originInstanceId}, then
     * dispatches to the same local-delivery cores the local publish path uses (no re-broadcast).
     */
    void applyRemote(RemoteEvent event) {
        if (event == null || instanceId.equals(event.originInstanceId())) {
            return;
        }
        switch (event.kind()) {
            case WRITE -> deliverWrite(event.region(), event.key(), event.value(), event.update(),
                    event.originClientId());
            case DESTROY -> deliverDestroy(event.region(), event.key(), event.originClientId());
            case INVALIDATE -> deliverInvalidate(event.region(), event.key(), event.originClientId());
            case CQ_EVENT -> deliverCqEvent(event.region(), event.key(), event.value(), event.priorValue(),
                    event.originClientId());
            case CQ_DESTROY -> deliverCqDestroy(event.region(), event.key(), event.priorValue(),
                    event.originClientId());
        }
    }

    /** Null-safe origin client id for a request's channel (null when there is no channel, e.g. tests). */
    public static String clientId(io.netty.channel.ChannelHandlerContext ctx) {
        Channel channel = ctx == null ? null : ctx.channel();
        return channel == null ? null : channel.attr(CLIENT_ID).get();
    }

    public void addFeed(Channel channel) {
        feeds.add(channel);
        channel.closeFuture().addListener(f -> {
            feeds.remove(channel);
            // When a client's feed closes, drop its interests and CQs (a client has one feed).
            String clientId = channel.attr(CLIENT_ID).get();
            if (clientId != null) {
                interests.remove(clientId);
                cqs.remove(clientId);
            }
        });
    }

    /** Register a continuous query for a client (region is the query's FROM region path). */
    public void registerCq(String clientId, String cqName, OqlQuery query) {
        if (clientId == null || cqName == null || query == null) {
            return;
        }
        cqs.computeIfAbsent(clientId, k -> new ConcurrentHashMap<>())
                .put(cqName, new Cq(cqName, query.regionPath(), query));
    }

    /** Stop/close a continuous query (both just deregister it in this first cut). */
    public void closeCq(String clientId, String cqName) {
        if (clientId == null || cqName == null) {
            return;
        }
        java.util.concurrent.ConcurrentMap<String, Cq> clientCqs = cqs.get(clientId);
        if (clientCqs != null) {
            clientCqs.remove(cqName);
        }
    }

    /** Register all-keys interest in a region (backward-compatible default). */
    public void registerInterest(String clientId, String region) {
        registerInterest(clientId, region, Interest.allKeys());
    }

    /** Register a specific interest (all-keys / key-set / regex) for a client in a region. */
    public void registerInterest(String clientId, String region, Interest interest) {
        if (clientId == null || region == null || region.isBlank() || interest == null) {
            return;
        }
        interests.computeIfAbsent(clientId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(region, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(interest);
    }

    public void unregisterInterest(String clientId, String region) {
        if (clientId == null || region == null) {
            return;
        }
        java.util.concurrent.ConcurrentMap<String, java.util.List<Interest>> byRegion = interests.get(clientId);
        if (byRegion != null) {
            byRegion.remove(region); // region-level unregister (drops all interest in the region)
        }
    }

    /** True if the client has any interest registered in the region (gates event building). */
    private boolean isInterested(String clientId, String region) {
        java.util.List<Interest> list = interestsFor(clientId, region);
        return list != null && !list.isEmpty();
    }

    /** True if any of the client's interests in the region matches the mutated key (per-key filtering). */
    private boolean isInterestedInKey(String clientId, String region, String key) {
        java.util.List<Interest> list = interestsFor(clientId, region);
        if (list == null) {
            return false;
        }
        for (Interest interest : list) {
            if (interest.matches(key)) {
                return true;
            }
        }
        return false;
    }

    private java.util.List<Interest> interestsFor(String clientId, String region) {
        if (clientId == null || region == null) {
            return null;
        }
        java.util.concurrent.ConcurrentMap<String, java.util.List<Interest>> byRegion = interests.get(clientId);
        return byRegion == null ? null : byRegion.get(region);
    }

    /** True if any open feed's client has registered interest in this region. */
    public boolean hasInterest(String region) {
        if (region == null) {
            return false;
        }
        for (Channel feed : feeds) {
            if (isInterested(feed.attr(CLIENT_ID).get(), region)) {
                return true;
            }
        }
        return false;
    }

    public Set<Channel> feeds() {
        return feeds;
    }

    public int feedCount() {
        return feeds.size();
    }

    /**
     * Push a write notification for an interested region's key to every open feed:
     * {@code LOCAL_UPDATE} when the key already existed, otherwise {@code LOCAL_CREATE}.
     */
    public void publishWrite(String region, String key, StoredValue value, boolean update, String originClientId) {
        if (value == null) {
            return;
        }
        deliverWrite(region, key, value, update, originClientId);
        backplane.publish(new RemoteEvent(
                RemoteEvent.Kind.WRITE, region, key, value, null, update, originClientId, instanceId));
    }

    private void deliverWrite(String region, String key, StoredValue value, boolean update, String originClientId) {
        if (!hasInterest(region) || value == null) {
            return;
        }
        int messageType = update ? MessageTypes.LOCAL_UPDATE : MessageTypes.LOCAL_CREATE;
        byte[] message = GemResponseWriter.buildLocalWrite(
                messageType, region, key, value, nextVersionTag(), nextEventId());
        pushToFeeds(message, update ? "update" : "create", region, key, originClientId);
    }

    /** Push a destroy notification for an interested region's key to every open feed (except the origin). */
    public void publishDestroy(String region, String key, String originClientId) {
        deliverDestroy(region, key, originClientId);
        backplane.publish(new RemoteEvent(
                RemoteEvent.Kind.DESTROY, region, key, null, null, false, originClientId, instanceId));
    }

    private void deliverDestroy(String region, String key, String originClientId) {
        if (!hasInterest(region)) {
            return;
        }
        byte[] message = GemResponseWriter.buildLocalDestroy(region, key, nextVersionTag(), nextEventId());
        pushToFeeds(message, "destroy", region, key, originClientId);
    }

    /** Push an invalidate notification for an interested region's key to every open feed (except the origin). */
    public void publishInvalidate(String region, String key, String originClientId) {
        deliverInvalidate(region, key, originClientId);
        backplane.publish(new RemoteEvent(
                RemoteEvent.Kind.INVALIDATE, region, key, null, null, false, originClientId, instanceId));
    }

    private void deliverInvalidate(String region, String key, String originClientId) {
        if (!hasInterest(region)) {
            return;
        }
        byte[] message = GemResponseWriter.buildLocalInvalidate(region, key, nextVersionTag(), nextEventId());
        pushToFeeds(message, "invalidate", region, key, originClientId);
    }

    /**
     * Evaluate registered continuous queries against a write and push the appropriate CQ event to each
     * matching client's feed, using the entry's prior value to determine the CQ operation:
     * <ul>
     *   <li>new matches, prior did not (or absent) &rarr; CQ CREATE</li>
     *   <li>new matches, prior matched &rarr; CQ UPDATE</li>
     *   <li>new does not match, prior matched &rarr; CQ DESTROY (the entry left the result set)</li>
     *   <li>neither matches &rarr; no event</li>
     * </ul>
     * Independent of register-interest, and self-suppressed.
     */
    public void publishCqEvent(String region, String key, StoredValue value, StoredValue priorValue,
                               String originClientId) {
        if (value == null) {
            return;
        }
        deliverCqEvent(region, key, value, priorValue, originClientId);
        backplane.publish(new RemoteEvent(
                RemoteEvent.Kind.CQ_EVENT, region, key, value, priorValue, false, originClientId, instanceId));
    }

    private void deliverCqEvent(String region, String key, StoredValue value, StoredValue priorValue,
                                String originClientId) {
        if (cqs.isEmpty() || value == null) {
            return;
        }
        for (Channel channel : feeds) {
            if (!channel.isActive()) {
                continue;
            }
            String feedClientId = channel.attr(CLIENT_ID).get();
            if (feedClientId == null || feedClientId.equals(originClientId)) {
                continue; // self-suppression
            }
            java.util.concurrent.ConcurrentMap<String, Cq> clientCqs = cqs.get(feedClientId);
            if (clientCqs == null) {
                continue;
            }
            for (Cq cq : clientCqs.values()) {
                if (!region.equals(cq.region())) {
                    continue;
                }
                boolean newMatches = cq.query().matches(value, cqFieldResolver);
                boolean priorMatches = priorValue != null && cq.query().matches(priorValue, cqFieldResolver);
                if (newMatches) {
                    int op = priorMatches ? MessageTypes.LOCAL_UPDATE : MessageTypes.LOCAL_CREATE;
                    sendMarkerIfNeeded(channel);
                    channel.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildCqEvent(
                            op, region, key, value, nextVersionTag(), nextEventId(), cq.cqName(), op)));
                    log.info(StructuredLog.event("subscription_cq_event_pushed", "cq", cq.cqName(),
                            "op", priorMatches ? "update" : "create", "region", region, "key", key));
                } else if (priorMatches) {
                    // The updated value no longer matches: the entry leaves the result set (CQ DESTROY).
                    sendMarkerIfNeeded(channel);
                    channel.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildCqDestroy(
                            region, key, nextVersionTag(), nextEventId(), cq.cqName(), MessageTypes.LOCAL_DESTROY)));
                    log.info(StructuredLog.event("subscription_cq_event_pushed", "cq", cq.cqName(),
                            "op", "destroy-stops-matching", "region", region, "key", key));
                }
            }
        }
    }

    /** True if any open feed's client has a CQ registered on this region (gates the prior-value read on remove). */
    public boolean hasCqOnRegion(String region) {
        if (cqs.isEmpty() || region == null) {
            return false;
        }
        for (Channel channel : feeds) {
            java.util.concurrent.ConcurrentMap<String, Cq> clientCqs = cqs.get(channel.attr(CLIENT_ID).get());
            if (clientCqs != null) {
                for (Cq cq : clientCqs.values()) {
                    if (region.equals(cq.region())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Push a CQ DESTROY event to each client whose CQ on this region matched the entry's prior value
     * (the value before removal). For a SELECT * CQ, any prior value matches; with a WHERE, the prior
     * value is evaluated against the predicate. Self-suppressed.
     */
    public void publishCqDestroy(String region, String key, StoredValue priorValue, String originClientId) {
        if (priorValue == null) {
            return;
        }
        deliverCqDestroy(region, key, priorValue, originClientId);
        backplane.publish(new RemoteEvent(
                RemoteEvent.Kind.CQ_DESTROY, region, key, null, priorValue, false, originClientId, instanceId));
    }

    private void deliverCqDestroy(String region, String key, StoredValue priorValue, String originClientId) {
        if (cqs.isEmpty() || priorValue == null) {
            return;
        }
        for (Channel channel : feeds) {
            if (!channel.isActive()) {
                continue;
            }
            String feedClientId = channel.attr(CLIENT_ID).get();
            if (feedClientId == null || feedClientId.equals(originClientId)) {
                continue;
            }
            java.util.concurrent.ConcurrentMap<String, Cq> clientCqs = cqs.get(feedClientId);
            if (clientCqs == null) {
                continue;
            }
            for (Cq cq : clientCqs.values()) {
                if (region.equals(cq.region()) && cq.query().matches(priorValue, cqFieldResolver)) {
                    sendMarkerIfNeeded(channel);
                    byte[] msg = GemResponseWriter.buildCqDestroy(
                            region, key, nextVersionTag(), nextEventId(), cq.cqName(), MessageTypes.LOCAL_DESTROY);
                    channel.writeAndFlush(Unpooled.wrappedBuffer(msg));
                    log.info(StructuredLog.event(
                            "subscription_cq_event_pushed", "cq", cq.cqName(), "op", "destroy",
                            "region", region, "key", key));
                }
            }
        }
    }

    private void sendMarkerIfNeeded(Channel channel) {
        if (channel.attr(MARKER_SENT).setIfAbsent(Boolean.TRUE) == null) {
            byte[] markerEventId = serialize(
                    new EventID(memberId, MARKER_THREAD_ID, markerSequence.incrementAndGet()));
            channel.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildClientMarker(markerEventId)));
        }
    }

    private void pushToFeeds(byte[] message, String kind, String region, String key, String originClientId) {
        int sent = 0;
        for (Channel channel : feeds) {
            if (!channel.isActive()) {
                continue;
            }
            String feedClientId = channel.attr(CLIENT_ID).get();
            // Per-key interest: only push to feeds whose client registered an interest matching this key
            // (all-keys / specific key / key-list / regex).
            if (!isInterestedInKey(feedClientId, region, key)) {
                continue;
            }
            // Self-event suppression: never echo a mutation back to the client that made it.
            if (originClientId != null && originClientId.equals(feedClientId)) {
                continue;
            }
            // CLIENT_MARKER once per feed before its first live event (the GII boundary).
            sendMarkerIfNeeded(channel);
            channel.writeAndFlush(Unpooled.wrappedBuffer(message.clone()));
            sent++;
        }
        log.info(StructuredLog.event(
                "subscription_event_pushed", "kind", kind, "region", region, "key", key, "feeds", sent));
    }

    private byte[] nextEventId() {
        return serialize(new EventID(memberId, DATA_THREAD_ID, sequence.incrementAndGet()));
    }

    private byte[] nextVersionTag() {
        VMVersionTag tag = new VMVersionTag();
        tag.setEntryVersion(entryVersion.incrementAndGet());
        tag.setRegionVersion(regionVersion.incrementAndGet());
        tag.setVersionTimeStamp(System.currentTimeMillis());
        return serialize(tag);
    }

    private static byte[] serialize(Object o) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataSerializer.writeObject(o, new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
