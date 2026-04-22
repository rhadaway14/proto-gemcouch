package com.protogemcouch.ops;

import com.protogemcouch.couchbase.CouchbaseRepository;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GetHandlerTest {

    @Test
    void handle_existing_value_writes_response() {
        CouchbaseRepository repository = mock(CouchbaseRepository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-key")).thenReturn("my-value");
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                part("/helloWorld".getBytes()),
                part("my-key".getBytes())
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_missing_value_writes_response() {
        CouchbaseRepository repository = mock(CouchbaseRepository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::missing")).thenReturn(null);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                part("/helloWorld".getBytes()),
                part("missing".getBytes())
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::missing");
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