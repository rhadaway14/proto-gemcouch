package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.serialization.ValueEncoding;
import com.protogemcouch.util.ByteUtils;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PutAllHandlerTest {

    private Repository repository;
    private ChannelHandlerContext ctx;
    private PutAllHandler handler;

    @BeforeEach
    void setUp() {
        repository = mock(Repository.class);
        ctx = mock(ChannelHandlerContext.class);
        handler = new PutAllHandler(repository);

        when(ctx.writeAndFlush(any())).thenReturn(null);
    }

    @Test
    void handle_stores_all_entries_and_writes_response() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("key-1", ValueEncoding.encodeGeodeStringValue("value-1")),
                entry("key-2", ValueEncoding.encodeGeodeStringValue("value-2"))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::key-1"),
                eq(StoredValue.stringValue("value-1"))
        );

        verify(repository).put(
                eq("/helloWorld::key-2"),
                eq(StoredValue.stringValue("value-2"))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_frame_too_small_writes_response_and_does_not_store() {
        GemFrame frame = frame(
                part("/helloWorld"),
                part(new byte[] {0x02, 0x00, 0x01}),
                part(ByteUtils.intToBytes(0))
        );

        handler.handle(ctx, frame);

        verifyNoInteractions(repository);
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_truncated_entries_only_stores_complete_entries() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("key-1", ValueEncoding.encodeGeodeStringValue("value-1"))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::key-1"),
                eq(StoredValue.stringValue("value-1"))
        );

        verify(repository, never()).put(
                eq("/helloWorld::key-2"),
                any(StoredValue.class)
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_null_deserialized_value_uses_fallback_when_string_like() {
        /*
         * This test intentionally validates the lightweight string-like fallback.
         *
         * The second value uses the legacy/unit-test fixture shape:
         *
         *   0x57
         *   UTF-8 bytes
         *
         * The production Geode string shape is length-prefixed and is covered by
         * the other tests through ValueEncoding.encodeGeodeStringValue(...).
         */
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("key-1", ValueEncoding.encodeGeodeStringValue("value-1")),
                entry("key-2", legacyStringLikeValue("value-2"))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::key-1"),
                eq(StoredValue.stringValue("value-1"))
        );

        verify(repository).put(
                eq("/helloWorld::key-2"),
                eq(StoredValue.stringValue("value-2"))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_invalid_value_payload_is_skipped() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("key-1", ValueEncoding.encodeGeodeStringValue("value-1")),
                entry("key-2", new byte[] {0x29})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::key-1"),
                eq(StoredValue.stringValue("value-1"))
        );

        verify(repository, never()).put(
                eq("/helloWorld::key-2"),
                any(StoredValue.class)
        );

        verify(ctx).writeAndFlush(any());
    }

    private static GemFrame putAllFrame(String region, int declaredSize, Entry... entries) {
        java.util.ArrayList<GemPart> parts = new java.util.ArrayList<>();

        parts.add(part(region));
        parts.add(part(new byte[] {0x02, 0x00, 0x01}));
        parts.add(part(ByteUtils.intToBytes(0)));
        parts.add(part(ByteUtils.intToBytes(3)));
        parts.add(part(ByteUtils.intToBytes(declaredSize)));

        for (Entry entry : entries) {
            parts.add(part(entry.key()));
            parts.add(part(entry.valuePayload()));
        }

        return frame(parts.toArray(new GemPart[0]));
    }

    private static Entry entry(String key, byte[] valuePayload) {
        return new Entry(key, valuePayload);
    }

    private static byte[] legacyStringLikeValue(String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[text.length + 1];

        out[0] = 0x57;
        System.arraycopy(text, 0, out, 1, text.length);

        return out;
    }

    private static GemFrame frame(GemPart... parts) {
        GemFrame frame = mock(GemFrame.class);

        when(frame.getParts()).thenReturn(List.of(parts));
        when(frame.getTransactionId()).thenReturn(-1);

        return frame;
    }

    private static GemPart part(String value) {
        return part(value.getBytes(StandardCharsets.UTF_8));
    }

    private static GemPart part(byte[] payload) {
        GemPart part = mock(GemPart.class);

        when(part.getPayload()).thenReturn(payload);

        return part;
    }

    private record Entry(String key, byte[] valuePayload) {
    }
}