package com.protogemcouch.subscription;

import com.protogemcouch.serialization.StoredValue;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for per-client subscription interest tracking. {@code hasInterest(region)} is true only
 * when an open feed's client has registered interest in that region — the gate the publish path uses.
 */
class SubscriptionRegistryTest {

    private static EmbeddedChannel feedFor(SubscriptionRegistry registry, String clientId) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(SubscriptionRegistry.CLIENT_ID).set(clientId);
        registry.addFeed(channel);
        return channel;
    }

    @Test
    void hasInterestRequiresAnOpenFeedWhoseClientRegisteredTheRegion() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        feedFor(registry, "A");

        assertFalse(registry.hasInterest("/r1"), "no interest registered yet");
        registry.registerInterest("A", "/r1");
        assertTrue(registry.hasInterest("/r1"), "client A's feed is interested in /r1");
        assertFalse(registry.hasInterest("/other"), "A is not interested in /other");
    }

    @Test
    void interestIsPerClientNotGlobal() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        feedFor(registry, "A");
        // Client B registered interest, but only A has an open feed here.
        registry.registerInterest("B", "/r1");
        assertFalse(registry.hasInterest("/r1"), "no open feed belongs to the interested client B");

        registry.registerInterest("A", "/r1");
        assertTrue(registry.hasInterest("/r1"));
    }

    @Test
    void unregisterRemovesInterest() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        feedFor(registry, "A");
        registry.registerInterest("A", "/r1");
        assertTrue(registry.hasInterest("/r1"));

        registry.unregisterInterest("A", "/r1");
        assertFalse(registry.hasInterest("/r1"), "interest removed by unregister");
    }

    @Test
    void keyListInterestDeliversOnlyMatchingKeys() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        EmbeddedChannel feed = feedFor(registry, "A");
        registry.registerInterest("A", "/r1", Interest.keys(Set.of("k1", "k3")));

        registry.publishWrite("/r1", "k2", StoredValue.stringValue("v"), false, "other");
        assertNull(feed.readOutbound(), "a key not in the interest list is not delivered");

        registry.publishWrite("/r1", "k1", StoredValue.stringValue("v"), false, "other");
        assertNotNull(feed.readOutbound(), "a key in the interest list is delivered");
    }

    @Test
    void regexInterestDeliversOnlyMatchingKeys() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        EmbeddedChannel feed = feedFor(registry, "A");
        registry.registerInterest("A", "/r1", Interest.regex("order-.*"));

        registry.publishWrite("/r1", "user-7", StoredValue.stringValue("v"), false, "other");
        assertNull(feed.readOutbound(), "a key not matching the regex is not delivered");

        registry.publishWrite("/r1", "order-42", StoredValue.stringValue("v"), false, "other");
        assertNotNull(feed.readOutbound(), "a key matching the regex is delivered");
    }

    @Test
    void allKeysInterestDeliversEveryKey() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        EmbeddedChannel feed = feedFor(registry, "A");
        registry.registerInterest("A", "/r1"); // all-keys (2-arg default)

        registry.publishWrite("/r1", "anything", StoredValue.stringValue("v"), false, "other");
        assertNotNull(feed.readOutbound(), "all-keys interest delivers any key");
    }

    @Test
    void closingAFeedDropsItsClientInterest() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        EmbeddedChannel feed = feedFor(registry, "A");
        registry.registerInterest("A", "/r1");
        assertTrue(registry.hasInterest("/r1"));

        feed.close();
        assertFalse(registry.hasInterest("/r1"), "closing the feed drops the client's interest");
        assertFalse(registry.feeds().contains(feed));
    }
}
