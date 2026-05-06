package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.protogemcouch.testsupport.FrameTestUtil.intPart;
import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.objectPart;
import static com.protogemcouch.testsupport.FrameTestUtil.part;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GetAllHandlerTest {

    @Test
    void handle_calls_repository_getAll_and_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("key-1", StoredValue.stringValue("value-1"));
        repoResult.put("key-2", StoredValue.stringValue("value-2"));
        repoResult.put("missing", null);

        when(repository.getAll("/helloWorld", List.of("key-1", "key-2", "missing")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("key-1", "key-2", "missing")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("key-1", "key-2", "missing"));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_integer_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("int-key-1", StoredValue.integerValue(12345));
        repoResult.put("int-key-2", StoredValue.integerValue(-12345));

        when(repository.getAll("/helloWorld", List.of("int-key-1", "int-key-2")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("int-key-1", "int-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("int-key-1", "int-key-2"));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_boolean_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("bool-key-true", StoredValue.booleanValue(Boolean.TRUE));
        repoResult.put("bool-key-false", StoredValue.booleanValue(Boolean.FALSE));

        when(repository.getAll("/helloWorld", List.of("bool-key-true", "bool-key-false")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("bool-key-true", "bool-key-false")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("bool-key-true", "bool-key-false"));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_long_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("long-key-1", StoredValue.longValue(9_876_543_210L));
        repoResult.put("long-key-2", StoredValue.longValue(-9_876_543_210L));

        when(repository.getAll("/helloWorld", List.of("long-key-1", "long-key-2")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("long-key-1", "long-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("long-key-1", "long-key-2"));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_double_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("double-key-1", StoredValue.doubleValue(7.25d));
        repoResult.put("double-key-2", StoredValue.doubleValue(-7.25d));

        when(repository.getAll("/helloWorld", List.of("double-key-1", "double-key-2")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("double-key-1", "double-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("double-key-1", "double-key-2"));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_mixed_typed_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("string-key", StoredValue.stringValue("value-1"));
        repoResult.put("integer-key", StoredValue.integerValue(12345));
        repoResult.put("boolean-key", StoredValue.booleanValue(Boolean.TRUE));
        repoResult.put("long-key", StoredValue.longValue(9_876_543_210L));
        repoResult.put("double-key", StoredValue.doubleValue(7.25d));
        repoResult.put("missing", null);

        List<String> keys = List.of(
                "string-key",
                "integer-key",
                "boolean-key",
                "long-key",
                "double-key",
                "missing"
        );

        when(repository.getAll("/helloWorld", keys)).thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(keys),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", keys);
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_malformed_key_payload_does_not_throw_and_does_not_call_repository() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                part(new byte[]{0x00, 0x01, 0x02, 0x03}),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository, never()).getAll(any(), any());
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_missing_key_part_does_not_throw_and_does_not_call_repository() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld")
        );

        handler.handle(ctx, frame);

        verify(repository, never()).getAll(any(), any());
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_empty_key_list_writes_response_without_repository_call() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of()),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository, never()).getAll(any(), any());
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_null_values_in_repository_result_are_allowed() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("key-1", StoredValue.stringValue("value-1"));
        repoResult.put("missing", null);

        when(repository.getAll("/helloWorld", List.of("key-1", "missing")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("key-1", "missing")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("key-1", "missing"));
        verify(ctx).writeAndFlush(any());
    }
}