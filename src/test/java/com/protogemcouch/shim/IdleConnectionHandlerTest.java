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
