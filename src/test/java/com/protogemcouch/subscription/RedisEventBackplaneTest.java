package com.protogemcouch.subscription;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the hand-rolled RESP client in {@link RedisEventBackplane} end-to-end against a minimal
 * in-test RESP broker — proving publish/subscribe round-trips a {@link RemoteEvent} over a real socket
 * with <b>no Redis product or library</b> involved.
 */
class RedisEventBackplaneTest {

    @Test
    @Timeout(15)
    void publishOnOneReplicaIsDeliveredToASubscriberOnAnother() throws Exception {
        try (MiniRespBroker broker = new MiniRespBroker()) {
            int port = broker.port();

            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<RemoteEvent> got = new AtomicReference<>();

            RedisEventBackplane subscriber = new RedisEventBackplane("127.0.0.1", port, "events");
            subscriber.subscribe(e -> {
                got.set(e);
                received.countDown();
            });

            // Wait until the broker has the subscriber registered (pub/sub is fire-and-forget).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (broker.subscriberCount() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, broker.subscriberCount(), "subscriber should have registered with the broker");

            RedisEventBackplane publisher = new RedisEventBackplane("127.0.0.1", port, "events");
            RemoteEvent event = new RemoteEvent(RemoteEvent.Kind.WRITE, "/r1", "k1",
                    StoredValue.stringValue("v"), null, true, "clientB", "instance-A");
            publisher.publish(event);

            assertTrue(received.await(10, TimeUnit.SECONDS), "subscriber should receive the published event");
            assertEquals(event, got.get());

            subscriber.close();
            publisher.close();
        }
    }

    /** Minimal Redis-pub/sub-speaking broker for one or more channels: enough RESP to test the client. */
    private static final class MiniRespBroker implements AutoCloseable {
        private final ServerSocket server;
        private final Thread acceptor;
        private final List<OutputStream> subscribers = new CopyOnWriteArrayList<>();

        MiniRespBroker() throws IOException {
            server = new ServerSocket(0);
            acceptor = new Thread(this::acceptLoop, "mini-resp-broker");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return server.getLocalPort();
        }

        int subscriberCount() {
            return subscribers.size();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread t = new Thread(() -> handle(socket), "mini-resp-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return; // server closed
                }
            }
        }

        private void handle(Socket socket) {
            try (socket) {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                while (true) {
                    List<byte[]> cmd = readCommand(in);
                    if (cmd.isEmpty()) {
                        return;
                    }
                    String name = new String(cmd.get(0), StandardCharsets.US_ASCII).toUpperCase();
                    if (name.equals("SUBSCRIBE")) {
                        byte[] ch = cmd.get(1);
                        writeArrayHeader(out, 3);
                        writeBulk(out, "subscribe".getBytes(StandardCharsets.US_ASCII));
                        writeBulk(out, ch);
                        writeInteger(out, 1);
                        out.flush();
                        subscribers.add(out);
                    } else if (name.equals("PUBLISH")) {
                        byte[] ch = cmd.get(1);
                        byte[] payload = cmd.get(2);
                        for (OutputStream sub : subscribers) {
                            synchronized (sub) {
                                writeArrayHeader(sub, 3);
                                writeBulk(sub, "message".getBytes(StandardCharsets.US_ASCII));
                                writeBulk(sub, ch);
                                writeBulk(sub, payload);
                                sub.flush();
                            }
                        }
                        writeInteger(out, subscribers.size());
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // connection closed
            }
        }

        private static List<byte[]> readCommand(InputStream in) throws IOException {
            int type = in.read();
            if (type < 0) {
                return List.of();
            }
            if (type != '*') {
                throw new IOException("expected RESP array, got " + type);
            }
            int n = Integer.parseInt(readLine(in));
            List<byte[]> args = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int b = in.read();
                if (b != '$') {
                    throw new IOException("expected bulk string, got " + b);
                }
                int len = Integer.parseInt(readLine(in));
                byte[] buf = new byte[len];
                int off = 0;
                while (off < len) {
                    int r = in.read(buf, off, len - off);
                    if (r < 0) {
                        throw new EOFException();
                    }
                    off += r;
                }
                in.read(); // CR
                in.read(); // LF
                args.add(buf);
            }
            return args;
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\r') {
                    in.read();
                    return sb.toString();
                }
                sb.append((char) b);
            }
            throw new EOFException();
        }

        private static void writeArrayHeader(OutputStream out, int n) throws IOException {
            out.write(('*' + Integer.toString(n) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }

        private static void writeBulk(OutputStream out, byte[] payload) throws IOException {
            out.write(('$' + Integer.toString(payload.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.write('\r');
            out.write('\n');
        }

        private static void writeInteger(OutputStream out, long value) throws IOException {
            out.write((':' + Long.toString(value) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
