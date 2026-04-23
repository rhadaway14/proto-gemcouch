package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.MessageTypes;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import static com.protogemcouch.testsupport.FrameTestUtil.intPart;
import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
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
                stringPart("/helloWorld"),
                stringPart("abc"),
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
                stringPart("/helloWorld"),
                stringPart("abc"),
                intPart(MessageTypes.CONTAINS_MODE_VALUE_FOR_KEY)
        );

        handler.handle(ctx, frame);

        verify(repository).containsValueForKey("/helloWorld::abc");
        verify(repository, never()).containsKey(any());
        verify(ctx).writeAndFlush(any());
    }
}