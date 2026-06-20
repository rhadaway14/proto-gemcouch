package com.protogemcouch.shim;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleConnectionHandlerTest {

    @Test
    void closesConnectionAndNotifiesListenerOnIdleEvent() {
        AtomicInteger idleCalls = new AtomicInteger();
        IdleConnectionListener listener = (SocketAddress remote) -> idleCalls.incrementAndGet();

        EmbeddedChannel channel = new EmbeddedChannel(new IdleConnectionHandler(listener));

        channel.pipeline().fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);

        assertEquals(1, idleCalls.get());
        assertFalse(channel.isOpen(), "idle connection should be closed");
    }

    @Test
    void keepsAnIdleSubscriptionFeedAlive() {
        AtomicInteger idleCalls = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(
                new IdleConnectionHandler(remote -> idleCalls.incrementAndGet()));
        channel.attr(com.protogemcouch.subscription.SubscriptionRegistry.IS_FEED).set(Boolean.TRUE);

        channel.pipeline().fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);

        assertEquals(0, idleCalls.get(), "a feed's idle event is not a reap");
        assertTrue(channel.isOpen(), "a subscription/durable feed is exempt from idle reaping (keepalive)");
        channel.finishAndReleaseAll();
    }

    @Test
    void ignoresUnrelatedUserEvents() {
        AtomicInteger idleCalls = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(
                new IdleConnectionHandler(remote -> idleCalls.incrementAndGet()));

        channel.pipeline().fireUserEventTriggered(new Object());

        assertEquals(0, idleCalls.get());
        assertTrue(channel.isOpen(), "unrelated events must not close the connection");
        channel.finishAndReleaseAll();
    }
}
