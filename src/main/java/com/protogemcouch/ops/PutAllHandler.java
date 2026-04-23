package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.util.DocumentKeyUtil;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.util.LinkedHashMap;
import java.util.Map;

public class PutAllHandler implements OperationHandler {

    private final Repository repository;

    public PutAllHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        System.out.println("PUT ALL REQUEST RECEIVED region=" + region + " txId=" + frame.getTransactionId());

        if (frame.getParts().size() < 5) {
            System.out.println("PUT ALL FRAME TOO SMALL");
            ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildPutAllChunkedResponse(frame.getTransactionId())));
            return;
        }

        byte[] eventIdPayload = frame.getParts().get(1).getPayload();
        int skipCallbacks = ByteUtils.bytesToInt(frame.getParts().get(2).getPayload());
        int flags = ByteUtils.bytesToInt(frame.getParts().get(3).getPayload());
        int size = ByteUtils.bytesToInt(frame.getParts().get(4).getPayload());

        System.out.println("PUT ALL EVENT ID HEX: " + ByteBufUtil.hexDump(eventIdPayload));
        System.out.println("PUT ALL SKIP CALLBACKS: " + skipCallbacks);
        System.out.println("PUT ALL FLAGS: " + flags);
        System.out.println("PUT ALL MAP SIZE: " + size);

        Map<String, String> entries = new LinkedHashMap<>();

        int partIndex = 5;
        for (int i = 0; i < size; i++) {
            if (partIndex + 1 >= frame.getParts().size()) {
                System.out.println("PUT ALL ENTRY PARTS TRUNCATED at entry index " + i);
                break;
            }

            GemPart keyPart = frame.getParts().get(partIndex++);
            GemPart valuePart = frame.getParts().get(partIndex++);

            String key = ByteUtils.bytesToString(keyPart.getPayload());

            Object rawValue;
            try {
                rawValue = GeodeSerialization.deserializeObject(valuePart.getPayload());
            } catch (Exception e) {
                System.out.println("PUT ALL VALUE DESERIALIZE ERROR for key=" + key + ": " + e.getMessage());
                rawValue = null;
            }

            String value = rawValue == null ? null : String.valueOf(rawValue);

            System.out.println("PUT ALL ENTRY key=" + key + " value=" + value
                    + " valueHex=" + ByteBufUtil.hexDump(valuePart.getPayload()));

            entries.put(key, value);
        }

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (entry.getValue() != null) {
                String docId = DocumentKeyUtil.docId(region, entry.getKey());
                repository.put(docId, entry.getValue());
                System.out.println("PUT ALL STORED docId=" + docId);
            } else {
                System.out.println("PUT ALL SKIPPED NULL VALUE key=" + entry.getKey());
            }
        }

        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildPutAllChunkedResponse(frame.getTransactionId())));
    }
}