package com.protogemcouch.ops;

import com.protogemcouch.couchbase.CouchbaseRepository;
import com.protogemcouch.serialization.GeodeSerialization;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PutHandlerTest {

    @Test
    void handle_parses_put_and_stores_value() {
        CouchbaseRepository repository = mock(CouchbaseRepository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                part("/helloWorld".getBytes()),
                part(new byte[] {0x0c}),
                intPart(0),
                part("my-key".getBytes()),
                objectPart("5"),
                objectPart("my-value"),
                part(new byte[] {0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put("/helloWorld::my-key", "my-value");
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