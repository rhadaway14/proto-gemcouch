package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.MessageTypes;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContainsHandlerTest {

    @Test
    void handle_contains_key_mode_calls_containsKey() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.containsKey("/helloWorld::abc")).thenReturn(true);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        ContainsHandler handler = new ContainsHandler(repository);
        GemFrame frame = mockFrame(
                MessageTypes.CONTAINS_KEY,
                part("/helloWorld".getBytes()),
                part("abc".getBytes()),
                intPart(MessageTypes.CONTAINS_MODE_KEY)
        );

        handler.handle(ctx, frame);

        verify(repository).containsKey("/helloWorld::abc");
        verify(repository, never()).containsValueForKey(any());
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_contains_value_for_key_mode_calls_containsValueForKey() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.containsValueForKey("/helloWorld::abc")).thenReturn(true);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        ContainsHandler handler = new ContainsHandler(repository);
        GemFrame frame = mockFrame(
                MessageTypes.CONTAINS_KEY,
                part("/helloWorld".getBytes()),
                part("abc".getBytes()),
                intPart(MessageTypes.CONTAINS_MODE_VALUE_FOR_KEY)
        );

        handler.handle(ctx, frame);

        verify(repository).containsValueForKey("/helloWorld::abc");
        verify(repository, never()).containsKey(any());
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
}