package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemResponseWriter;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GetAllHandler implements OperationHandler {

    private final Repository repository;

    public GetAllHandler(Repository repository) {
        this.repository = repository;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, GemFrame frame) {
        String region = frame.getParts().size() > 0
                ? ByteUtils.bytesToString(frame.getParts().get(0).getPayload())
                : "";

        System.out.println("GET ALL REQUEST RECEIVED type=" + frame.getMessageType()
                + " region=" + region
                + " txId=" + frame.getTransactionId());

        List<String> keys = new ArrayList<>();
        if (frame.getParts().size() > 1) {
            byte[] keyPayload = frame.getParts().get(1).getPayload();
            System.out.println("GET ALL KEYS RAW HEX: " + ByteBufUtil.hexDump(keyPayload));
            System.out.println("GET ALL KEYS RAW TEXT: " + ByteUtils.bytesToString(keyPayload));
            logGetAllKeyHints(keyPayload);

            try {
                keys = GeodeSerialization.deserializeGetAllKeys(keyPayload);
                System.out.println("GET ALL DESERIALIZED KEYS: " + keys);
            } catch (Exception e) {
                System.out.println("GET ALL KEY DESERIALIZE ERROR: " + e.getMessage());
            }
        }

        if (frame.getParts().size() > 2) {
            byte[] callbackPayload = frame.getParts().get(2).getPayload();
            System.out.println("GET ALL CALLBACK/CFG HEX: " + ByteBufUtil.hexDump(callbackPayload));
            System.out.println("GET ALL CALLBACK/CFG INT: " + ByteUtils.bytesToInt(callbackPayload));
        }

        Map<String, String> results = repository.getAll(region, keys);
        System.out.println("GET ALL COUCHBASE RESULTS: " + results);

        byte[] responseBytes = GemResponseWriter.buildGetAllChunkedResponse(frame.getTransactionId(), keys, results);
        System.out.println("GET ALL CHUNKED RESPONSE HEX: " + ByteBufUtil.hexDump(responseBytes));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(responseBytes));
    }

    private void logGetAllKeyHints(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        System.out.println("GET ALL KEY HINT TEXT: " + text);

        String[] hints = {
                "proto::getall-1",
                "proto::getall-2",
                "proto::getall-missing"
        };

        for (String hint : hints) {
            if (text.contains(hint)) {
                System.out.println("GET ALL KEY FOUND IN PAYLOAD: " + hint);
            }
        }
    }
}