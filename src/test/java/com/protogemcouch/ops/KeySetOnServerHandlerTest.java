package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KeySetOnServerHandlerTest {

    @Test
    void handle_calls_repository_keySet_and_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.keySet("/helloWorld")).thenReturn(List.of("k1", "k2"));
        when(ctx.writeAndFlush(any())).thenReturn(null);

        KeySetOnServerHandler handler = new KeySetOnServerHandler(repository);
        GemFrame frame = mockFrame(
                40,
                stringPart("/helloWorld")
        );

        handler.handle(ctx, frame);

        verify(repository).keySet("/helloWorld");
        verify(ctx).writeAndFlush(any());
    }
}