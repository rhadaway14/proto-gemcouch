package com.protogemcouch.ops;

import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.MessageTypes;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * GET_CLIENT_PARTITION_ATTRIBUTES (opcode 73) is an intentional graceful no-op for the single-backend
 * shim (single-hop is a multi-server optimization with nothing to offer here). The handler must accept
 * the probe without error and without writing any response — the client then falls back to direct
 * routing.
 */
class GetClientPartitionAttributesHandlerTest {

    @Test
    void handle_doesNotReply_andDoesNotThrow() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        GetClientPartitionAttributesHandler handler = new GetClientPartitionAttributesHandler();
        GemFrame frame = mockFrame(
                MessageTypes.GET_CLIENT_PARTITION_ATTRIBUTES,
                stringPart("/helloWorld")
        );

        handler.handle(ctx, frame);

        // No fabricated partition metadata is sent; the client falls back to direct routing.
        verify(ctx, never()).writeAndFlush(any());
        verify(ctx, never()).write(any());
    }
}
