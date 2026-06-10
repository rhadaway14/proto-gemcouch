package com.protogemcouch.shim;

import com.protogemcouch.config.ConfigException;
import com.protogemcouch.config.ServerConfig;
import com.protogemcouch.config.StartupValidator;
import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.couchbase.RepositoryFactory;
import com.protogemcouch.health.HealthHttpServer;
import com.protogemcouch.health.HealthState;
import com.protogemcouch.observability.MetricsRegistry;
import com.protogemcouch.observability.OperationNames;
import com.protogemcouch.observability.StructuredLog;
import com.protogemcouch.ops.HandlerRegistryFactory;
import com.protogemcouch.ops.OpcodeRegistry;
import com.protogemcouch.ops.OperationHandler;
import com.protogemcouch.ops.UnknownOpcodeHandler;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.FrameLimits;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemFrameDecoder;
import com.protogemcouch.wire.GemPart;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.netty.util.AttributeKey;

public class RawShimServer {

    private static final Logger log = LoggerFactory.getLogger(RawShimServer.class);

    private static final byte[] HANDSHAKE_REPLY = ByteUtils.hex(
            "3b0000000000" +
                    "4e015c04ac1600020000a02957000c67656f64652d736572766572090000a69d" +
                    "000000ee0a0057000773657276657231570001315700000000012cff00968fc5" +
                    "ac0b5bff75bfb2849de120d17e6700000001"
    );

    // Communication-mode bytes of the first byte of a client connection (org.apache.geode...
    // CommunicationMode): 100 = normal ops (handled like today), 101 = the server->client
    // subscription feed connection, 107 = the subscription-control (register-interest) connection.
    private static final int MODE_CLIENT_TO_SERVER = 100;
    private static final int MODE_PRIMARY_SERVER_TO_CLIENT = 101;
    private static final int MODE_CLIENT_TO_SERVER_FOR_QUEUE = 107;

    // Reply on a subscription feed (mode 101): 0x69 = 105 (SuccessfulServerToClient) + a 14-byte
    // server-queue-status body, then the server pushes events down this channel. Captured from a real
    // Geode 1.15 server (see docs/SUBSCRIPTIONS.md / tools/SubscriptionCapture).
    private static final byte[] FEED_HANDSHAKE_REPLY = ByteUtils.hex("69000000000000000000000000ea60");

    private static final AttributeKey<Integer> CURRENT_OPCODE =
            AttributeKey.valueOf("protogemcouch.currentOpcode");

    /**
     * Connection lifecycle accounting + logging. Lives on {@link ConnectionTrackingHandler} (the
     * first, permanent pipeline handler) so closes are always counted and limiter slots released —
     * the handshake handler cannot own this because it removes itself after the handshake.
     */
    private static final ConnectionTrackingListener CONNECTION_TRACKING_LISTENER =
            new ConnectionTrackingListener() {
                @Override
                public void onConnectionOpened(SocketAddress remote, int activeConnections) {
                    metrics.recordConnectionOpened();
                    log.info(StructuredLog.event(
                            "client_connected",
                            "remote", remote,
                            "activeConnections", activeConnections
                    ));
                }

                @Override
                public void onConnectionClosed(SocketAddress remote) {
                    metrics.recordConnectionClosed();
                    log.info(StructuredLog.event(
                            "client_disconnected",
                            "remote", remote
                    ));
                }

                @Override
                public void onConnectionRejected(SocketAddress remote, int maxConnections) {
                    metrics.recordConnectionRejected();
                    log.warn(StructuredLog.event(
                            "connection_rejected",
                            "reason", "max_connections_exceeded",
                            "maxConnections", maxConnections,
                            "remote", remote
                    ));
                }
            };

    private static ServerConfig config;
    private static FrameLimits frameLimits;
    private static ErrorResponsePolicy errorResponsePolicy;
    private static EventExecutorGroup handlerExecutorGroup;
    private static ConnectionLimits connectionLimits;
    private static ConnectionLimiter connectionLimiter;
    private static SslContext shimSslContext;
    private static Repository repository;
    private static OpcodeRegistry opcodeRegistry;
    private static com.protogemcouch.subscription.SubscriptionRegistry subscriptions;
    private static UnknownOpcodeHandler unknownOpcodeHandler;
    private static MetricsRegistry metrics;
    private static ScheduledExecutorService metricsReporter;
    private static HealthState healthState;
    private static HealthHttpServer healthHttpServer;
    private static EventLoopGroup bossGroup;
    private static EventLoopGroup workerGroup;
    private static Channel serverChannel;

