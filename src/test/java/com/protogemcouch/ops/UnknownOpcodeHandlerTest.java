package com.protogemcouch.ops;

import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UnknownOpcodeHandlerTest {

    @Test
    void handle_unknown_opcode_does_not_write_response_or_throw() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        UnknownOpcodeHandler handler = new UnknownOpcodeHandler();
        GemFrame frame = mockFrame(
                999,
                stringPart("mystery")
        );

        handler.handle(ctx, frame);

        verify(ctx, never()).writeAndFlush(any());
    }
}