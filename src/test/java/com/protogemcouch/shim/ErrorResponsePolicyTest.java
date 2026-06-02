package com.protogemcouch.shim;

import com.protogemcouch.wire.MessageTypes;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErrorResponsePolicyTest {

    private static final RuntimeException CAUSE = new RuntimeException("backend down");

    @Test
    void closePolicyClosesTheConnection() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        new CloseConnectionErrorPolicy().onFailure(ctx, MessageTypes.GET, 7, CAUSE);

        verify(ctx).close();
        verify(ctx, never()).writeAndFlush(any());
    }

    @Test
    void exceptionPolicySendsAFrameAndKeepsConnectionOpen() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        new ExceptionFrameErrorPolicy().onFailure(ctx, MessageTypes.GET, 7, CAUSE);

        // Graceful: the request frame was fully decoded, so the stream is still aligned and the
        // connection is left open for subsequent requests.
        verify(ctx, times(1)).writeAndFlush(any());
        verify(ctx, never()).close();
    }

    @Test
    void exceptionPolicyFallsBackToCloseIfWriteFails() {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.writeAndFlush(any())).thenThrow(new RuntimeException("write boom"));

        new ExceptionFrameErrorPolicy().onFailure(ctx, MessageTypes.GET, 7, CAUSE);

        // The error path must never fail open.
        verify(ctx).close();
    }

    @Test
    void factoryDefaultsToCloseConnectionPolicy() {
        // ERROR_RESPONSE_MODE is unset in the test environment; default must be the safe close policy.
        assertInstanceOf(CloseConnectionErrorPolicy.class, RawShimServer.createErrorResponsePolicy());
    }
}
