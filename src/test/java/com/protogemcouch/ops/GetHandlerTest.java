package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GetHandlerTest {

    @Test
    void handle_existing_string_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-key"))
                .thenReturn(StoredValue.stringValue("my-value"));

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
    void handle_existing_boolean_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-bool-key"))
                .thenReturn(StoredValue.booleanValue(Boolean.TRUE));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-bool-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-bool-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_character_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-character-key"))
                .thenReturn(StoredValue.characterValue(Character.valueOf('A')));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-character-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-character-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_byte_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-byte-key"))
                .thenReturn(StoredValue.byteValue(Byte.valueOf((byte) 7)));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-byte-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-byte-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_short_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-short-key"))
                .thenReturn(StoredValue.shortValue(Short.valueOf((short) 7)));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-short-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-short-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_integer_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-int-key"))
                .thenReturn(StoredValue.integerValue(12345));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-int-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-int-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_long_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-long-key"))
                .thenReturn(StoredValue.longValue(9_876_543_210L));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-long-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-long-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_float_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-float-key"))
                .thenReturn(StoredValue.floatValue(7.25f));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-float-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-float-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_double_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-double-key"))
                .thenReturn(StoredValue.doubleValue(7.25d));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-double-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-double-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_date_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-date-key"))
                .thenReturn(StoredValue.dateValue(new Date(1_000L)));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-date-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-date-key");
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