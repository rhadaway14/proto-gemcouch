package com.protogemcouch.subscription;

import com.protogemcouch.serialization.StoredValue;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the cross-replica eventing seam: publish broadcasts a {@link RemoteEvent} on the backplane, a
 * remote event from another replica is delivered to local feeds, and a replica's own echoed events are
 * dropped by {@code originInstanceId}.
 */
class SubscriptionBackplaneTest {

    /** Captures published events and exposes the handler the registry subscribed, so tests can inject remote events. */
    private static final class FakeBackplane implements EventBackplane {
        final List<RemoteEvent> published = new ArrayList<>();
        Consumer<RemoteEvent> handler;

        @Override public void publish(RemoteEvent event) { published.add(event); }
        @Override public void subscribe(Consumer<RemoteEvent> handler) { this.handler = handler; }
        @Override public void close() { }

        void deliverFromRemote(RemoteEvent event) { handler.accept(event); }
    }

    private static EmbeddedChannel feedFor(SubscriptionRegistry registry, String clientId) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(SubscriptionRegistry.CLIENT_ID).set(clientId);
        registry.addFeed(channel);
        return channel;
    }

    @Test
    void remoteEventBytesRoundTripIncludingStoredValue() {
        RemoteEvent original = new RemoteEvent(RemoteEvent.Kind.WRITE, "/r1", "k1",
                StoredValue.stringValue("hello"), null, true, "clientB", "instance-7");
        RemoteEvent restored = RemoteEvent.fromBytes(original.toBytes());
        assertEquals(original, restored);
        assertEquals("hello", restored.value().value());
    }

    @Test
    void noOpBackplaneDoesNothing() {
        EventBackplane backplane = new NoOpEventBackplane();
        backplane.subscribe(e -> fail("no-op should never deliver"));
        backplane.publish(new RemoteEvent(RemoteEvent.Kind.DESTROY, "/r", "k", null, null, false, "c", "i"));
        backplane.close(); // no throw
    }

    @Test
    void publishBroadcastsAWriteEventStampedWithThisInstanceId() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        FakeBackplane backplane = new FakeBackplane();
        registry.setBackplane(backplane);

        registry.publishWrite("/r1", "k1", StoredValue.stringValue("v"), false, "clientA");

        assertEquals(1, backplane.published.size());
        RemoteEvent event = backplane.published.get(0);
        assertEquals(RemoteEvent.Kind.WRITE, event.kind());
        assertEquals("/r1", event.region());
        assertEquals(registry.instanceId(), event.originInstanceId(), "broadcast is stamped with this replica's id");
    }

    @Test
    void remoteWriteFromAnotherReplicaIsDeliveredToAnInterestedLocalFeed() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        FakeBackplane backplane = new FakeBackplane();
        registry.setBackplane(backplane);

        EmbeddedChannel feed = feedFor(registry, "A");
        registry.registerInterest("A", "/r1");

        // A mutation that happened on another replica (originClientId B, different instance id).
        backplane.deliverFromRemote(new RemoteEvent(RemoteEvent.Kind.WRITE, "/r1", "k1",
                StoredValue.stringValue("v"), null, false, "B", "other-instance"));

        assertNotNull(feed.readOutbound(), "interested local feed receives the remote replica's event");
    }

    @Test
    void aReplicaDropsItsOwnEchoedEvents() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        FakeBackplane backplane = new FakeBackplane();
        registry.setBackplane(backplane);

        EmbeddedChannel feed = feedFor(registry, "A");
        registry.registerInterest("A", "/r1");

        // Same instance id as the registry -> already delivered locally; must be ignored when echoed back.
        backplane.deliverFromRemote(new RemoteEvent(RemoteEvent.Kind.WRITE, "/r1", "k1",
                StoredValue.stringValue("v"), null, false, "B", registry.instanceId()));

        assertNull(feed.readOutbound(), "a replica must not re-deliver its own echoed event");
    }
}
