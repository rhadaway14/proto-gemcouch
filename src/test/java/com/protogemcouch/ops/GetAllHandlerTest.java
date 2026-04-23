package com.protogemcouch.ops;

import com.protogemcouch.couchbase.CouchbaseRepository;
import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GetAllHandlerTest {

    @Test
    void handle_calls_repository_getAll_and_writes_response() {
        CouchbaseRepository repository = mock(CouchbaseRepository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, String> repoResult = new LinkedHashMap<>();
        repoResult.put("key-1", "value-1");
        repoResult.put("key-2", "value-2");
        repoResult.put("missing", null);

        when(repository.getAll("/helloWorld", List.of("key-1", "key-2", "missing")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                part("/helloWorld".getBytes()),
                objectPart(List.of("key-1", "key-2", "missing")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("key-1", "key-2", "missing"));
        verify(ctx).writeAndFlush(any());
    }

    private static GemFrame mockFrame(int messageType, GemPart... parts) {
        GemFrame frame = mock(GemFrame.class);
        when(frame.getMessageType()).thenReturn(messageType);
        when(frame.getNumberOfParts()).thenReturn(parts.length);
        when(frame.getTransactionId()).thenReturn(-1);
        when(frame.getParts()).thenReturn(List.of(parts));
        return frame;
    }

    private static GemPart part(byte[] payload) {
        return new GemPart(payload.length, (byte) 0x00, payload);
    }

    private static GemPart intPart(int value) {
        byte[] bytes = new byte[] {
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
        return new GemPart(bytes.length, (byte) 0x00, bytes);
    }

    private static GemPart objectPart(Object value) {
        byte[] bytes = GeodeSerialization.serializeObject(value);
        return new GemPart(bytes.length, (byte) 0x01, bytes);
    }
}