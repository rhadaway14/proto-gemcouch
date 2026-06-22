package com.protogemcouch.subscription;

import com.protogemcouch.couchbase.DurableRecord;
import com.protogemcouch.couchbase.Repository;
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

    /** A durable client's stable durable id (parsed from the handshake), set on durable connections only. */
    public static final AttributeKey<String> DURABLE_ID =
            AttributeKey.valueOf("protogemcouch.subscription.durableId");

    /** A durable client's requested durable-client-timeout (seconds). */
    public static final AttributeKey<Integer> DURABLE_TIMEOUT =
            AttributeKey.valueOf("protogemcouch.subscription.durableTimeout");

    /**
     * Marks a server&rarr;client subscription feed (mode 101). A feed is long-lived and legitimately
     * idle while waiting for events, so it is exempt from the idle-connection reaper (keepalive); dead
     * feeds are detected at the TCP layer. Set when the feed is registered.
     */
    public static final AttributeKey<Boolean> IS_FEED =
            AttributeKey.valueOf("protogemcouch.subscription.isFeed");

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

    // Durable clients (single-instance first cut): when a durable client's feed disconnects we retain
    // its interest and queue matching events in memory, replaying them on reconnect + CLIENT_READY, and
    // dropping the queue if it doesn't return within its timeout. Multi-replica durable persistence
    // (Couchbase-backed) is a documented follow-up. Keyed by durable id.
    private static final int DURABLE_MAX_QUEUE = intEnv("DURABLE_MAX_QUEUE", 100_000);
    private static final int DURABLE_DEFAULT_TIMEOUT_SECONDS = 300;
    private final java.util.concurrent.ConcurrentMap<String, DurableState> durableClients = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService durableExpiry =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pgc-durable-expiry");
                t.setDaemon(true);
                return t;
            });

    // Multi-replica durable persistence (1.2.0-M1 Slice 2): when enabled, a durable client's registry
    // record (interests/timeout/away) AND its disconnect-time event queue are persisted to Couchbase via
    // the Repository, so they survive this replica failing and replay on a reconnect to ANY replica. Off
    // (the default) keeps the in-memory single-instance behavior byte-for-byte unchanged. The flag is set
    // once at startup from DURABLE_PERSISTENCE (see HandlerRegistryFactory).
    private static final int DURABLE_SWEEP_SECONDS = intEnv("DURABLE_SWEEP_SECONDS", 60);
    private volatile Repository durableRepository;
    private volatile boolean durablePersistence;

    /** In-memory state for one durable client: its current feed (or null while away) + its event queue. */
    private static final class DurableState {
        volatile Channel liveFeed;        // current feed channel, or null while disconnected
        volatile boolean ready;           // true after CLIENT_READY — only then is delivery live
        volatile int timeoutSeconds = DURABLE_DEFAULT_TIMEOUT_SECONDS;
        volatile java.util.concurrent.ScheduledFuture<?> expiry;
        final java.util.concurrent.ConcurrentLinkedDeque<byte[]> pending = new java.util.concurrent.ConcurrentLinkedDeque<>();
        final AtomicInteger pendingCount = new AtomicInteger();
    }

    private static int intEnv(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // --- observability accessors (sampled by the metrics gauges; weakly-consistent reads).
    // feedCount() is defined further down with the feed bookkeeping.

    /** Total registered interests across all clients and regions. */
    public int interestCount() {
        int total = 0;
        for (java.util.concurrent.ConcurrentMap<String, java.util.List<Interest>> perRegion : interests.values()) {
            for (java.util.List<Interest> list : perRegion.values()) {
                total += list.size();
            }
        }
        return total;
    }

    /** Total registered continuous queries across all clients. */
    public int cqCount() {
        int total = 0;
        for (java.util.concurrent.ConcurrentMap<String, Cq> perClient : cqs.values()) {
            total += perClient.size();
        }
        return total;
    }

    /** Number of durable clients currently retained (connected or awaiting reconnect). */
    public int durableClientCount() {
        return durableClients.size();
    }

    /** Total queued (undelivered) events across all durable clients. */
    public long durableQueueDepth() {
        long total = 0;
        for (DurableState state : durableClients.values()) {
            total += state.pendingCount.get();
        }
        return total;
    }

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
     * Enable Couchbase-backed durable persistence (1.2.0-M1): durable records + event queues are
     * persisted via {@code repository}, and a periodic cross-replica timeout sweep runs. Called once at
     * startup when {@code DURABLE_PERSISTENCE} is set. A null repository leaves persistence off.
     */
    public void enableDurablePersistence(Repository repository) {
        this.durableRepository = repository;
        this.durablePersistence = repository != null;
        if (this.durablePersistence) {
            // Authoritative, instance-independent expiry: drops away docs whose timeout elapsed even if
            // the replica that owned the client is gone (the local per-client timer only frees memory).
            durableExpiry.scheduleWithFixedDelay(() -> {
                try {
                    durableRepository.sweepExpiredDurable();
                } catch (RuntimeException e) {
                    log.warn(StructuredLog.event("durable_sweep_failed", "error", e.getMessage()));
                }
            }, DURABLE_SWEEP_SECONDS, DURABLE_SWEEP_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            log.info(StructuredLog.event("durable_persistence_enabled", "sweepSeconds", DURABLE_SWEEP_SECONDS));
        }
    }

    private boolean durablePersistenceActive() {
        return durablePersistence && durableRepository != null;
    }

    /** Build the persistable record for a durable client from its current in-memory interests. */
    private DurableRecord buildDurableRecord(String durableId, int timeoutSeconds, boolean away) {
        java.util.List<DurableRecord.InterestSpec> specs = new java.util.ArrayList<>();
        java.util.concurrent.ConcurrentMap<String, java.util.List<Interest>> byRegion = interests.get(durableId);
        if (byRegion != null) {
            for (java.util.Map.Entry<String, java.util.List<Interest>> e : byRegion.entrySet()) {
                for (Interest interest : e.getValue()) {
                    specs.add(toInterestSpec(e.getKey(), interest));
                }
            }
        }
        // CQ-definition persistence (needed only for cross-replica CQ *evaluation*) is Slice 3; durable
        // CQ *events* already replay via the persisted queue, so they survive here regardless.
        return new DurableRecord(durableId, timeoutSeconds, away, specs, java.util.List.of());
    }

    private static DurableRecord.InterestSpec toInterestSpec(String region, Interest interest) {
        if (interest instanceof Interest.Keys k) {
            return DurableRecord.InterestSpec.keys(region, new java.util.ArrayList<>(k.keys()));
        }
        if (interest instanceof Interest.Regex r) {
            return DurableRecord.InterestSpec.regex(region, r.pattern().pattern());
        }
        return DurableRecord.InterestSpec.allKeys(region);
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
        channel.attr(IS_FEED).set(Boolean.TRUE); // exempt from idle reaping (keepalive)
        String durableId = channel.attr(DURABLE_ID).get();
        if (durableId != null && !durableId.isBlank()) {
            attachDurableFeed(durableId, channel);
        }
        channel.closeFuture().addListener(f -> onFeedClosed(channel));
    }

    private void onFeedClosed(Channel channel) {
        feeds.remove(channel);
        String durableId = channel.attr(DURABLE_ID).get();
        if (durableId != null && !durableId.isBlank()) {
            // Durable: keep interest + the event queue, detach the feed, and start the expiry timer.
            DurableState state = durableClients.get(durableId);
            if (state != null) {
                synchronized (state) {
                    if (state.liveFeed == channel) {
                        state.liveFeed = null;
                        state.ready = false;
                    }
                    scheduleDurableExpiry(durableId, state);
                }
                if (durablePersistenceActive()) {
                    // Persist the registry record (away) so any replica can serve this client, and flush
                    // any in-memory pending to Couchbase so the queue survives this replica failing.
                    byte[] m;
                    while ((m = state.pending.pollFirst()) != null) {
                        state.pendingCount.decrementAndGet();
                        durableRepository.enqueueDurableEvent(durableId, m);
                    }
                    durableRepository.saveDurable(buildDurableRecord(durableId, state.timeoutSeconds, true));
                }
                log.info(StructuredLog.event("durable_client_disconnected", "durableId", durableId,
                        "queued", state.pendingCount.get(), "timeoutSeconds", state.timeoutSeconds,
                        "persisted", durablePersistenceActive()));
            }
            return;
        }
        // Non-durable: a client has one feed, so drop its interests and CQs on close.
        String clientId = channel.attr(CLIENT_ID).get();
        if (clientId != null) {
            interests.remove(clientId);
            cqs.remove(clientId);
        }
    }

    /** Attach a (re)connecting durable client's feed: cancel any pending expiry; await CLIENT_READY. */
    private void attachDurableFeed(String durableId, Channel channel) {
        DurableState state = durableClients.computeIfAbsent(durableId, k -> new DurableState());
        Integer timeout = channel.attr(DURABLE_TIMEOUT).get();
        if (timeout != null && timeout > 0) {
            state.timeoutSeconds = timeout;
        }
        synchronized (state) {
            state.liveFeed = channel;
            state.ready = false; // a reconnecting client re-calls readyForEvents() before live delivery
            if (state.expiry != null) {
                state.expiry.cancel(false);
                state.expiry = null;
            }
        }
        if (durablePersistenceActive()) {
            // The client may be reconnecting to a replica that never saw it; its persisted queue is
            // waiting in Couchbase (drained on CLIENT_READY). Mark the doc not-away so the timeout sweep
            // won't reclaim it mid-reconnect (no-op if no doc exists — a never-disconnected client).
            durableRepository.markDurableAway(durableId, false);
        }
        log.info(StructuredLog.event("durable_client_connected", "durableId", durableId,
                "queued", state.pendingCount.get(), "persisted", durablePersistenceActive()));
    }

    private void scheduleDurableExpiry(String durableId, DurableState state) {
        if (state.expiry != null) {
            state.expiry.cancel(false);
        }
        state.expiry = durableExpiry.schedule(
                () -> dropDurable(durableId), state.timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void dropDurable(String durableId) {
        DurableState state = durableClients.remove(durableId);
        if (state != null && state.liveFeed == null) {
            interests.remove(durableId);
            cqs.remove(durableId);
            state.pending.clear();
            // When persistence is on, this local per-client timer only frees in-memory state — it must
            // NOT drop the Couchbase doc, because the client may have reconnected to another replica.
            // The authoritative, instance-independent cleanup is sweepExpiredDurable() (checks the away
            // flag + awaySince), so the persisted record/queue outlive this replica losing the client.
            log.info(StructuredLog.event("durable_client_expired", "durableId", durableId,
                    "persisted", durablePersistenceActive()));
        } else if (state != null) {
            // Reconnected between expiry firing and now: keep it.
            durableClients.put(durableId, state);
        }
    }

    /**
     * A reconnected durable client signalled readiness (CLIENT_READY): replay its queued events down the
     * now-attached feed in order, then resume live delivery.
     */
    public void onClientReady(String durableId) {
        if (durableId == null) {
            return;
        }
        DurableState state = durableClients.get(durableId);
        if (state == null) {
            return;
        }
        Channel feed = state.liveFeed;
        if (feed == null || !feed.isActive()) {
            state.ready = true; // nothing to replay onto yet; live once a feed attaches
            return;
        }
        sendMarkerIfNeeded(feed);
        int replayed = 0;
        byte[] msg;
        while ((msg = state.pending.pollFirst()) != null) {
            state.pendingCount.decrementAndGet();
            feed.writeAndFlush(Unpooled.wrappedBuffer(msg));
            replayed++;
        }
        if (durablePersistenceActive()) {
            // Replay the Couchbase-persisted queue — this is what lets a reconnect to ANY replica recover
            // the events missed while away, even if the original owner replica is gone.
            for (byte[] event : durableRepository.drainDurableQueue(durableId)) {
                feed.writeAndFlush(Unpooled.wrappedBuffer(event));
                replayed++;
            }
            durableRepository.markDurableAway(durableId, false);
        }
        state.ready = true;
        log.info(StructuredLog.event("durable_client_replayed", "durableId", durableId, "events", replayed,
                "persisted", durablePersistenceActive()));
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
        // A durable client sends CLOSECQ as part of its keepalive close; retain its CQ across the
        // disconnect so CQ events queue for replay (until the durable timeout). (First-cut limitation:
        // a durable client can't stop/close a CQ mid-session.)
        if (durableClients.containsKey(clientId)) {
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
        // A durable client sends UNREGISTER_INTEREST as part of its keepalive close, but its interest
        // must be RETAINED across disconnect so events queue for replay; keep it until the durable
        // timeout. (First-cut limitation: a durable client can't unregister interest mid-session.)
        if (durableClients.containsKey(clientId)) {
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
        // Disconnected durable clients keep their interest so their events are queued for replay.
        for (String durableId : durableClients.keySet()) {
            if (isInterested(durableId, region)) {
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
        // Iterate every client with CQs (so a single mutation fires each of a client's matching CQs, and
        // a disconnected durable client's CQ events are queued for replay rather than lost).
        for (java.util.Map.Entry<String, java.util.concurrent.ConcurrentMap<String, Cq>> entry : cqs.entrySet()) {
            String clientId = entry.getKey();
            if (clientId.equals(originClientId)) {
                continue; // self-suppression
            }
            for (Cq cq : entry.getValue().values()) {
                if (!region.equals(cq.region())) {
                    continue;
                }
                boolean newMatches = cq.query().matches(value, cqFieldResolver);
                boolean priorMatches = priorValue != null && cq.query().matches(priorValue, cqFieldResolver);
                if (newMatches) {
                    int op = priorMatches ? MessageTypes.LOCAL_UPDATE : MessageTypes.LOCAL_CREATE;
                    deliverOrQueueCq(clientId, GemResponseWriter.buildCqEvent(
                            op, region, key, value, nextVersionTag(), nextEventId(), cq.cqName(), op));
                    log.info(StructuredLog.event("subscription_cq_event_pushed", "cq", cq.cqName(),
                            "op", priorMatches ? "update" : "create", "region", region, "key", key));
                } else if (priorMatches) {
                    // The updated value no longer matches: the entry leaves the result set (CQ DESTROY).
                    deliverOrQueueCq(clientId, GemResponseWriter.buildCqDestroy(
                            region, key, nextVersionTag(), nextEventId(), cq.cqName(), MessageTypes.LOCAL_DESTROY));
                    log.info(StructuredLog.event("subscription_cq_event_pushed", "cq", cq.cqName(),
                            "op", "destroy-stops-matching", "region", region, "key", key));
                }
            }
        }
    }

    /** Deliver a CQ event live to the client's ready feed, or queue it for a disconnected durable client. */
    private void deliverOrQueueCq(String clientId, byte[] message) {
        Channel feed = liveReadyFeed(clientId);
        if (feed != null) {
            sendMarkerIfNeeded(feed);
            feed.writeAndFlush(Unpooled.wrappedBuffer(message));
            return;
        }
        DurableState durable = durableClients.get(clientId);
        if (durable != null) {
            // disconnected / not-yet-ready durable client: queue for replay (Couchbase when persistence
            // is on, so the queue survives a replica failing; in-memory otherwise).
            if (durablePersistenceActive()) {
                durableRepository.enqueueDurableEvent(clientId, message);
            } else {
                enqueueDurable(durable, message);
            }
        }
        // else: a non-durable client with no live feed — dropped (the client is gone)
    }

    /** This client's active feed if it is ready to receive live events, else null (queue instead). */
    private Channel liveReadyFeed(String clientId) {
        if (clientId == null) {
            return null;
        }
        for (Channel channel : feeds) {
            if (channel.isActive() && clientId.equals(channel.attr(CLIENT_ID).get())) {
                DurableState durable = durableClients.get(clientId);
                return (durable != null && !durable.ready) ? null : channel;
            }
        }
        return null;
    }

    /** True if any client (connected or a retained durable client) has a CQ on this region — gates the
     *  prior-value read on remove so a CQ DESTROY can be computed. */
    public boolean hasCqOnRegion(String region) {
        if (cqs.isEmpty() || region == null) {
            return false;
        }
        for (java.util.concurrent.ConcurrentMap<String, Cq> clientCqs : cqs.values()) {
            for (Cq cq : clientCqs.values()) {
                if (region.equals(cq.region())) {
                    return true;
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
        for (java.util.Map.Entry<String, java.util.concurrent.ConcurrentMap<String, Cq>> entry : cqs.entrySet()) {
            String clientId = entry.getKey();
            if (clientId.equals(originClientId)) {
                continue;
            }
            for (Cq cq : entry.getValue().values()) {
                if (region.equals(cq.region()) && cq.query().matches(priorValue, cqFieldResolver)) {
                    deliverOrQueueCq(clientId, GemResponseWriter.buildCqDestroy(
                            region, key, nextVersionTag(), nextEventId(), cq.cqName(), MessageTypes.LOCAL_DESTROY));
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
        java.util.Set<String> liveClients = new java.util.HashSet<>();
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
            // A durable feed delivers live only after CLIENT_READY; before that its events are queued
            // (handled in the durable loop below) so they replay in order.
            DurableState durable = feedClientId == null ? null : durableClients.get(feedClientId);
            if (durable != null && !durable.ready) {
                continue;
            }
            // CLIENT_MARKER once per feed before its first live event (the GII boundary).
            sendMarkerIfNeeded(channel);
            channel.writeAndFlush(Unpooled.wrappedBuffer(message.clone()));
            sent++;
            if (feedClientId != null) {
                liveClients.add(feedClientId);
            }
        }
        // Durable clients that are disconnected or attached-but-not-ready: queue the event for replay.
        int queued = 0;
        for (java.util.Map.Entry<String, DurableState> entry : durableClients.entrySet()) {
            String durableId = entry.getKey();
            DurableState state = entry.getValue();
            if (state.ready && state.liveFeed != null) {
                continue; // delivered live above
            }
            if (liveClients.contains(durableId) || !isInterestedInKey(durableId, region, key)) {
                continue;
            }
            if (originClientId != null && originClientId.equals(durableId)) {
                continue;
            }
            if (durablePersistenceActive()) {
                durableRepository.enqueueDurableEvent(durableId, message.clone());
            } else {
                enqueueDurable(state, message.clone());
            }
            queued++;
        }
        log.info(StructuredLog.event("subscription_event_pushed", "kind", kind, "region", region,
                "key", key, "feeds", sent, "durableQueued", queued));
    }

    private static void enqueueDurable(DurableState state, byte[] message) {
        while (state.pendingCount.get() >= DURABLE_MAX_QUEUE) {
            if (state.pending.pollFirst() != null) {
                state.pendingCount.decrementAndGet(); // bounded: drop oldest under sustained overflow
            } else {
                break;
            }
        }
        state.pending.addLast(message);
        state.pendingCount.incrementAndGet();
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
