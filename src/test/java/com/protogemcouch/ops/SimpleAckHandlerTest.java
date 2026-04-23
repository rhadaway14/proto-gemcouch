package com.protogemcouch.ops;

import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SimpleAckHandlerTest {

    @Test
    void handle_writes_ack_response() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        GemFrame frame = mock(GemFrame.class);

        when(frame.getTransactionId()).thenReturn(-1);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        SimpleAckHandler handler = new SimpleAckHandler("PING FRAME");
        handler.handle(ctx, frame);

        verify(ctx).writeAndFlush(any());
    }
}