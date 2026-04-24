package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SizeOnServerHandlerTest {

    @Test
    void handle_calls_repository_size_and_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.size("/helloWorld")).thenReturn(7);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        SizeOnServerHandler handler = new SizeOnServerHandler(repository);
        GemFrame frame = mockFrame(
                81,
                stringPart("/helloWorld")
        );

        handler.handle(ctx, frame);

        verify(repository).size("/helloWorld");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_empty_region_still_calls_repository_and_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.size("")).thenReturn(0);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        SizeOnServerHandler handler = new SizeOnServerHandler(repository);
        GemFrame frame = mockFrame(
                81,
                stringPart("")
        );

        handler.handle(ctx, frame);

        verify(repository).size("");
        verify(ctx).writeAndFlush(any());
    }
}