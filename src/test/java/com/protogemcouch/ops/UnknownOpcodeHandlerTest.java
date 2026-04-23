package com.protogemcouch.ops;

import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class UnknownOpcodeHandlerTest {

    @Test
    void handle_unknown_opcode_does_not_write_response_or_throw() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        UnknownOpcodeHandler handler = new UnknownOpcodeHandler();
        GemFrame frame = mockFrame(
                999,
                part("mystery".getBytes())
        );

        handler.handle(ctx, frame);

        verify(ctx, never()).writeAndFlush(any());
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