package com.protogemcouch.ops;

import com.protogemcouch.couchbase.CouchbaseRepository;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KeySetOnServerHandlerTest {

    @Test
    void handle_calls_repository_keySet_and_writes_response() {
        CouchbaseRepository repository = mock(CouchbaseRepository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.keySet("/helloWorld")).thenReturn(List.of("k1", "k2"));
        when(ctx.writeAndFlush(any())).thenReturn(null);

        KeySetOnServerHandler handler = new KeySetOnServerHandler(repository);
        GemFrame frame = mockFrame(
                40,
                part("/helloWorld".getBytes())
        );

        handler.handle(ctx, frame);

        verify(repository).keySet("/helloWorld");
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
}