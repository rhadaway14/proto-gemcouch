package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GetHandlerTest {

    @Test
    void handle_existing_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-key")).thenReturn("my-value");
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_missing_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::missing")).thenReturn(null);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("missing")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::missing");
        verify(ctx).writeAndFlush(any());
    }
}