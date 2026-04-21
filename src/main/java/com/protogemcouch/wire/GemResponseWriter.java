package com.protogemcouch.wire;

import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.util.ByteUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.apache.geode.internal.cache.tier.sockets.VersionedObjectList;

import java.util.List;
import java.util.Map;

public final class GemResponseWriter {

    private static final byte[] RESPONSE_META = ByteUtils.hex(
            "01018800040000ff000300000003e1dba9ded533"
    );

    private GemResponseWriter() {
    }

    public static byte[] buildGetResponse(int transactionId, String value) {
        byte[] serializedValue = GeodeSerialization.serializeString(value);

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(MessageTypes.RESPONSE);
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

        return ByteBufUtil.getBytes(buf);
    }

    public static byte[] buildNullGetResponse(int transactionId) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(MessageTypes.RESPONSE);
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

        return ByteBufUtil.getBytes(buf);
    }

    public static byte[] buildPutResponse(int transactionId) {
        ByteBuf buf = Unpooled.buffer();

        buf.writeInt(MessageTypes.REPLY);
        buf.writeInt(16);
        buf.writeInt(2);
        buf.writeInt(transactionId);
        buf.writeByte(0);

        buf.writeInt(2);
        buf.writeByte(0x00);
        buf.writeByte(0x00);
        buf.writeByte(0x00);

        buf.writeInt(4);
        buf.writeByte(0x00);
        buf.writeInt(0);

        return ByteBufUtil.getBytes(buf);
    }

    public static byte[] buildRemoveResponse(int transactionId) {
        ByteBuf buf = Unpooled.buffer();

        buf.writeInt(MessageTypes.REPLY);
        buf.writeInt(9);
        buf.writeInt(1);
        buf.writeInt(transactionId);
        buf.writeByte(0);

        buf.writeInt(4);
        buf.writeByte(0x00);
        buf.writeInt(0);

        return ByteBufUtil.getBytes(buf);
    }

    public static byte[] buildContainsResponse(int transactionId, boolean exists) {
        byte[] serializedBool = GeodeSerialization.serializeBoolean(exists);

        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(MessageTypes.RESPONSE);
        buf.writeInt(0);
        buf.writeInt(1);
        buf.writeInt(transactionId);
        buf.writeByte(0);

        int payloadStart = buf.writerIndex();

        buf.writeInt(serializedBool.length);
        buf.writeByte(0x01);
        buf.writeBytes(serializedBool);

        int payloadLength = buf.writerIndex() - payloadStart;
        buf.setInt(4, payloadLength);

        return ByteBufUtil.getBytes(buf);
    }

    public static byte[] buildSimpleAck(int transactionId) {
        ByteBuf buf = Unpooled.buffer();

        buf.writeInt(MessageTypes.REPLY);
        buf.writeInt(6);
        buf.writeInt(1);
        buf.writeInt(transactionId);
        buf.writeByte(0);

        buf.writeInt(1);
        buf.writeByte(0x00);
        buf.writeByte(0x00);

        return ByteBufUtil.getBytes(buf);
    }

    public static byte[] buildGetAllChunkedResponse(int transactionId,
                                                    List<String> keys,
                                                    Map<String, String> results) {
        VersionedObjectList vol = new VersionedObjectList(keys.size(), false, false, false);

        for (String key : keys) {
            String value = results.get(key);
            if (value == null) {
                vol.addObjectPartForAbsentKey(null, null, null);
            } else {
                vol.addObjectPart(null, value, true, null);
            }
        }

        byte[] volBytes = GeodeSerialization.serializeObject(vol);

        ByteBuf buf = Unpooled.buffer();

        buf.writeInt(MessageTypes.RESPONSE);
        buf.writeInt(1);
        buf.writeInt(transactionId);

        int chunkLength = 4 + 1 + volBytes.length;
        buf.writeInt(chunkLength);
        buf.writeByte(0x01);

        buf.writeInt(volBytes.length);
        buf.writeByte(0x01);
        buf.writeBytes(volBytes);

        return ByteBufUtil.getBytes(buf);
    }
}