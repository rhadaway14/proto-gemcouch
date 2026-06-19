package com.protogemcouch.subscription;

import java.util.function.Consumer;

/**
 * Pluggable cross-replica eventing transport.
 *
 * <p>Subscriptions and CQ events are delivered to the feed channels held by <em>one</em> shim replica.
 * Behind a load balancer a mutation can land on a different replica than a client's feed, so each
 * replica must broadcast its local mutations to the others and re-deliver mutations it hears about. The
 * backplane is that broadcast bus.
 *
 * <p>This interface is the seam that keeps the shim core transport-agnostic: the default is
 * {@link NoOpEventBackplane} (single-instance, no dependency), and a concrete transport (e.g. an
 * opt-in Redis pub/sub adapter) plugs in behind it without touching {@link SubscriptionRegistry}. The
 * core never depends on any specific broker product or client library.
 *
 * <p>Implementations must be safe to call from multiple threads, must not block the caller of
 * {@link #publish}, and must tag/skip a replica's own events (see {@link RemoteEvent#originInstanceId}).
 */
public interface EventBackplane extends AutoCloseable {

    /** Broadcast a locally-originated mutation event to the other replicas. Non-blocking, best-effort. */
    void publish(RemoteEvent event);

    /**
     * Begin receiving events broadcast by other replicas. The handler is invoked once per remote event;
     * the registry applies it to its local feeds. Implementations deliver only events from <em>other</em>
     * replicas where possible, but the registry also drops its own by {@code originInstanceId}.
     */
    void subscribe(Consumer<RemoteEvent> handler);

    @Override
    void close();
}
