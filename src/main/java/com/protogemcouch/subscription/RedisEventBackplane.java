package com.protogemcouch.subscription;

import com.protogemcouch.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Redis pub/sub {@link EventBackplane}: replicas {@code PUBLISH} each mutation to a shared channel and
 * {@code SUBSCRIBE} to re-deliver mutations from the others. The "first try" cross-replica transport.
 *
 * <p>It speaks the Redis RESP wire protocol over a plain socket with a <b>tiny hand-rolled client</b> —
 * no third-party Redis client library is pulled into the build (so the offline build is unaffected and
 * the shim core stays dependency-light). Two sockets are used: one for publishing and a dedicated,
 * auto-reconnecting subscriber connection (a Redis subscribed connection cannot also issue PUBLISH).
 *
 * <p>This is one swappable implementation behind {@link EventBackplane}; the abstraction is what keeps
 * the shim free of any hard Redis dependency, so this can later be replaced by a self-contained
 * transport (peer mesh, Couchbase change feed) with no change to {@link SubscriptionRegistry}.
 */
public final class RedisEventBackplane implements EventBackplane {

    private static final Logger log = LoggerFactory.getLogger(RedisEventBackplane.class);
    private static final long RECONNECT_BACKOFF_MS = 2000;

    private final String host;
    private final int port;
    private final byte[] channel;

    private volatile boolean closed = false;
    private volatile Consumer<RemoteEvent> handler;
    private Thread subscriberThread;

    // Publisher connection (guarded by the publisher lock; lazily (re)connected).
    private final Object publisherLock = new Object();
    private Socket publisherSocket;
    private OutputStream publisherOut;
    private InputStream publisherIn;
    private volatile Socket subscriberSocket;

    public RedisEventBackplane(String host, int port, String channel) {
        this.host = host;
        this.port = port;
        this.channel = channel.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void publish(RemoteEvent event) {
        if (closed) {
            return;
        }
        byte[] payload = event.toBytes();
        synchronized (publisherLock) {
            try {
                ensurePublisherConnected();
                writeCommand(publisherOut, "PUBLISH".getBytes(StandardCharsets.US_ASCII), channel, payload);
                publisherOut.flush();
                readReply(publisherIn); // integer reply (# of receivers); drained, value unused
            } catch (IOException e) {
                closePublisherQuietly(); // force a reconnect on the next publish
                log.warn(StructuredLog.event("backplane_publish_failed", "transport", "redis",
                        "error", e.getMessage()));
            }
        }
    }

    @Override
    public synchronized void subscribe(Consumer<RemoteEvent> handler) {
        this.handler = handler;
        if (subscriberThread != null || closed) {
            return;
        }
        subscriberThread = new Thread(this::subscriberLoop, "pgc-backplane-redis-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        log.info(StructuredLog.event("backplane_started", "transport", "redis",
                "host", host, "port", port, "channel", new String(channel, StandardCharsets.UTF_8)));
    }

    private void subscriberLoop() {
        while (!closed) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5000);
                socket.setKeepAlive(true);
                subscriberSocket = socket;
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                InputStream in = new BufferedInputStream(socket.getInputStream());
                writeCommand(out, "SUBSCRIBE".getBytes(StandardCharsets.US_ASCII), channel);
                out.flush();
                while (!closed) {
                    Object reply = readReply(in); // ["subscribe"|"message", channel, count|payload]
                    deliverIfMessage(reply);
                }
            } catch (IOException e) {
                if (!closed) {
                    log.warn(StructuredLog.event("backplane_subscriber_reconnecting", "transport", "redis",
                            "error", e.getMessage(), "backoffMs", RECONNECT_BACKOFF_MS));
                    sleep(RECONNECT_BACKOFF_MS);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void deliverIfMessage(Object reply) {
        if (!(reply instanceof List<?> parts) || parts.size() != 3) {
            return;
        }
        if (!(parts.get(0) instanceof byte[] type) || !"message".equals(new String(type, StandardCharsets.US_ASCII))) {
            return; // subscribe confirmation or other push; ignore
        }
        if (!(parts.get(2) instanceof byte[] payload)) {
            return;
        }
        Consumer<RemoteEvent> h = handler;
        if (h == null) {
            return;
        }
        try {
            h.accept(RemoteEvent.fromBytes(payload));
        } catch (RuntimeException e) {
            log.warn(StructuredLog.event("backplane_event_apply_failed", "transport", "redis",
                    "error", e.getMessage()));
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread t = subscriberThread;
        if (t != null) {
            t.interrupt();
        }
        closeQuietly(subscriberSocket);
        synchronized (publisherLock) {
            closePublisherQuietly();
        }
    }

    private void ensurePublisherConnected() throws IOException {
        if (publisherSocket != null && publisherSocket.isConnected() && !publisherSocket.isClosed()) {
            return;
        }
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setKeepAlive(true);
        publisherSocket = socket;
        publisherOut = new BufferedOutputStream(socket.getOutputStream());
        publisherIn = new BufferedInputStream(socket.getInputStream());
    }

    private void closePublisherQuietly() {
        closeQuietly(publisherSocket);
        publisherSocket = null;
        publisherOut = null;
        publisherIn = null;
    }

    // ---------------------------------------------------------------- minimal RESP

    /** Write a command as a RESP array of binary-safe bulk strings. */
    private static void writeCommand(OutputStream out, byte[]... args) throws IOException {
        out.write(('*' + Integer.toString(args.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (byte[] arg : args) {
            out.write(('$' + Integer.toString(arg.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(arg);
            out.write('\r');
            out.write('\n');
        }
    }

    /** Read one RESP reply: array -> List, bulk -> byte[] (or null), integer -> Long, simple/error -> String. */
    private static Object readReply(InputStream in) throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new EOFException("connection closed");
        }
        String line = readLine(in);
        switch (type) {
            case '+':
            case '-':
                return line;
            case ':':
                return Long.parseLong(line);
            case '$': {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    return null;
                }
                byte[] buf = readFully(in, len);
                in.read(); // CR
                in.read(); // LF
                return buf;
            }
            case '*': {
                int count = Integer.parseInt(line);
                if (count < 0) {
                    return null;
                }
                List<Object> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    list.add(readReply(in));
                }
                return list;
            }
            default:
                throw new IOException("Unexpected RESP type byte: " + type);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\r') {
                in.read(); // consume '\n'
                return bos.toString(StandardCharsets.US_ASCII);
            }
            bos.write(b);
        }
        throw new EOFException("connection closed mid-line");
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new EOFException("connection closed mid-bulk");
            }
            off += n;
        }
        return buf;
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
