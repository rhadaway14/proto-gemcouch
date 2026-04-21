package com.protogemcouch.shim;

import com.protogemcouch.config.ServerConfig;
import com.protogemcouch.couchbase.CouchbaseRepository;
import com.protogemcouch.ops.ContainsHandler;
import com.protogemcouch.ops.GetAllHandler;
import com.protogemcouch.ops.GetClientPartitionAttributesHandler;
import com.protogemcouch.ops.GetHandler;
import com.protogemcouch.ops.KeySetOnServerHandler;
import com.protogemcouch.ops.OpcodeRegistry;
import com.protogemcouch.ops.OperationHandler;
import com.protogemcouch.ops.PutAllHandler;
import com.protogemcouch.ops.PutHandler;
import com.protogemcouch.ops.RemoveHandler;
import com.protogemcouch.ops.SimpleAckHandler;
import com.protogemcouch.ops.SizeOnServerHandler;
import com.protogemcouch.ops.UnknownOpcodeHandler;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemFrameDecoder;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.MessageTypes;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class RawShimServer {

    private static final byte[] HANDSHAKE_REPLY = ByteUtils.hex(
            "3b0000000000" +
                    "4e015c04ac1600020000a02957000c67656f64652d736572766572090000a69d" +
                    "000000ee0a0057000773657276657231570001315700000000012cff00968fc5" +
                    "ac0b5bff75bfb2849de120d17e6700000001"
    );

    private static ServerConfig config;
    private static CouchbaseRepository repository;
    private static OpcodeRegistry opcodeRegistry;
    private static UnknownOpcodeHandler unknownOpcodeHandler;

    public static void main(String[] args) throws Exception {
        config = ServerConfig.fromEnv();
        repository = new CouchbaseRepository(config);
        repository.connect();

        opcodeRegistry = new OpcodeRegistry();
        unknownOpcodeHandler = new UnknownOpcodeHandler();

        registerHandlers();

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup workers = new NioEventLoopGroup();

        try {
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
            System.out.println("RawShimServer listening on " + config.getShimPort());
            ch.closeFuture().sync();
        } finally {
            workers.shutdownGracefully();
            boss.shutdownGracefully();
            repository.disconnect();
        }
    }

    private static void registerHandlers() {
        opcodeRegistry.register(MessageTypes.GET, new GetHandler(repository));
        opcodeRegistry.register(MessageTypes.PUT, new PutHandler(repository));
        opcodeRegistry.register(MessageTypes.REMOVE, new RemoveHandler(repository));
        opcodeRegistry.register(MessageTypes.CONTAINS_KEY, new ContainsHandler(repository));
        opcodeRegistry.register(MessageTypes.KEY_SET, new KeySetOnServerHandler(repository));
        opcodeRegistry.register(MessageTypes.PUT_ALL, new PutAllHandler(repository));
        opcodeRegistry.register(MessageTypes.GET_CLIENT_PARTITION_ATTRIBUTES, new GetClientPartitionAttributesHandler());
        opcodeRegistry.register(MessageTypes.SIZE, new SizeOnServerHandler(repository));
        opcodeRegistry.register(MessageTypes.GET_ALL_70, new GetAllHandler(repository));
        opcodeRegistry.register(MessageTypes.CONTROL, new SimpleAckHandler("CONTROL FRAME type=18"));
        opcodeRegistry.register(MessageTypes.PING, new SimpleAckHandler("PING FRAME"));
    }

    static class HandshakeThenFrameHandler extends ChannelInboundHandlerAdapter {
        private boolean handshakeDone = false;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            System.out.println("CLIENT CONNECTED: " + ctx.channel().remoteAddress());
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof ByteBuf buf)) {
                super.channelRead(ctx, msg);
                return;
            }

            if (!handshakeDone) {
                byte[] inbound = ByteBufUtil.getBytes(buf);
                System.out.println("HANDSHAKE REQ HEX: " + ByteBufUtil.hexDump(inbound));
                buf.release();

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
            cause.printStackTrace();
            ctx.close();
        }
    }

    static class GemRequestHandler extends SimpleChannelInboundHandler<GemFrame> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, GemFrame frame) {
            System.out.println("FRAME type=" + frame.getMessageType()
                    + " parts=" + frame.getNumberOfParts()
                    + " txId=" + frame.getTransactionId());

            dumpParts("part", frame);

            OperationHandler handler = opcodeRegistry.get(frame.getMessageType());
            if (handler != null) {
                handler.handle(ctx, frame);
            } else {
                unknownOpcodeHandler.handle(ctx, frame);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    private static void dumpParts(String prefix, GemFrame frame) {
        for (int i = 0; i < frame.getParts().size(); i++) {
            GemPart p = frame.getParts().get(i);
            System.out.println(prefix + " part[" + i + "] len=" + p.getLength()
                    + " type=" + String.format("0x%02x", p.getTypeCode())
                    + " hex=" + ByteBufUtil.hexDump(p.getPayload())
                    + " text=" + ByteUtils.bytesToString(p.getPayload()));
        }
    }
}