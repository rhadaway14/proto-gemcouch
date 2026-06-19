package com.protogemcouch.subscription;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the broker-free peer-mesh backplane end-to-end with NO external infrastructure: real
 * {@link MeshEventBackplane} instances on localhost ports, cross-wired as peers, broadcasting a
 * {@link RemoteEvent} directly to each other. This is the Redis-free path the final deployment uses.
 */
class MeshEventBackplaneTest {

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Supplier<List<InetSocketAddress>> peer(int port) {
        return MeshPeers.staticList("127.0.0.1:" + port);
    }

    private static RemoteEvent sampleEvent() {
        return new RemoteEvent(RemoteEvent.Kind.WRITE, "/r1", "k1",
                StoredValue.stringValue("v"), null, true, "clientB", "instance-A");
    }

    @Test
    @Timeout(20)
    void eventPublishedOnOneReplicaReachesAPeer() throws Exception {
        int portA = freePort();
        int portB = freePort();
        MeshEventBackplane a = new MeshEventBackplane(portA, peer(portB), 60);
        MeshEventBackplane b = new MeshEventBackplane(portB, peer(portA), 60);
        try {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<RemoteEvent> got = new AtomicReference<>();
            b.subscribe(e -> {
                got.set(e);
                received.countDown();
            });
            a.subscribe(e -> { });

            RemoteEvent event = sampleEvent();
            // Retry past startup timing (peer set + listener come up asynchronously); duplicates are harmless.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (received.getCount() > 0 && System.nanoTime() < deadline) {
                a.publish(event);
                received.await(200, TimeUnit.MILLISECONDS);
            }

            assertEquals(0, received.getCount(), "peer should have received the broadcast event");
            assertEquals(event, got.get());
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    @Timeout(20)
    void eventFansOutToAllPeers() throws Exception {
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();
        MeshEventBackplane a = new MeshEventBackplane(
                portA, MeshPeers.staticList("127.0.0.1:" + portB + ",127.0.0.1:" + portC), 60);
        MeshEventBackplane b = new MeshEventBackplane(portB, peer(portA), 60);
        MeshEventBackplane c = new MeshEventBackplane(portC, peer(portA), 60);
        try {
            CountDownLatch bGot = new CountDownLatch(1);
            CountDownLatch cGot = new CountDownLatch(1);
            b.subscribe(e -> bGot.countDown());
            c.subscribe(e -> cGot.countDown());
            a.subscribe(e -> { });

            RemoteEvent event = sampleEvent();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while ((bGot.getCount() > 0 || cGot.getCount() > 0) && System.nanoTime() < deadline) {
                a.publish(event);
                Thread.sleep(200);
            }

            assertEquals(0, bGot.getCount(), "peer B should receive the broadcast");
            assertEquals(0, cGot.getCount(), "peer C should receive the broadcast");
        } finally {
            a.close();
            b.close();
            c.close();
        }
    }

    @Test
    void parsesStaticPeerListAndSkipsMalformedEntries() {
        List<InetSocketAddress> peers = MeshPeers.parse("10.0.0.1:40406, 10.0.0.2:40406 , bad, nohost:, :40406");
        assertEquals(2, peers.size());
        assertEquals(40406, peers.get(0).getPort());
    }
}
