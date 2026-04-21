package com.protogemcouch.ops;

import com.protogemcouch.couchbase.CouchbaseRepository;
import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;

public class PutHandler implements OperationHandler {

    private final CouchbaseRepository repository;

    public PutHandler(CouchbaseRepository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = null;
        String key = null;
        String value = null;

        for (GemPart part : frame.getParts()) {
            byte[] payload = part.getPayload();

            System.out.println("PUT PART HEX: " + ByteBufUtil.hexDump(payload));

            try {
                String candidate = GeodeSerialization.deserializeString(payload);

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
            String docId = CouchbaseRepository.docId(region, key);
            repository.put(docId, value);
            System.out.println("PUT STORED docId=" + docId);
        } else {
            System.out.println("FAILED TO PARSE PUT");
        }

        ctx.writeAndFlush(Unpooled.wrappedBuffer(GemResponseWriter.buildPutResponse(frame.getTransactionId())));
    }
}