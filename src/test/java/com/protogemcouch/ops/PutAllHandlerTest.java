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
import java.util.Date;
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
    void handle_parses_boolean_values_and_stores_them() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("boolean-key-true", geodeBoolean(true)),
                entry("boolean-key-false", geodeBoolean(false))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::boolean-key-true"),
                eq(StoredValue.booleanValue(Boolean.TRUE))
        );

        verify(repository).put(
                eq("/helloWorld::boolean-key-false"),
                eq(StoredValue.booleanValue(Boolean.FALSE))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_character_values_and_stores_them() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("character-key-1", geodeCharacter('A')),
                entry("character-key-2", geodeCharacter('Z'))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::character-key-1"),
                eq(StoredValue.characterValue(Character.valueOf('A')))
        );

        verify(repository).put(
                eq("/helloWorld::character-key-2"),
                eq(StoredValue.characterValue(Character.valueOf('Z')))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_byte_values_and_stores_them() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("byte-key-1", geodeByte((byte) 7)),
                entry("byte-key-2", geodeByte((byte) -7))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::byte-key-1"),
                eq(StoredValue.byteValue(Byte.valueOf((byte) 7)))
        );

        verify(repository).put(
                eq("/helloWorld::byte-key-2"),
                eq(StoredValue.byteValue(Byte.valueOf((byte) -7)))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_short_values_and_stores_them() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("short-key-1", geodeShort((short) 7)),
                entry("short-key-2", geodeShort((short) -7))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::short-key-1"),
                eq(StoredValue.shortValue(Short.valueOf((short) 7)))
        );

        verify(repository).put(
                eq("/helloWorld::short-key-2"),
                eq(StoredValue.shortValue(Short.valueOf((short) -7)))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_integer_values_and_stores_them() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("integer-key-1", geodeInteger(12345)),
                entry("integer-key-2", geodeInteger(-12345))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::integer-key-1"),
                eq(StoredValue.integerValue(12345))
        );

        verify(repository).put(
                eq("/helloWorld::integer-key-2"),
                eq(StoredValue.integerValue(-12345))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_long_values_and_stores_them() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("long-key-1", geodeLong(9_876_543_210L)),
                entry("long-key-2", geodeLong(-9_876_543_210L))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::long-key-1"),
                eq(StoredValue.longValue(9_876_543_210L))
        );

        verify(repository).put(
                eq("/helloWorld::long-key-2"),
                eq(StoredValue.longValue(-9_876_543_210L))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_float_values_and_stores_them() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("float-key-1", geodeFloat(7.25f)),
                entry("float-key-2", geodeFloat(-7.25f))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::float-key-1"),
                eq(StoredValue.floatValue(7.25f))
        );

        verify(repository).put(
                eq("/helloWorld::float-key-2"),
                eq(StoredValue.floatValue(-7.25f))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_double_values_and_stores_them() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("double-key-1", geodeDouble(7.25d)),
                entry("double-key-2", geodeDouble(-7.25d))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::double-key-1"),
                eq(StoredValue.doubleValue(7.25d))
        );

        verify(repository).put(
                eq("/helloWorld::double-key-2"),
                eq(StoredValue.doubleValue(-7.25d))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_date_values_and_stores_them() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("date-key-1", geodeDate(1_000L)),
                entry("date-key-2", geodeDate(1_778_265_266_000L))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::date-key-1"),
                eq(StoredValue.dateValue(new Date(1_000L)))
        );

        verify(repository).put(
                eq("/helloWorld::date-key-2"),
                eq(StoredValue.dateValue(new Date(1_778_265_266_000L)))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_mixed_primitive_values_and_stores_them() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                10,
                entry("string-key", ValueEncoding.encodeGeodeStringValue("value-1")),
                entry("boolean-key", geodeBoolean(true)),
                entry("character-key", geodeCharacter('A')),
                entry("byte-key", geodeByte((byte) 7)),
                entry("short-key", geodeShort((short) 7)),
                entry("integer-key", geodeInteger(12345)),
                entry("long-key", geodeLong(9_876_543_210L)),
                entry("float-key", geodeFloat(7.25f)),
                entry("double-key", geodeDouble(7.25d)),
                entry("date-key", geodeDate(1_000L))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::string-key"),
                eq(StoredValue.stringValue("value-1"))
        );

        verify(repository).put(
                eq("/helloWorld::boolean-key"),
                eq(StoredValue.booleanValue(Boolean.TRUE))
        );

        verify(repository).put(
                eq("/helloWorld::character-key"),
                eq(StoredValue.characterValue(Character.valueOf('A')))
        );

        verify(repository).put(
                eq("/helloWorld::byte-key"),
                eq(StoredValue.byteValue(Byte.valueOf((byte) 7)))
        );

        verify(repository).put(
                eq("/helloWorld::short-key"),
                eq(StoredValue.shortValue(Short.valueOf((short) 7)))
        );

        verify(repository).put(
                eq("/helloWorld::integer-key"),
                eq(StoredValue.integerValue(12345))
        );

        verify(repository).put(
                eq("/helloWorld::long-key"),
                eq(StoredValue.longValue(9_876_543_210L))
        );

        verify(repository).put(
                eq("/helloWorld::float-key"),
                eq(StoredValue.floatValue(7.25f))
        );

        verify(repository).put(
                eq("/helloWorld::double-key"),
                eq(StoredValue.doubleValue(7.25d))
        );

        verify(repository).put(
                eq("/helloWorld::date-key"),
                eq(StoredValue.dateValue(new Date(1_000L)))
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

    private static byte[] geodeBoolean(boolean value) {
        return new byte[] {
                0x35,
                (byte) (value ? 0x01 : 0x00)
        };
    }

    private static byte[] geodeCharacter(char value) {
        return new byte[] {
                0x36,
                (byte) ((value >>> 8) & 0xff),
                (byte) (value & 0xff)
        };
    }

    private static byte[] geodeByte(byte value) {
        return new byte[] {
                0x37,
                value
        };
    }

    private static byte[] geodeShort(short value) {
        return new byte[] {
                0x38,
                (byte) ((value >>> 8) & 0xff),
                (byte) (value & 0xff)
        };
    }

    private static byte[] geodeInteger(int value) {
        return new byte[] {
                0x39,
                (byte) ((value >>> 24) & 0xff),
                (byte) ((value >>> 16) & 0xff),
                (byte) ((value >>> 8) & 0xff),
                (byte) (value & 0xff)
        };
    }

    private static byte[] geodeLong(long value) {
        return new byte[] {
                0x3a,
                (byte) ((value >>> 56) & 0xff),
                (byte) ((value >>> 48) & 0xff),
                (byte) ((value >>> 40) & 0xff),
                (byte) ((value >>> 32) & 0xff),
                (byte) ((value >>> 24) & 0xff),
                (byte) ((value >>> 16) & 0xff),
                (byte) ((value >>> 8) & 0xff),
                (byte) (value & 0xff)
        };
    }

    private static byte[] geodeFloat(float value) {
        int bits = Float.floatToRawIntBits(value);

        return new byte[] {
                0x3b,
                (byte) ((bits >>> 24) & 0xff),
                (byte) ((bits >>> 16) & 0xff),
                (byte) ((bits >>> 8) & 0xff),
                (byte) (bits & 0xff)
        };
    }

    private static byte[] geodeDouble(double value) {
        long bits = Double.doubleToRawLongBits(value);

        return new byte[] {
                0x3c,
                (byte) ((bits >>> 56) & 0xff),
                (byte) ((bits >>> 48) & 0xff),
                (byte) ((bits >>> 40) & 0xff),
                (byte) ((bits >>> 32) & 0xff),
                (byte) ((bits >>> 24) & 0xff),
                (byte) ((bits >>> 16) & 0xff),
                (byte) ((bits >>> 8) & 0xff),
                (byte) (bits & 0xff)
        };
    }

    private static byte[] geodeDate(long epochMillis) {
        return new byte[] {
                0x3d,
                (byte) ((epochMillis >>> 56) & 0xff),
                (byte) ((epochMillis >>> 48) & 0xff),
                (byte) ((epochMillis >>> 40) & 0xff),
                (byte) ((epochMillis >>> 32) & 0xff),
                (byte) ((epochMillis >>> 24) & 0xff),
                (byte) ((epochMillis >>> 16) & 0xff),
                (byte) ((epochMillis >>> 8) & 0xff),
                (byte) (epochMillis & 0xff)
        };
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