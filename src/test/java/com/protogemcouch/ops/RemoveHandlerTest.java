package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RemoveHandlerTest {

    @Test
    void handle_calls_repository_remove_and_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        RemoveHandler handler = new RemoveHandler(repository);
        GemFrame frame = mockFrame(
                9,
                stringPart("/helloWorld"),
                stringPart("remove-key")
        );

        handler.handle(ctx, frame);

        verify(repository).remove("/helloWorld::remove-key");
        verify(ctx).writeAndFlush(any());
    }
}