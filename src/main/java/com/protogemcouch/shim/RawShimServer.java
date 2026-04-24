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
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemFrameDecoder;
import com.protogemcouch.wire.GemPart;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RawShimServer {

    private static final Logger log = LoggerFactory.getLogger(RawShimServer.class);

    private static final byte[] HANDSHAKE_REPLY = ByteUtils.hex(
            "3b0000000000" +
                    "4e015c04ac1600020000a02957000c67656f64652d736572766572090000a69d" +
                    "000000ee0a0057000773657276657231570001315700000000012cff00968fc5" +
                    "ac0b5bff75bfb2849de120d17e6700000001"
    );

    private static ServerConfig config;
    private static Repository repository;
    private static OpcodeRegistry opcodeRegistry;
    private static UnknownOpcodeHandler unknownOpcodeHandler;
    private static MetricsRegistry metrics;
    private static ScheduledExecutorService metricsReporter;
    private static HealthState healthState;
    private static HealthHttpServer healthHttpServer;

    public static void main(String[] args) throws Exception {
        EventLoopGroup boss = null;
        EventLoopGroup workers = null;

        healthState = new HealthState();
        healthState.markStarting();

        try {
            config = ServerConfig.fromEnv();

            log.info(StructuredLog.event(
                    "server_starting",
                    "config", config.toSafeLogString()
            ));

            StartupValidator.validate(config);
            healthState.markConfigValidated();

            healthHttpServer = new HealthHttpServer(config.getHealthPort(), healthState);
            healthHttpServer.start();

            repository = createRepository(config);
            healthState.markRepositoryConnected();

            opcodeRegistry = createOpcodeRegistry(repository);
            unknownOpcodeHandler = new UnknownOpcodeHandler();
            metrics = new MetricsRegistry();

            startMetricsReporter();

            boss = new NioEventLoopGroup(1);
            workers = new NioEventLoopGroup();

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HandshakeThenFrameHandler());
                        }
                    });

            Channel ch = bootstrap.bind(config.getShimPort()).sync().channel();
            healthState.markServerBound();

            log.info(StructuredLog.event(
                    "server_started",
                    "port", config.getShimPort(),
                    "healthPort", config.getHealthPort()
            ));

            ch.closeFuture().sync();
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
            healthState.markStopping();

            stopMetricsReporter();

            if (workers != null) {
                workers.shutdownGracefully();
            }
            if (boss != null) {
                boss.shutdownGracefully();
            }

            closeRepository(repository);

            if (healthHttpServer != null) {
                healthHttpServer.stop();
            }

            healthState.markStopped();
            log.info(StructuredLog.event("server_stopped"));
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

    static class HandshakeThenFrameHandler extends ChannelInboundHandlerAdapter {
        private boolean handshakeDone = false;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            metrics.recordConnectionOpened();
            log.info(StructuredLog.event(
                    "client_connected",
                    "remote", ctx.channel().remoteAddress()
            ));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            metrics.recordConnectionClosed();
            log.info(StructuredLog.event(
                    "client_disconnected",
                    "remote", ctx.channel().remoteAddress()
            ));
            super.channelInactive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof ByteBuf buf)) {
                super.channelRead(ctx, msg);
                return;
            }

            if (!handshakeDone) {
                byte[] inbound = ByteBufUtil.getBytes(buf);
                buf.release();

                metrics.recordHandshakeRequest();
                log.info(StructuredLog.event(
                        "handshake_request",
                        "remote", ctx.channel().remoteAddress(),
                        "hex", ByteBufUtil.hexDump(inbound)
                ));

                ctx.writeAndFlush(Unpooled.wrappedBuffer(HANDSHAKE_REPLY));
                handshakeDone = true;

                ctx.pipeline().addLast(new GemFrameDecoder());
                ctx.pipeline().addLast(new GemRequestHandler());
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

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, GemFrame frame) {
            int opcode = frame.getMessageType();
            String operation = OperationNames.nameOf(opcode);

            metrics.recordRequestStart(opcode);

            long start = System.nanoTime();

            log.info(StructuredLog.event(
                    "request_received",
                    "opcode", opcode,
                    "operation", operation,
                    "parts", frame.getNumberOfParts(),
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
                            "elapsed_ns", elapsed
                    ));
                } else {
                    metrics.recordUnknownOpcode(opcode);
                    long elapsed = System.nanoTime() - start;

                    log.warn(StructuredLog.event(
                            "unknown_opcode",
                            "opcode", opcode,
                            "operation", operation,
                            "txId", frame.getTransactionId(),
                            "elapsed_ns", elapsed
                    ));

                    unknownOpcodeHandler.handle(ctx, frame);
                }
            } catch (Exception e) {
                long elapsed = System.nanoTime() - start;
                metrics.recordRequestError(opcode, elapsed);

                log.error(StructuredLog.event(
                        "request_failed",
                        "opcode", opcode,
                        "operation", operation,
                        "txId", frame.getTransactionId(),
                        "elapsed_ns", elapsed,
                        "error", e.getMessage()
                ), e);

                throw e;
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