    // Ensures the shutdown sequence runs exactly once whether it is triggered by a SIGTERM
    // shutdown hook (the normal path under Kubernetes/Docker) or by the main thread unwinding.
    private static final java.util.concurrent.atomic.AtomicBoolean SHUTDOWN_STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        healthState = new HealthState();
        healthState.markStarting();

        // Run the graceful drain on SIGTERM, which is how Kubernetes and `docker stop` ask the
        // process to exit. Without this, the cleanup in the finally block below would not run on a
        // signal (the JVM exits straight after hooks), so in-flight requests and connections would
        // be dropped instead of drained. The hook closes the listener, which unblocks the main
        // thread's closeFuture().sync() and lets it converge on the same idempotent shutdown.
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> gracefulShutdown("signal"), "protogemcouch-shutdown"));

        try {
            config = ServerConfig.fromEnv();

            log.info(StructuredLog.event(
                    "server_starting",
                    "config", config.toSafeLogString()
            ));

            StartupValidator.validate(config);
            healthState.markConfigValidated();

            metrics = new MetricsRegistry();

            frameLimits = FrameLimits.fromEnv();
            log.info(StructuredLog.event(
                    "frame_limits_configured",
                    "maxFrameBytes", frameLimits.maxFrameBytes(),
                    "maxParts", frameLimits.maxParts()
            ));

            errorResponsePolicy = createErrorResponsePolicy();
            log.info(StructuredLog.event(
                    "error_response_policy_configured",
                    "policy", errorResponsePolicy.getClass().getSimpleName()
            ));

            TlsConfig tlsConfig = TlsConfig.fromEnv();
            if (tlsConfig.enabled()) {
                shimSslContext = tlsConfig.buildServerSslContext();
            }
            log.info(StructuredLog.event(
                    "tls_configured",
                    "geodeListenerTls", tlsConfig.enabled(),
                    "clientAuth", tlsConfig.requireClientAuth() ? "require" : "none",
                    "healthTls", tlsConfig.healthTlsEnabled()
            ));

            javax.net.ssl.SSLContext healthSslContext =
                    tlsConfig.healthTlsEnabled() ? tlsConfig.buildJdkSslContext() : null;
            healthHttpServer = new HealthHttpServer(
                    config.getHealthPort(), healthState, metrics,
                    System.getenv("HEALTH_BIND_ADDRESS"), healthSslContext);
            healthHttpServer.start();

            repository = createRepository(config);
            healthState.markRepositoryConnected();

            subscriptions = new com.protogemcouch.subscription.SubscriptionRegistry();
            opcodeRegistry = HandlerRegistryFactory.create(repository, subscriptions);
            unknownOpcodeHandler = new UnknownOpcodeHandler();

            startMetricsReporter();

            HandlerExecutorConfig handlerExecutorConfig = HandlerExecutorConfig.fromEnv();
            handlerExecutorGroup = new DefaultEventExecutorGroup(
                    handlerExecutorConfig.threads(),
                    new DefaultThreadFactory("protogemcouch-handler"),
                    handlerExecutorConfig.maxPendingTasks(),
                    new SheddingRejectedExecutionHandler(RawShimServer::onRequestShed));
            log.info(StructuredLog.event(
                    "handler_executor_configured",
                    "threads", handlerExecutorConfig.threads(),
                    "maxPendingTasks", handlerExecutorConfig.maxPendingTasks()
            ));

            connectionLimits = ConnectionLimits.fromEnv();
            connectionLimiter = new ConnectionLimiter(connectionLimits.maxConnections());
            log.info(StructuredLog.event(
                    "connection_limits_configured",
                    "idleTimeoutSeconds", connectionLimits.idleTimeoutSeconds(),
                    "maxConnections", connectionLimits.maxConnections(),
                    "firstRequestTimeoutSeconds", connectionLimits.firstRequestTimeoutSeconds()
            ));

            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // TLS termination first, so everything downstream sees plaintext.
                            if (shimSslContext != null) {
                                ch.pipeline().addLast(shimSslContext.newHandler(ch.alloc()));
                            }
                            // Tracks the connection for its whole lifetime
                            // (open/close accounting + max-connections cap).
                            ch.pipeline().addLast(new ConnectionTrackingHandler(
                                    connectionLimiter, CONNECTION_TRACKING_LISTENER));
                            if (connectionLimits.idleReapingEnabled()) {
                                // allIdle: fire when neither a read nor a write has happened for the timeout.
                                ch.pipeline().addLast(new IdleStateHandler(
                                        0, 0, connectionLimits.idleTimeoutSeconds(), TimeUnit.SECONDS));
                                ch.pipeline().addLast(new IdleConnectionHandler(RawShimServer::onIdleConnection));
                            }
                            if (connectionLimits.firstRequestDeadlineEnabled()) {
                                ch.pipeline().addLast(new FirstRequestDeadlineHandler(
                                        connectionLimits.firstRequestTimeoutSeconds() * 1000L,
                                        RawShimServer::onFirstRequestTimeout));
                            }
                            ch.pipeline().addLast(new HandshakeThenFrameHandler());
                        }
                    });

            serverChannel = bootstrap.bind(config.getShimPort()).sync().channel();
            healthState.markServerBound();

            log.info(StructuredLog.event(
                    "server_started",
                    "port", config.getShimPort(),
                    "healthPort", config.getHealthPort(),
                    "metricsJsonPath", "/metrics/json"
            ));

            serverChannel.closeFuture().sync();
        } catch (ConfigException e) {
            healthState.markStartupFailed(e.getMessage());
            log.error(StructuredLog.event(
                    "server_startup_config_failure",
                    "error", e.getMessage()
            ), e);
            throw e;
        } catch (Exception e) {
            healthState.markStartupFailed(e.getMessage());
            log.error(StructuredLog.event(
                    "server_startup_failure",
                    "error", e.getMessage()
            ), e);
            throw e;
        } finally {
            gracefulShutdown("main-thread-exit");
        }
    }

    /**
     * Drains and releases every server resource exactly once. Safe to call from both the SIGTERM
     * shutdown hook and the main thread's {@code finally}; whichever calls first runs the body and
     * the other returns immediately. The JVM blocks on the shutdown-hook thread until this returns,
     * so the drain completes before exit.
     *
     * <p>Order: mark not-ready (so orchestrators stop routing) → stop new accepts by closing the
     * listener → drain in-flight request handlers and event loops with a bounded grace period →
     * close the Couchbase repository → stop the health server.
     */
    static void gracefulShutdown(String trigger) {
        if (!SHUTDOWN_STARTED.compareAndSet(false, true)) {
            return;
        }
        log.info(StructuredLog.event("server_stopping", "trigger", trigger));

        if (healthState != null) {
            healthState.markStopping();
        }

        stopMetricsReporter();

        // Stop accepting new connections first. Closing the listener also unblocks
        // serverChannel.closeFuture().sync() on the main thread.
        if (serverChannel != null) {
            try {
                serverChannel.close().await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Kick off graceful drains concurrently (each returns immediately), then await them. The
        // quiet period lets in-flight requests finish; the timeout bounds the wait so we stay
        // within the container's termination grace period.
        if (handlerExecutorGroup != null) {
            handlerExecutorGroup.shutdownGracefully(2, 8, TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(2, 8, TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS);
        }
        awaitQuietly(handlerExecutorGroup, 11);
        awaitQuietly(workerGroup, 11);
        awaitQuietly(bossGroup, 6);

        closeRepository(repository);

        if (healthHttpServer != null) {
            healthHttpServer.stop();
        }

        if (healthState != null) {
            healthState.markStopped();
        }
        log.info(StructuredLog.event("server_stopped", "trigger", trigger));
    }

    private static void awaitQuietly(EventExecutorGroup group, long seconds) {
        if (group == null) {
            return;
        }
        try {
            group.awaitTermination(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static Repository createRepository(ServerConfig config) {
        return RepositoryFactory.create(config);
    }

    static void closeRepository(Repository repository) {
        RepositoryFactory.close(repository);
    }

    static OpcodeRegistry createOpcodeRegistry(Repository repository) {
        return HandlerRegistryFactory.create(repository);
    }

    private static void startMetricsReporter() {
        metricsReporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-reporter");
            t.setDaemon(true);
            return t;
        });

        metricsReporter.scheduleAtFixedRate(() -> {
            try {
                for (String line : metrics.snapshotLines()) {
                    log.info(line);
                }
            } catch (Exception e) {
                log.error(StructuredLog.event(
                        "metrics_report_failed",
                        "error", e.getMessage()
                ), e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private static void stopMetricsReporter() {
        if (metricsReporter != null) {
            metricsReporter.shutdownNow();
        }
    }

    /**
     * Select the error-response policy from the {@code ERROR_RESPONSE_MODE} environment variable.
     * Defaults to replying with a Geode EXCEPTION frame and keeping the connection open (validated
     * against a live Geode client; see ProtoGemCouchExceptionResponseIntegrationTest). Set
     * {@code ERROR_RESPONSE_MODE=close} to opt out and drop the connection on failure instead.
     */
    static ErrorResponsePolicy createErrorResponsePolicy() {
        String mode = System.getenv("ERROR_RESPONSE_MODE");
        if (mode != null && mode.trim().equalsIgnoreCase("close")) {
            return new CloseConnectionErrorPolicy();
        }
        return new ExceptionFrameErrorPolicy();
    }

    /**
     * Invoked by {@link IdleConnectionHandler} when a connection is closed for being idle past the
     * configured timeout. Records the event in metrics and logs it.
     */
    static void onIdleConnection(java.net.SocketAddress remote) {
        if (metrics != null) {
            metrics.recordIdleConnectionClosed();
        }
        log.info(StructuredLog.event(
                "connection_idle_closed",
                "remote", remote,
                "idleTimeoutSeconds", connectionLimits == null ? -1 : connectionLimits.idleTimeoutSeconds()
        ));
    }

    /**
     * Invoked by {@link FirstRequestDeadlineHandler} when a connection is closed for not completing
     * its first request within the deadline (a slowloris-style guard).
     */
    static void onFirstRequestTimeout(java.net.SocketAddress remote) {
        if (metrics != null) {
            metrics.recordFirstRequestTimeout();
        }
        log.warn(StructuredLog.event(
                "connection_first_request_timeout",
                "remote", remote,
                "firstRequestTimeoutSeconds", connectionLimits == null ? -1 : connectionLimits.firstRequestTimeoutSeconds()
        ));
    }

    /**
     * Invoked by {@link SheddingRejectedExecutionHandler} when a request is shed because the handler
     * queue is full (load shedding under sustained overload).
     */
    static void onRequestShed() {
        if (metrics != null) {
            metrics.recordRequestShed();
        }
        log.warn(StructuredLog.event("request_shed", "reason", "handler_queue_full"));
    }

    /**
     * Invoked by {@link GemFrameDecoder} when an inbound frame is rejected as malformed or oversized.
     * Records the event in metrics and logs it; the decoder closes the offending connection.
     */
    static void onMalformedFrame(String reason, java.net.SocketAddress remote, long offendingValue) {
        if (metrics != null) {
            metrics.recordMalformedFrame();
        }
        log.warn(StructuredLog.event(
                "malformed_frame",
                "reason", reason,
                "remote", remote,
                "offendingValue", offendingValue
        ));
    }

    static class HandshakeThenFrameHandler extends ChannelInboundHandlerAdapter {
        private boolean handshakeDone = false;

        // Connection open/close accounting and the max-connections cap live on
        // ConnectionTrackingHandler, which stays in the pipeline for the connection's lifetime.
        // This handler removes itself after the handshake, so it must not own that accounting.

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof ByteBuf buf)) {
                super.channelRead(ctx, msg);
                return;
            }

            if (!handshakeDone) {
                byte[] inbound = ByteBufUtil.getBytes(buf);
                buf.release();

                int mode = inbound.length > 0 ? (inbound[0] & 0xff) : MODE_CLIENT_TO_SERVER;
                // Stable client identity from the ClientProxyMembershipID bytes (identical across a
                // client's op/control/feed connections; the mode byte + readTimeout before offset 10
                // differ). Used for subscription self-event suppression.
                if (inbound.length > 10) {
                    String clientId = ByteBufUtil.hexDump(inbound, 10, inbound.length - 10);
                    ctx.channel().attr(com.protogemcouch.subscription.SubscriptionRegistry.CLIENT_ID).set(clientId);
                }
                metrics.recordHandshakeRequest();
                log.info(StructuredLog.event(
                        "handshake_request",
                        "remote", ctx.channel().remoteAddress(),
                        "mode", mode,
                        "hex", ByteBufUtil.hexDump(inbound)
                ));

                // Subscription feed connection (mode 101): reply with the SuccessfulServerToClient
                // handshake, retain the channel as an event feed, and keep it open for server->client
                // push. It does not run the request/response op pipeline; inbound bytes (acks) are
                // drained by this handler's pass-through. Cancel the first-request deadline since a
                // feed never sends a normal request.
                if (mode == MODE_PRIMARY_SERVER_TO_CLIENT) {
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(FEED_HANDSHAKE_REPLY));
                    handshakeDone = true;
                    subscriptions.addFeed(ctx.channel());
                    ctx.channel().pipeline().fireUserEventTriggered(FirstRequestCompletedEvent.INSTANCE);
                    log.info(StructuredLog.event(
                            "subscription_feed_established",
                            "remote", ctx.channel().remoteAddress(),
                            "feeds", subscriptions.feedCount()));
                    return;
                }

                // Op connection (mode 100) and subscription-control connection (mode 107) share the
                // same server handshake reply and request/response pipeline; the control connection
                // simply also carries REGISTER_INTEREST requests.
                ctx.writeAndFlush(Unpooled.wrappedBuffer(HANDSHAKE_REPLY));
                handshakeDone = true;

                ctx.pipeline().addLast(new GemFrameDecoder(frameLimits, RawShimServer::onMalformedFrame));
                ctx.pipeline().addLast(new ResponseByteMetricsHandler());
                /*
                 * Run the request handler on a dedicated executor group, not the Netty event loop.
                 * Handlers make blocking Couchbase calls, so keeping them off the I/O loop prevents
                 * one slow backend call from stalling every other connection on the same loop.
                 */
                ctx.pipeline().addLast(handlerExecutorGroup, new GemRequestHandler());
                ctx.pipeline().remove(this);
                return;
            }

            super.channelRead(ctx, msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error(StructuredLog.event(
                    "handshake_handler_error",
                    "remote", ctx.channel().remoteAddress(),
                    "error", cause.getMessage()
            ), cause);
            ctx.close();
        }
    }

    static class GemRequestHandler extends SimpleChannelInboundHandler<GemFrame> {

        private boolean firstRequestSignaled = false;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, GemFrame frame) throws Exception {
            int opcode = frame.getMessageType();
            String operation = OperationNames.nameOf(opcode);

            // Signal (once) that this connection completed a real request, cancelling the
            // first-request deadline. Fired from the pipeline head so the deadline handler (near the
            // head, upstream of the decoder) receives it; Netty marshals it onto the event loop.
            if (!firstRequestSignaled) {
                firstRequestSignaled = true;
                ctx.channel().pipeline().fireUserEventTriggered(FirstRequestCompletedEvent.INSTANCE);
            }

            metrics.recordRequestStart(opcode);
            long requestBytes = estimateRequestBytes(frame);
            metrics.recordRequestBytes(opcode, requestBytes);

            /*
             * Individual OperationHandler implementations write responses directly through the
             * ChannelHandlerContext. Store the current opcode on the channel so the outbound
             * response-byte recorder can attribute the written ByteBuf size to the operation that
             * produced it.
             *
             * The handler runs on a dedicated executor (not the event loop), so writeAndFlush is
             * asynchronous: the outbound recorder reads this attribute later, on the event loop. We
             * therefore do NOT clear it after the request — clearing could null it before the write
             * is processed. Leaving the last opcode set is correct because the Geode client is
             * synchronous per connection (it waits for each response before sending the next
             * request), so the next request overwrites it only after this response has been sent.
             */
            ctx.channel().attr(CURRENT_OPCODE).set(opcode);

            long start = System.nanoTime();

            log.info(StructuredLog.event(
                    "request_received",
                    "opcode", opcode,
                    "operation", operation,
                    "parts", frame.getNumberOfParts(),
                    "request_bytes", requestBytes,
                    "txId", frame.getTransactionId(),
                    "remote", ctx.channel().remoteAddress()
            ));

            dumpParts("part", frame);

            OperationHandler handler = opcodeRegistry.get(opcode);

            try {
                if (handler != null) {
                    handler.handle(ctx, frame);
                    long elapsed = System.nanoTime() - start;
                    metrics.recordRequestSuccess(opcode, elapsed);

                    log.info(StructuredLog.event(
                            "request_completed",
                            "opcode", opcode,
                            "operation", operation,
                            "txId", frame.getTransactionId(),
                            "elapsed_ns", elapsed,
                            "request_bytes", requestBytes
                    ));
                } else {
                    metrics.recordUnknownOpcode(opcode);
                    long elapsed = System.nanoTime() - start;

                    log.warn(StructuredLog.event(
                            "unknown_opcode",
                            "opcode", opcode,
                            "operation", operation,
                            "txId", frame.getTransactionId(),
                            "elapsed_ns", elapsed,
                            "request_bytes", requestBytes
                    ));

                    unknownOpcodeHandler.handle(ctx, frame);
                }
            } catch (Exception e) {
                long elapsed = System.nanoTime() - start;
                metrics.recordRequestError(opcode, elapsed, e);

                log.error(StructuredLog.event(
                        "request_failed",
                        "opcode", opcode,
                        "operation", operation,
                        "txId", frame.getTransactionId(),
                        "elapsed_ns", elapsed,
                        "request_bytes", requestBytes,
                        "error", e.getMessage()
                ), e);

                /*
                 * Centralized failure seam: the metric and structured log are recorded above, then
                 * the configured policy decides the client-facing response (close by default, or a
                 * Geode EXCEPTION frame when opted in). We do not rethrow, so this is the single
                 * place that owns post-decode operation-failure behavior.
                 */
                errorResponsePolicy.onFailure(ctx, opcode, frame.getTransactionId(), e);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error(StructuredLog.event(
                    "request_handler_error",
                    "remote", ctx.channel().remoteAddress(),
                    "error", cause.getMessage()
            ), cause);
            ctx.close();
        }
    }

    static class ResponseByteMetricsHandler extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Integer opcode = ctx.channel().attr(CURRENT_OPCODE).get();
            long responseBytes = estimateResponseBytes(msg);

            if (opcode != null && responseBytes >= 0) {
                metrics.recordResponseBytes(opcode, responseBytes);

                log.debug(StructuredLog.event(
                        "response_bytes_recorded",
                        "opcode", opcode,
                        "operation", OperationNames.nameOf(opcode),
                        "response_bytes", responseBytes,
                        "remote", ctx.channel().remoteAddress()
                ));
            }

            super.write(ctx, msg, promise);
        }
    }

    private static long estimateResponseBytes(Object msg) {
        if (msg instanceof ByteBuf byteBuf) {
            return byteBuf.readableBytes();
        }

        if (msg instanceof ByteBufHolder holder) {
            return holder.content().readableBytes();
        }

        if (msg instanceof byte[] bytes) {
            return bytes.length;
        }

        return -1L;
    }

    private static long estimateRequestBytes(GemFrame frame) {
        /*
         * Estimate the decoded GemFire request frame size.
         *
         * GemFrameDecoder has already consumed the raw Netty bytes by this point, so
         * this metric approximates the wire frame size from the decoded header and
         * parts. It is intended for trend/correlation analysis, not exact packet
         * accounting.
         *
         * Message header:
         *   int messageType
         *   int messageLength
         *   int numberOfParts
         *   int transactionId
         *   byte earlyAck
         *
         * Part header:
         *   int length
         *   byte typeCode
         */
        long total = 17L;

        for (GemPart part : frame.getParts()) {
            total += 5L;
            total += part.getLength();
        }

        return total;
    }

    private static void dumpParts(String prefix, GemFrame frame) {
        for (int i = 0; i < frame.getParts().size(); i++) {
            GemPart p = frame.getParts().get(i);
            log.debug(StructuredLog.event(
                    "frame_part",
                    "prefix", prefix,
                    "index", i,
                    "length", p.getLength(),
                    "type", String.format("0x%02x", p.getTypeCode()),
                    "hex", ByteBufUtil.hexDump(p.getPayload()),
                    "text", ByteUtils.bytesToString(p.getPayload())
            ));
        }
    }
}
