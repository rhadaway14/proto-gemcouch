package com.protogemcouch.shim;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstRequestDeadlineHandlerTest {

    @Test
    void closesConnectionWhenFirstRequestNeverArrives() {
        AtomicInteger timeouts = new AtomicInteger();
        FirstRequestTimeoutListener listener = (SocketAddress remote) -> timeouts.incrementAndGet();

        // A long timeout so the real scheduled task does not fire during the test; we invoke the
        // deadline check directly to simulate expiry.
        FirstRequestDeadlineHandler handler = new FirstRequestDeadlineHandler(60_000, listener);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        ChannelHandlerContext ctx = channel.pipeline().context(handler);
        handler.enforceFirstRequestDeadline(ctx);

        assertEquals(1, timeouts.get(), "deadline should fire when no first request completed");
        assertFalse(channel.isOpen(), "connection should be closed on first-request timeout");
    }

    @Test
    void firstRequestCompletionCancelsTheDeadline() {
        AtomicInteger timeouts = new AtomicInteger();
        FirstRequestDeadlineHandler handler = new FirstRequestDeadlineHandler(
                60_000, remote -> timeouts.incrementAndGet());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        ChannelHandlerContext ctx = channel.pipeline().context(handler);

        // Simulate the dispatch handler signalling that a real request completed.
        channel.pipeline().fireUserEventTriggered(FirstRequestCompletedEvent.INSTANCE);

        // The handler should have removed itself and the deadline must now be a no-op.
        assertNull(channel.pipeline().context(handler), "handler should remove itself after completion");
        handler.enforceFirstRequestDeadline(ctx);

        assertEquals(0, timeouts.get(), "deadline must not fire once a first request completed");
        assertTrue(channel.isOpen(), "connection should stay open after completing a request");
        channel.finishAndReleaseAll();
    }
}
