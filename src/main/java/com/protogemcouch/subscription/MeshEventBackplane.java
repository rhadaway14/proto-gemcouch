package com.protogemcouch.subscription;

import com.protogemcouch.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Self-contained, broker-free cross-replica {@link EventBackplane}: a peer mesh. Each replica runs a
 * small TCP listener and broadcasts every {@link RemoteEvent} directly to its peers as a length-prefixed
 * frame; on receipt a replica re-delivers via the registry (which drops its own echoes by
 * {@code originInstanceId}). No external broker, no third-party dependency — the final deployment stays
 * just shim + Couchbase.
 *
 * <p>Peers come from a {@link Supplier} (a static list, or periodic DNS resolution of a Kubernetes
 * headless Service — see {@link MeshPeers}). The set is refreshed on an interval so replicas added or
 * removed (e.g. by an HPA) are picked up; outbound connections are lazy and best-effort, reconnecting
 * on failure. Frame = 4-byte big-endian length + {@code RemoteEvent} bytes.
 */
public final class MeshEventBackplane implements EventBackplane {

    private static final Logger log = LoggerFactory.getLogger(MeshEventBackplane.class);
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    private final int listenPort;
    private final Supplier<List<InetSocketAddress>> peerSource;
    private final long refreshSeconds;
    private final Set<InetSocketAddress> selfAddresses;

    private volatile boolean closed = false;
    private volatile Consumer<RemoteEvent> handler;
    private volatile List<InetSocketAddress> peers = List.of();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ScheduledExecutorService discovery;
    private final ConcurrentHashMap<InetSocketAddress, PeerConnection> outbound = new ConcurrentHashMap<>();

    public MeshEventBackplane(int listenPort, Supplier<List<InetSocketAddress>> peerSource, long refreshSeconds) {
        this.listenPort = listenPort;
        this.peerSource = peerSource;
        this.refreshSeconds = Math.max(1, refreshSeconds);
        this.selfAddresses = MeshPeers.localAddresses(listenPort);
    }

    @Override
    public void publish(RemoteEvent event) {
        if (closed) {
            return;
        }
        byte[] body = event.toBytes();
        byte[] frame = new byte[4 + body.length];
        frame[0] = (byte) (body.length >>> 24);
        frame[1] = (byte) (body.length >>> 16);
        frame[2] = (byte) (body.length >>> 8);
        frame[3] = (byte) body.length;
        System.arraycopy(body, 0, frame, 4, body.length);

        for (InetSocketAddress peer : peers) {
            outbound.computeIfAbsent(peer, PeerConnection::new).trySend(frame);
        }
    }

    @Override
    public synchronized void subscribe(Consumer<RemoteEvent> handler) {
        this.handler = handler;
        if (acceptThread != null || closed) {
            return;
        }
        try {
            serverSocket = new ServerSocket(listenPort);
        } catch (IOException e) {
            throw new IllegalStateException("mesh backplane could not bind port " + listenPort, e);
        }
        acceptThread = new Thread(this::acceptLoop, "pgc-backplane-mesh-listener");
        acceptThread.setDaemon(true);
        acceptThread.start();

        discovery = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pgc-backplane-mesh-discovery");
            t.setDaemon(true);
            return t;
        });
        discovery.scheduleWithFixedDelay(this::refreshPeers, 0, refreshSeconds, TimeUnit.SECONDS);

        log.info(StructuredLog.event("backplane_started", "transport", "mesh", "listenPort", listenPort));
    }

    private void refreshPeers() {
        try {
            List<InetSocketAddress> resolved = peerSource.get().stream()
                    .filter(a -> !selfAddresses.contains(a))   // never mesh to ourselves
                    .toList();
            this.peers = resolved;
            // Drop connections to peers that are gone.
            for (InetSocketAddress addr : Set.copyOf(outbound.keySet())) {
                if (!resolved.contains(addr)) {
                    PeerConnection gone = outbound.remove(addr);
                    if (gone != null) {
                        gone.close();
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn(StructuredLog.event("backplane_peer_discovery_failed", "transport", "mesh",
                    "error", e.getMessage()));
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                Thread reader = new Thread(() -> readLoop(socket), "pgc-backplane-mesh-reader");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                return; // server closed
            }
        }
    }

    private void readLoop(Socket socket) {
        try (socket; InputStream in = new BufferedInputStream(socket.getInputStream())) {
            while (!closed) {
                int len = readInt(in);
                if (len <= 0 || len > MAX_FRAME_BYTES) {
                    return;
                }
                byte[] body = readFully(in, len);
                Consumer<RemoteEvent> h = handler;
                if (h != null) {
                    try {
                        h.accept(RemoteEvent.fromBytes(body));
                    } catch (RuntimeException e) {
                        log.warn(StructuredLog.event("backplane_event_apply_failed", "transport", "mesh",
                                "error", e.getMessage()));
                    }
                }
            }
        } catch (IOException ignored) {
            // peer disconnected
        }
    }

    @Override
    public void close() {
        closed = true;
        if (discovery != null) {
            discovery.shutdownNow();
        }
        closeQuietly(serverSocket);
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        for (PeerConnection c : outbound.values()) {
            c.close();
        }
        outbound.clear();
    }

    /** A lazy, best-effort outbound connection to one peer; reconnects on the next send after a failure. */
    private final class PeerConnection {
        private final InetSocketAddress peer;
        private Socket socket;
        private OutputStream out;

        PeerConnection(InetSocketAddress peer) {
            this.peer = peer;
        }

        synchronized void trySend(byte[] frame) {
            try {
                if (socket == null || socket.isClosed() || !socket.isConnected()) {
                    Socket s = new Socket();
                    s.connect(peer, 3000);
                    s.setKeepAlive(true);
                    socket = s;
                    out = new BufferedOutputStream(s.getOutputStream());
                }
                out.write(frame);
                out.flush();
            } catch (IOException e) {
                close(); // reconnect next publish
                // Best-effort: a peer being down must not fail the mutation. Logged at debug to avoid noise.
                log.debug("mesh backplane send to {} failed: {}", peer, e.getMessage());
            }
        }

        synchronized void close() {
            closeQuietly(socket);
            socket = null;
            out = null;
        }
    }

    private static int readInt(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        int b4 = in.read();
        if ((b1 | b2 | b3 | b4) < 0) {
            throw new EOFException("connection closed");
        }
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new EOFException("connection closed mid-frame");
            }
            off += n;
        }
        return buf;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
