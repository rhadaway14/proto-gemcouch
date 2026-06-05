package com.protogemcouch.subscription;

import io.netty.channel.Channel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks client subscriptions for the server&rarr;client event feed (register-interest / P1 first
 * cut). Holds the open feed channels (the {@code PrimaryServerToClient}, mode-101 connections the
 * server pushes events down) and the set of regions any client has registered interest in.
 *
 * <p>P1 scope: interest is tracked per region (ALL_KEYS), not per client, and events are pushed to
 * every feed whose region is of interest. Per-client interest filtering and cross-replica propagation
 * are later phases (see {@code docs/SUBSCRIPTIONS.md}). All state is in-memory and single-instance.
 */
public final class SubscriptionRegistry {

    private final Set<Channel> feeds = ConcurrentHashMap.newKeySet();
    private final Set<String> interestedRegions = ConcurrentHashMap.newKeySet();

    /** Register a feed channel; it is removed automatically when the channel closes. */
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

    /** The open feed channels (server&rarr;client push connections). */
    public Set<Channel> feeds() {
        return feeds;
    }

    public int feedCount() {
        return feeds.size();
    }
}
