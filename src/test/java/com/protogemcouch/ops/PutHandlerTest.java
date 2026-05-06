package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import static com.protogemcouch.testsupport.FrameTestUtil.intPart;
import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.objectPart;
import static com.protogemcouch.testsupport.FrameTestUtil.part;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PutHandlerTest {

    @Test
    void handle_parses_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-key"),
                objectPart("5"),
                objectPart("my-value"),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-key"),
                eq(StoredValue.stringValue("my-value"))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_integer_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-int-key"),
                objectPart("5"),
                part(new byte[]{
                        0x39,
                        0x00, 0x00, 0x30, 0x39
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-int-key"),
                eq(StoredValue.integerValue(12345))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_boolean_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-bool-key"),
                objectPart("5"),
                part(new byte[]{
                        0x35,
                        0x01
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-bool-key"),
                eq(StoredValue.booleanValue(Boolean.TRUE))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_long_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-long-key"),
                objectPart("5"),
                part(new byte[]{
                        0x3a,
                        0x00, 0x00, 0x00, 0x02,
                        0x4c, (byte) 0xb0, 0x16, (byte) 0xea
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-long-key"),
                eq(StoredValue.longValue(9_876_543_210L))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_float_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-float-key"),
                objectPart("5"),
                part(new byte[]{
                        0x3b,
                        0x40, (byte) 0xe8, 0x00, 0x00
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-float-key"),
                eq(StoredValue.floatValue(7.25f))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_double_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-double-key"),
                objectPart("5"),
                part(new byte[]{
                        0x3c,
                        0x40, 0x1d, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-double-key"),
                eq(StoredValue.doubleValue(7.25d))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_missing_region_does_not_store_but_still_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart(""),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-key"),
                objectPart("5"),
                objectPart("my-value"),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository, never()).put(any(), any(StoredValue.class));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_missing_key_does_not_store_but_still_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart(""),
                objectPart("5"),
                objectPart("my-value"),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository, never()).put(any(), any(StoredValue.class));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_invalid_value_payload_does_not_store_but_still_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-key"),
                objectPart("5"),
                part(new byte[]{0x00, 0x01, 0x02, 0x03}),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository, never()).put(any(), any(StoredValue.class));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_too_few_parts_does_not_throw_and_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld")
        );

        handler.handle(ctx, frame);

        verify(repository, never()).put(any(), any(StoredValue.class));
        verify(ctx).writeAndFlush(any());
    }
}