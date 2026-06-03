package com.protogemcouch.shim;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.SocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConnectionTrackingHandlerTest {

    private static final class RecordingListener implements ConnectionTrackingListener {
        int opened;
        int closed;
        int rejected;

        @Override
        public void onConnectionOpened(SocketAddress remote, int activeConnections) {
            opened++;
        }

        @Override
        public void onConnectionClosed(SocketAddress remote) {
            closed++;
        }

        @Override
        public void onConnectionRejected(SocketAddress remote, int maxConnections) {
            rejected++;
        }
    }

    @Test
    void countsOpenAndCloseAndReleasesTheSlot() {
        ConnectionLimiter limiter = new ConnectionLimiter(0); // unlimited
        RecordingListener listener = new RecordingListener();

        EmbeddedChannel channel = new EmbeddedChannel(new ConnectionTrackingHandler(limiter, listener));
        // EmbeddedChannel fires channelActive on registration.
        assertEquals(1, listener.opened);
        assertEquals(1, limiter.activeConnections());

        channel.close();
        // The close must be counted and the slot released even though, in production, the handshake
        // handler ahead of this one removes itself mid-connection.
        assertEquals(1, listener.closed);
        assertEquals(0, limiter.activeConnections());
    }

    @Test
    void rejectsAndClosesWhenOverTheCap() {
        ConnectionLimiter limiter = new ConnectionLimiter(1);
        limiter.tryAcquire(); // occupy the only slot
        RecordingListener listener = new RecordingListener();

        EmbeddedChannel channel = new EmbeddedChannel(new ConnectionTrackingHandler(limiter, listener));

        assertEquals(1, listener.rejected);
        assertEquals(0, listener.opened);
        assertFalse(channel.isOpen(), "over-cap connection should be closed");

        // A rejected connection never acquired a slot, so closing it must not over-release.
        channel.close();
        assertEquals(1, limiter.activeConnections());
        assertEquals(0, listener.closed);
    }
}
