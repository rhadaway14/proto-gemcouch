package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.couchbase.RepositoryException;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.protogemcouch.testsupport.FrameTestUtil.intPart;
import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.objectPart;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 guardrail: an infrastructure failure surfaced by the repository as a
 * {@link RepositoryException} must propagate out of the handler so the dispatch loop records it as
 * an error. It must NOT be swallowed and turned into a benign response, which would mask an outage
 * as empty/absent data and keep the metrics green.
 */
class RepositoryFailurePropagationTest {

    private static RepositoryException backendDown() {
        return new RepositoryException("backend unavailable", new RuntimeException("timeout"));
    }

    @Test
    void getHandlerPropagatesRepositoryFailure() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(repository.get(anyString())).thenThrow(backendDown());

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(0, stringPart("/helloWorld"), stringPart("k"));

        assertThrows(RepositoryException.class, () -> handler.handle(ctx, frame));
        verify(ctx, never()).writeAndFlush(any());
    }

    @Test
    void containsHandlerPropagatesRepositoryFailure() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(repository.containsKey(anyString())).thenThrow(backendDown());

        ContainsHandler handler = new ContainsHandler(repository);
        GemFrame frame = mockFrame(0, stringPart("/helloWorld"), stringPart("k"));

        assertThrows(RepositoryException.class, () -> handler.handle(ctx, frame));
        verify(ctx, never()).writeAndFlush(any());
    }

    @Test
    void removeHandlerPropagatesRepositoryFailure() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        org.mockito.Mockito.doThrow(backendDown()).when(repository).remove(anyString());

        RemoveHandler handler = new RemoveHandler(repository);
        GemFrame frame = mockFrame(0, stringPart("/helloWorld"), stringPart("k"));

        assertThrows(RepositoryException.class, () -> handler.handle(ctx, frame));
        verify(ctx, never()).writeAndFlush(any());
    }

    @Test
    void sizeHandlerPropagatesRepositoryFailure() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(repository.size(anyString())).thenThrow(backendDown());

        SizeOnServerHandler handler = new SizeOnServerHandler(repository);
        GemFrame frame = mockFrame(0, stringPart("/helloWorld"));

        assertThrows(RepositoryException.class, () -> handler.handle(ctx, frame));
        verify(ctx, never()).writeAndFlush(any());
    }

    @Test
    void keySetHandlerPropagatesRepositoryFailureInsteadOfSwallowing() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(repository.keySet(anyString())).thenThrow(backendDown());

        KeySetOnServerHandler handler = new KeySetOnServerHandler(repository);
        GemFrame frame = mockFrame(0, stringPart("/helloWorld"));

        assertThrows(RepositoryException.class, () -> handler.handle(ctx, frame));
        verify(ctx, never()).writeAndFlush(any());
    }

    @Test
    void getAllHandlerPropagatesRepositoryFailureInsteadOfSwallowing() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(repository.getAll(anyString(), any())).thenThrow(backendDown());

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("key-1", "key-2")),
                intPart(0)
        );

        assertThrows(RepositoryException.class, () -> handler.handle(ctx, frame));
        verify(ctx, never()).writeAndFlush(any());
    }
}
