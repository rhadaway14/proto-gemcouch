package com.protogemcouch.ops;

import com.protogemcouch.couchbase.CouchbaseRepository;
import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;

public class PutHandler implements OperationHandler {

    private final Repository repository;

    public PutHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = null;
        String key = null;
        String value = null;

        for (int i = 0; i < frame.getParts().size(); i++) {
            GemPart part = frame.getParts().get(i);
            byte[] payload = part.getPayload();

            System.out.println("PUT PART[" + i + "] HEX: " + ByteBufUtil.hexDump(payload));
            System.out.println("PUT PART[" + i + "] TEXT: " +
                    new String(payload, StandardCharsets.UTF_8).replace("\u0000", "").trim());
        }

        if (frame.getParts().size() > 0) {
            region = com.protogemcouch.util.ByteUtils.bytesToString(frame.getParts().get(0).getPayload());
        }

        if (frame.getParts().size() > 3) {
            key = com.protogemcouch.util.ByteUtils.bytesToString(frame.getParts().get(3).getPayload());
        }

        if (frame.getParts().size() > 5) {
            byte[] valuePayload = frame.getParts().get(5).getPayload();
            try {
                Object rawValue = GeodeSerialization.deserializeObject(valuePayload);
                if (rawValue != null) {
                    value = String.valueOf(rawValue);
                }
            } catch (Exception e) {
                System.out.println("PUT VALUE DESERIALIZE ERROR: " + e.getMessage());
            }

            if (value == null) {
                String fallback = new String(valuePayload, StandardCharsets.UTF_8)
                        .replace("\u0000", "")
                        .trim();
                if (!fallback.isBlank()) {
                    value = fallback;
                }
            }
        }

        System.out.println("PUT PARSED:");
        System.out.println("  region=" + region);
        System.out.println("  key=" + key);
        System.out.println("  value=" + value);

        if (region != null && !region.isBlank()
                && key != null && !key.isBlank()
                && value != null) {
            String docId = CouchbaseRepository.docId(region, key);
            repository.put(docId, value);
            System.out.println("PUT STORED docId=" + docId);
        } else {
            System.out.println("FAILED TO PARSE PUT");
        }

        ctx.writeAndFlush(Unpooled.wrappedBuffer(
                GemResponseWriter.buildPutResponse(frame.getTransactionId())
        ));
    }
}