package com.protogemcouch.shim;

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
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.geode.DataSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RawShimServer {

    private static final byte[] HANDSHAKE_REPLY = hex(
            "3b0000000000" +
                    "4e015c04ac1600020000a02957000c67656f64652d736572766572090000a69d" +
                    "000000ee0a0057000773657276657231570001315700000000012cff00968fc5" +
                    "ac0b5bff75bfb2849de120d17e6700000001"
    );

    private static final byte[] RESPONSE_META = hex(
            "01018800040000ff000300000003e1dba9ded533"
    );

    private static final Map<String, String> STORE = new ConcurrentHashMap<>();

    static {
        STORE.put(docId("/helloWorld", "proto::seed-key"), "hello-proto-gemcouch");
    }

    public static void main(String[] args) throws Exception {
        int port = 40405;

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

            Channel ch = bootstrap.bind(port).sync().channel();
            System.out.println("RawShimServer listening on " + port);
            ch.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            workers.shutdownGracefully();
        }
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

            for (int i = 0; i < frame.getParts().size(); i++) {
                GemPart p = frame.getParts().get(i);
                String printable = new String(p.getPayload(), StandardCharsets.UTF_8);
                System.out.println("part[" + i + "] len=" + p.getLength()
                        + " type=" + String.format("0x%02x", p.getTypeCode())
                        + " text=" + printable);
            }

            switch (frame.getMessageType()) {
                case 0 -> handleGet(ctx, frame);
                case 7 -> handlePut(ctx, frame);
                case 18 -> {
                    System.out.println("CONTROL FRAME type=18 received");
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(buildSimpleAck(frame.getTransactionId())));
                }
                case 5 -> {
                    System.out.println("PING FRAME received");
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(buildSimpleAck(frame.getTransactionId())));
                }
                default -> System.out.println("IGNORED FRAME type=" + frame.getMessageType());
            }
        }

        private void handleGet(ChannelHandlerContext ctx, GemFrame frame) {
            String region = frame.getParts().size() > 0
                    ? bytesToString(frame.getParts().get(0).getPayload())
                    : "";
            String key = frame.getParts().size() > 1
                    ? bytesToString(frame.getParts().get(1).getPayload())
                    : "";

            System.out.println("GET REQUEST RECEIVED region=" + region + " key=" + key);

            String value = STORE.get(docId(region, key));
            byte[] response = (value != null)
                    ? buildGetResponse(frame.getTransactionId(), value)
                    : buildNullGetResponse(frame.getTransactionId());

            ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
        }

        private void handlePut(ChannelHandlerContext ctx, GemFrame frame) {
            String region = frame.getParts().size() > 0
                    ? bytesToString(frame.getParts().get(0).getPayload())
                    : "";

            String key = frame.getParts().size() > 3
                    ? bytesToString(frame.getParts().get(3).getPayload())
                    : "";

            String value = null;
            if (frame.getParts().size() > 5) {
                byte[] valueBytes = frame.getParts().get(5).getPayload();
                try {
                    value = geodeDeserializeString(valueBytes);
                } catch (Exception e) {
                    System.out.println("PUT value deserialize failed, falling back to printable text");
                    value = bytesToString(valueBytes);
                }
            }

            System.out.println("PUT REQUEST RECEIVED region=" + region + " key=" + key + " value=" + value);

            if (!region.isEmpty() && !key.isEmpty() && value != null) {
                STORE.put(docId(region, key), value);
            }

            ctx.writeAndFlush(Unpooled.wrappedBuffer(buildPutResponse(frame.getTransactionId())));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    private static byte[] buildGetResponse(int transactionId, String value) {
        byte[] serializedValue = geodeSerializeString(value);

        ByteBuf buf = Unpooled.buffer();

        buf.writeInt(1);              // RESPONSE
        buf.writeInt(0);              // payloadLength placeholder
        buf.writeInt(3);              // numberOfParts
        buf.writeInt(transactionId);
        buf.writeByte(0);

        int payloadStart = buf.writerIndex();

        // Part 1: value object
        buf.writeInt(serializedValue.length);
        buf.writeByte(0x01);          // OBJECT
        buf.writeBytes(serializedValue);

        // Part 2: flags/status int
        buf.writeInt(4);
        buf.writeByte(0x00);          // BYTE/int-style
        buf.writeInt(0);

        // Part 3: metadata object
        buf.writeInt(RESPONSE_META.length);
        buf.writeByte(0x01);          // OBJECT
        buf.writeBytes(RESPONSE_META);

        int payloadLength = buf.writerIndex() - payloadStart;
        buf.setInt(4, payloadLength);

        byte[] responseBytes = ByteBufUtil.getBytes(buf);
        System.out.println("GET RESPONSE HEX: " + ByteBufUtil.hexDump(responseBytes));
        return responseBytes;
    }

    private static byte[] buildNullGetResponse(int transactionId) {
        ByteBuf buf = Unpooled.buffer();

        buf.writeInt(1);              // RESPONSE
        buf.writeInt(0);              // payloadLength placeholder
        buf.writeInt(3);              // numberOfParts
        buf.writeInt(transactionId);
        buf.writeByte(0);

        int payloadStart = buf.writerIndex();

        // Part 1: null object marker
        buf.writeInt(1);
        buf.writeByte(0x01);          // OBJECT
        buf.writeByte(0x29);          // null marker

        // Part 2: flags/status int
        buf.writeInt(4);
        buf.writeByte(0x00);          // BYTE/int-style
        buf.writeInt(0);

        // Part 3: metadata object
        buf.writeInt(RESPONSE_META.length);
        buf.writeByte(0x01);          // OBJECT
        buf.writeBytes(RESPONSE_META);

        int payloadLength = buf.writerIndex() - payloadStart;
        buf.setInt(4, payloadLength);

        byte[] responseBytes = ByteBufUtil.getBytes(buf);
        System.out.println("NULL GET RESPONSE HEX: " + ByteBufUtil.hexDump(responseBytes));
        return responseBytes;
    }

    private static byte[] buildPutResponse(int transactionId) {
        ByteBuf buf = Unpooled.buffer();

        buf.writeInt(6);   // REPLY
        buf.writeInt(0);   // payloadLength placeholder
        buf.writeInt(3);   // numberOfParts
        buf.writeInt(transactionId);
        buf.writeByte(0);

        int payloadStart = buf.writerIndex();

        // PART 1: NULL old value
        buf.writeInt(1);
        buf.writeByte(0x01);
        buf.writeByte(0x29);

        // PART 2: flags
        buf.writeInt(4);
        buf.writeByte(0x00);
        buf.writeInt(4);

        // PART 3: NULL VersionTag (THIS FIXES ClassCastException)
        buf.writeInt(1);
        buf.writeByte(0x01);
        buf.writeByte(0x29);

        int payloadLength = buf.writerIndex() - payloadStart;
        buf.setInt(4, payloadLength);

        byte[] responseBytes = ByteBufUtil.getBytes(buf);
        System.out.println("PUT RESPONSE HEX: " + ByteBufUtil.hexDump(responseBytes));
        return responseBytes;
    }

    private static byte[] buildSimpleAck(int transactionId) {
        ByteBuf buf = Unpooled.buffer();

        buf.writeInt(6);
        buf.writeInt(6);
        buf.writeInt(1);
        buf.writeInt(transactionId);
        buf.writeByte(0);

        buf.writeInt(1);
        buf.writeByte(0x00);
        buf.writeByte(0x00);

        byte[] ackBytes = ByteBufUtil.getBytes(buf);
        System.out.println("ACK HEX: " + ByteBufUtil.hexDump(ackBytes));
        return ackBytes;
    }

    private static String docId(String region, String key) {
        return region + "::" + key;
    }

    private static String bytesToString(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    }

    private static byte[] geodeSerializeString(String value) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            DataSerializer.writeString(value, dos);
            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to Geode-serialize string: " + value, e);
        }
    }

    private static String geodeDeserializeString(byte[] bytes) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            DataInputStream dis = new DataInputStream(bais);
            return DataSerializer.readString(dis);
        } catch (Exception e) {
            throw new RuntimeException("Failed to Geode-deserialize string", e);
        }
    }

    private static byte[] geodeSerializeObject(Object value) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            DataSerializer.writeObject(value, dos);
            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize object", e);
        }
    }

    private static byte[] hex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
        }
        return out;
    }
}