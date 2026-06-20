package com.protogemcouch.subscription;

import com.protogemcouch.serialization.StoredValue;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Durable-client state machine (single-instance): a durable client's events are queued while it is
 * disconnected (or attached but not yet ready) and replayed on reconnect + CLIENT_READY.
 */
class DurableSubscriptionTest {

    private static EmbeddedChannel durableFeed(SubscriptionRegistry registry, String durableId) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(SubscriptionRegistry.CLIENT_ID).set(durableId);
        channel.attr(SubscriptionRegistry.DURABLE_ID).set(durableId);
        channel.attr(SubscriptionRegistry.DURABLE_TIMEOUT).set(300);
        registry.addFeed(channel);
        return channel;
    }

    @Test
    void eventsQueueWhileDisconnectedAndReplayOnReconnectReady() {
        SubscriptionRegistry registry = new SubscriptionRegistry();

        // Connect durable client "d1", register interest, signal ready -> live delivery.
        EmbeddedChannel feed1 = durableFeed(registry, "d1");
        registry.registerInterest("d1", "/r1");
        registry.onClientReady("d1");
        registry.publishWrite("/r1", "k1", StoredValue.stringValue("v"), false, "other");
        assertNotNull(feed1.readOutbound(), "ready durable client gets live events");

        // Disconnect: the queue + interest are retained, and an event arriving WHILE AWAY is queued.
        feed1.close();
        registry.publishWrite("/r1", "k2", StoredValue.stringValue("v"), false, "other"); // queued, not lost

        // Reconnect with the SAME durable id: nothing replays until CLIENT_READY.
        EmbeddedChannel feed2 = durableFeed(registry, "d1");
        assertNull(feed2.readOutbound(), "no replay before readyForEvents()");

        // readyForEvents -> the missed event replays (a CLIENT_MARKER, then the queued k2 event).
        registry.onClientReady("d1");
        assertNotNull(feed2.readOutbound(), "CLIENT_MARKER sent on reconnect");
        assertNotNull(feed2.readOutbound(), "the event missed while disconnected is replayed");
        assertNull(feed2.readOutbound(), "exactly the queued backlog is replayed");

        // A subsequent live event is delivered immediately now that the client is ready again.
        registry.publishWrite("/r1", "k3", StoredValue.stringValue("v"), false, "other");
        assertNotNull(feed2.readOutbound(), "live delivery resumes after replay");
    }

    @Test
    void durableClientRetainsInterestThroughUnregisterOnClose() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        EmbeddedChannel feed = durableFeed(registry, "d3");
        registry.registerInterest("d3", "/r1");
        registry.onClientReady("d3");
        // A durable client sends UNREGISTER on its keepalive close — its interest must survive it.
        registry.unregisterInterest("d3", "/r1");
        registry.publishWrite("/r1", "k", StoredValue.stringValue("v"), false, "other");
        assertNotNull(feed.readOutbound(), "a durable client keeps its interest through unregister-on-close");
    }

    @Test
    void nonReadyDurableClientQueuesInsteadOfLiveDelivery() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        EmbeddedChannel feed = durableFeed(registry, "d2");
        registry.registerInterest("d2", "/r1");
        // Not ready yet (no CLIENT_READY): events must queue, not deliver live.
        registry.publishWrite("/r1", "k1", StoredValue.stringValue("v"), false, "other");
        assertNull(feed.readOutbound(), "a durable client that hasn't called readyForEvents gets no live events");

        registry.onClientReady("d2");
        assertNotNull(feed.readOutbound(), "queued events replay once ready");
    }
}
