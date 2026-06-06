package com.protogemcouch.subscription;

import com.protogemcouch.observability.StructuredLog;
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

    private final Set<Channel> feeds = ConcurrentHashMap.newKeySet();
    private final Set<String> interestedRegions = ConcurrentHashMap.newKeySet();

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

    public void addFeed(Channel channel) {
        feeds.add(channel);
        channel.closeFuture().addListener(f -> feeds.remove(channel));
    }

    public void registerInterest(String region) {
        if (region != null && !region.isBlank()) {
            interestedRegions.add(region);
        }
    }

    public void unregisterInterest(String region) {
        if (region != null) {
            interestedRegions.remove(region);
        }
    }

    /** True if any client has registered interest in this region and at least one feed is open. */
    public boolean hasInterest(String region) {
        return !feeds.isEmpty() && region != null && interestedRegions.contains(region);
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
    public void publishWrite(String region, String key, StoredValue value, boolean update) {
        if (!hasInterest(region) || value == null) {
            return;
        }
        int messageType = update ? MessageTypes.LOCAL_UPDATE : MessageTypes.LOCAL_CREATE;
        byte[] message = GemResponseWriter.buildLocalWrite(
                messageType, region, key, value, nextVersionTag(), nextEventId());
        pushToFeeds(message, update ? "update" : "create", region, key);
    }

    /** Push a destroy notification for an interested region's key to every open feed. */
    public void publishDestroy(String region, String key) {
        if (!hasInterest(region)) {
            return;
        }
        byte[] message = GemResponseWriter.buildLocalDestroy(region, key, nextVersionTag(), nextEventId());
        pushToFeeds(message, "destroy", region, key);
    }

    private void pushToFeeds(byte[] message, String kind, String region, String key) {
        int sent = 0;
        for (Channel channel : feeds) {
            if (!channel.isActive()) {
                continue;
            }
            // CLIENT_MARKER once per feed before its first live event (the GII boundary). Uses a
            // distinct thread id so it does not shadow data events in the client's EventID dedup.
            if (channel.attr(MARKER_SENT).setIfAbsent(Boolean.TRUE) == null) {
                byte[] markerEventId = serialize(
                        new EventID(memberId, MARKER_THREAD_ID, markerSequence.incrementAndGet()));
                channel.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildClientMarker(markerEventId)));
            }
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
