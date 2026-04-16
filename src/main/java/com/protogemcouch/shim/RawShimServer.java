package com.protogemcouch.shim;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
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
import java.time.Duration;

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

    private static final int SHIM_PORT = 40405;

    private static final String CB_CONNSTR = envOrDefault("CB_CONNSTR", "couchbase://127.0.0.1");
    private static final String CB_USERNAME = envOrDefault("CB_USERNAME", "Administrator");
    private static final String CB_PASSWORD = envOrDefault("CB_PASSWORD", "password");
    private static final String CB_BUCKET = envOrDefault("CB_BUCKET", "test");
    private static final String CB_SCOPE = envOrDefault("CB_SCOPE", "_default");
    private static final String CB_COLLECTION = envOrDefault("CB_COLLECTION", "_default");

    private static Cluster cluster;
    private static Bucket bucket;
    private static Scope scope;
    private static Collection collection;

    public static void main(String[] args) throws Exception {
        initCouchbase();

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

            Channel ch = bootstrap.bind(SHIM_PORT).sync().channel();
            System.out.println("RawShimServer listening on " + SHIM_PORT);
            ch.closeFuture().sync();
        } finally {
            workers.shutdownGracefully();
            boss.shutdownGracefully();
            closeCouchbase();
        }
    }

    private static void initCouchbase() {
        System.out.println("Connecting to Couchbase...");
        System.out.println("  connstr    = " + CB_CONNSTR);
        System.out.println("  bucket     = " + CB_BUCKET);
        System.out.println("  scope      = " + CB_SCOPE);
        System.out.println("  collection = " + CB_COLLECTION);

        ClusterEnvironment env = ClusterEnvironment.builder()
                .timeoutConfig(tc -> tc
                        .connectTimeout(Duration.ofSeconds(10))
                        .kvTimeout(Duration.ofSeconds(5)))
                .build();

        cluster = Cluster.connect(
                CB_CONNSTR,
                com.couchbase.client.java.ClusterOptions.clusterOptions(CB_USERNAME, CB_PASSWORD)
                        .environment(env)
        );

        bucket = cluster.bucket(CB_BUCKET);
        bucket.waitUntilReady(Duration.ofSeconds(15));
        scope = bucket.scope(CB_SCOPE);
        collection = scope.collection(CB_COLLECTION);

        System.out.println("Couchbase connected.");
    }

    private static void closeCouchbase() {
        try {
            if (cluster != null) {
                cluster.disconnect();
            }
        } catch (Exception ignored) {
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
                case 9 -> handleRemove(ctx, frame);
                case 18 -> {
                    System.out.println("CONTROL FRAME type=18 received");
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(buildSimpleAck(frame.getTransactionId())));
                }
                case 5 -> {
                    System.out.println("PING FRAME received");
                    ctx.writeAndFlush(Unpooled.wrappedBuffer(buildSimpleAck(frame.getTransactionId())));
                }
                default -> {
                    System.out.println("UNKNOWN FRAME TYPE: " + frame.getMessageType());
                    for (int i = 0; i < frame.getParts().size(); i++) {
                        GemPart p = frame.getParts().get(i);
                        System.out.println("unknown part[" + i + "] len=" + p.getLength()
                                + " type=" + String.format("0x%02x", p.getTypeCode())
                                + " hex=" + ByteBufUtil.hexDump(p.getPayload())
                                + " text=" + bytesToString(p.getPayload()));
                    }
                }
            }
        }

        private void handleGet(ChannelHandlerContext ctx, GemFrame frame) {
            String region = frame.getParts().size() > 0
                    ? bytesToString(frame.getParts().get(0).getPayload())
                    : "";
            String key = frame.getParts().size() > 1
                    ? bytesToString(frame.getParts().get(1).getPayload())
                    : "";

            String docId = docId(region, key);
            System.out.println("GET REQUEST RECEIVED region=" + region + " key=" + key + " docId=" + docId);

            String value = cbGet(docId);

            byte[] response = (value != null)
                    ? buildGetResponse(frame.getTransactionId(), value)
                    : buildNullGetResponse(frame.getTransactionId());

            ctx.writeAndFlush(Unpooled.wrappedBuffer(response));
        }

        private void handlePut(ChannelHandlerContext ctx, GemFrame frame) {
            String region = null;
            String key = null;
            String value = null;

            for (GemPart part : frame.getParts()) {
                byte[] payload = part.getPayload();

                System.out.println("PUT PART HEX: " + ByteBufUtil.hexDump(payload));

                try {
                    String candidate = geodeDeserializeString(payload);

                    if (candidate != null && !candidate.isBlank()) {
                        System.out.println("DESERIALIZED STRING: " + candidate);

                        if (candidate.startsWith("proto::")) {
                            key = candidate;
                        } else if (!candidate.startsWith("/") && !candidate.startsWith("proto::")) {
                            value = candidate;
                        }
                    }
                } catch (Exception ignored) {
                }

                String text = new String(payload, StandardCharsets.UTF_8)
                        .replace("\u0000", "")
                        .trim();

                if (!text.isBlank()) {
                    System.out.println("RAW STRING: " + text);

                    if (region == null && text.startsWith("/")) {
                        region = text;
                    } else if (key == null && text.startsWith("proto::")) {
                        key = text;
                    } else if (value == null && text.contains("value")) {
                        value = text;
                    }
                }
            }

            System.out.println("PUT PARSED:");
            System.out.println("  region=" + region);
            System.out.println("  key=" + key);
            System.out.println("  value=" + value);

            if (region != null && key != null && value != null) {
                String docId = docId(region, key);
                cbPut(docId, value);
                System.out.println("PUT STORED docId=" + docId);
            } else {
                System.out.println("FAILED TO PARSE PUT");
            }

            ctx.writeAndFlush(Unpooled.wrappedBuffer(buildPutResponse(frame.getTransactionId())));
        }

        private void handleRemove(ChannelHandlerContext ctx, GemFrame frame) {
            String region = frame.getParts().size() > 0
                    ? bytesToString(frame.getParts().get(0).getPayload())
                    : "";
            String key = frame.getParts().size() > 1
                    ? bytesToString(frame.getParts().get(1).getPayload())
                    : "";

            String docId = docId(region, key);
            System.out.println("REMOVE REQUEST RECEIVED region=" + region + " key=" + key + " docId=" + docId);

            cbRemove(docId);

            ctx.writeAndFlush(Unpooled.wrappedBuffer(buildRemoveResponse(frame.getTransactionId())));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    private static String cbGet(String docId) {
        try {
            GetResult result = collection.get(docId);
            JsonObject content = result.contentAsObject();
            System.out.println("CB GET OK docId=" + docId + " content=" + content);
            if (content.containsKey("value")) {
                return content.getString("value");
            }
            return null;
        } catch (Exception e) {
            System.out.println("CB GET MISS/ERROR docId=" + docId + " msg=" + e.getMessage());
            return null;
        }
    }

    private static void cbPut(String docId, String value) {
        JsonObject body = JsonObject.create()
                .put("value", value);

        collection.upsert(docId, body);
        System.out.println("CB UPSERT OK docId=" + docId + " body=" + body);
    }

    private static void cbRemove(String docId) {
        try {
            collection.remove(docId);
            System.out.println("CB REMOVE OK docId=" + docId);
        } catch (Exception e) {
            System.out.println("CB REMOVE MISS/ERROR docId=" + docId + " msg=" + e.getMessage());
        }
    }

    private static byte[] buildGetResponse(int transactionId, String value) {
        byte[] serializedValue = geodeSerializeString(value);

        ByteBuf buf = Unpooled.buffer();

        buf.writeInt(1);
        buf.writeInt(0);
        buf.writeInt(3);
        buf.writeInt(transactionId);
        buf.writeByte(0);

        int payloadStart = buf.writerIndex();

        buf.writeInt(serializedValue.length);
        buf.writeByte(0x01);
        buf.writeBytes(serializedValue);

        buf.writeInt(4);
        buf.writeByte(0x00);
        buf.writeInt(0);

        buf.writeInt(RESPONSE_META.length);
        buf.writeByte(0x01);
        buf.writeBytes(RESPONSE_META);

        int payloadLength = buf.writerIndex() - payloadStart;
        buf.setInt(4, payloadLength);

        byte[] responseBytes = ByteBufUtil.getBytes(buf);
        System.out.println("GET RESPONSE HEX: " + ByteBufUtil.hexDump(responseBytes));
        return responseBytes;
    }

    private static byte[] buildNullGetResponse(int transactionId) {
        ByteBuf buf = Unpooled.buffer();

        buf.writeInt(1);
        buf.writeInt(0);
        buf.writeInt(3);
        buf.writeInt(transactionId);
        buf.writeByte(0);

        int payloadStart = buf.writerIndex();

        buf.writeInt(1);
        buf.writeByte(0x01);
        buf.writeByte(0x29);

        buf.writeInt(4);
        buf.writeByte(0x00);
        buf.writeInt(0);

        buf.writeInt(RESPONSE_META.length);
        buf.writeByte(0x01);
        buf.writeBytes(RESPONSE_META);

        int payloadLength = buf.writerIndex() - payloadStart;
        buf.setInt(4, payloadLength);

        byte[] responseBytes = ByteBufUtil.getBytes(buf);
        System.out.println("NULL GET RESPONSE HEX: " + ByteBufUtil.hexDump(responseBytes));
        return responseBytes;
    }

    private static byte[] buildPutResponse(int transactionId) {
        ByteBuf buf = Unpooled.buffer();

        // Header
        buf.writeInt(6);   // REPLY
        buf.writeInt(16);  // payload length
        buf.writeInt(2);   // 2 parts
        buf.writeInt(transactionId);
        buf.writeByte(0);  // flags

        // Part 0: metadata bytes expected by PutOp
        buf.writeInt(2);       // part length
        buf.writeByte(0x00);   // bytes part
        buf.writeByte(0x00);   // version byte
        buf.writeByte(0x00);   // metadata byte

        // Part 1: flags int = 0
        buf.writeInt(4);       // part length
        buf.writeByte(0x00);   // bytes/int part
        buf.writeInt(0);       // flags

        byte[] responseBytes = ByteBufUtil.getBytes(buf);
        System.out.println("PUT RESPONSE HEX: " + ByteBufUtil.hexDump(responseBytes));
        return responseBytes;
    }

    private static byte[] buildRemoveResponse(int transactionId) {
        ByteBuf buf = Unpooled.buffer();

        buf.writeInt(6);
        buf.writeInt(9);
        buf.writeInt(1);
        buf.writeInt(transactionId);
        buf.writeByte(0);

        buf.writeInt(4);
        buf.writeByte(0x00);
        buf.writeInt(0);

        byte[] responseBytes = ByteBufUtil.getBytes(buf);
        System.out.println("REMOVE RESPONSE HEX: " + ByteBufUtil.hexDump(responseBytes));
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
        return new String(bytes, StandardCharsets.UTF_8)
                .replace("\u0000", "")
                .trim();
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

    private static byte[] hex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
        }
        return out;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}