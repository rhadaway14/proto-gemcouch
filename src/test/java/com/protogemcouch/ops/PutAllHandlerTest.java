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
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
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
    void handle_parses_byte_array_values_and_stores_them() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("byte-array-key-1", geodeByteArray(new byte[] {
                        0x01, 0x02, 0x03, 0x04, 0x05
                })),
                entry("byte-array-key-2", geodeByteArray(new byte[] {
                        0x00, 0x01, 0x7f, (byte) 0x80, (byte) 0xff
                }))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::byte-array-key-1"),
                eq(StoredValue.byteArrayValue(new byte[] {
                        0x01, 0x02, 0x03, 0x04, 0x05
                }))
        );

        verify(repository).put(
                eq("/helloWorld::byte-array-key-2"),
                eq(StoredValue.byteArrayValue(new byte[] {
                        0x00, 0x01, 0x7f, (byte) 0x80, (byte) 0xff
                }))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_string_array_values_and_stores_them() {
        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("string-array-key-1", geodeStringArray(new String[] {
                        "one",
                        "two",
                        "three"
                })),
                entry("string-array-key-2", geodeStringArray(new String[] {
                        "one",
                        null,
                        "three"
                }))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::string-array-key-1"),
                eq(StoredValue.stringArrayValue(new String[] {
                        "one",
                        "two",
                        "three"
                }))
        );

        verify(repository).put(
                eq("/helloWorld::string-array-key-2"),
                eq(StoredValue.stringArrayValue(new String[] {
                        "one",
                        null,
                        "three"
                }))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_string_array_list_values_and_stores_them() {
        ArrayList<String> expected1 = new ArrayList<>();
        expected1.add("one");
        expected1.add("two");
        expected1.add("three");

        ArrayList<String> expected2 = new ArrayList<>();
        expected2.add("one");
        expected2.add(null);
        expected2.add("three");

        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("string-array-list-key-1", geodeStringArrayList(expected1)),
                entry("string-array-list-key-2", geodeStringArrayList(expected2))
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::string-array-list-key-1"),
                eq(StoredValue.stringArrayListValue(expected1))
        );

        verify(repository).put(
                eq("/helloWorld::string-array-list-key-2"),
                eq(StoredValue.stringArrayListValue(expected2))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_empty_string_hash_map_value_and_stores_it() {
        LinkedHashMap<String, String> expected = new LinkedHashMap<>();

        GemFrame frame = putAllFrame(
                "/helloWorld",
                1,
                entry("empty-string-hash-map-key", geodeEmptyStringHashMap())
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::empty-string-hash-map-key"),
                eq(StoredValue.stringHashMapValue(expected))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_string_hash_map_values_and_stores_them() {
        LinkedHashMap<String, String> expected1 = new LinkedHashMap<>();
        expected1.put("one", "value-1");
        expected1.put("two", "value-2");
        expected1.put("three", "value-3");

        LinkedHashMap<String, String> expected2 = new LinkedHashMap<>();
        expected2.put("one", "value-1");
        expected2.put("two", null);
        expected2.put("three", "value-3");

        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("string-hash-map-key-1", geodeStringHashMapThree()),
                entry("string-hash-map-key-2", geodeStringHashMapWithNullValue())
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::string-hash-map-key-1"),
                eq(StoredValue.stringHashMapValue(expected1))
        );

        verify(repository).put(
                eq("/helloWorld::string-hash-map-key-2"),
                eq(StoredValue.stringHashMapValue(expected2))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_string_object_hash_map_values_and_stores_them() {
        LinkedHashMap<String, Object> expected1 = new LinkedHashMap<>();
        expected1.put("name", "rob");
        expected1.put("age", Integer.valueOf(42));
        expected1.put("active", Boolean.TRUE);

        LinkedHashMap<String, Object> expected2 = new LinkedHashMap<>();
        expected2.put("name", "rob");
        expected2.put("middleName", null);
        expected2.put("createdAt", new Date(1_000L));

        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("string-object-hash-map-key-1", geodeStringObjectHashMapStringIntegerBoolean()),
                entry("string-object-hash-map-key-2", geodeStringObjectHashMapStringNullDate())
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::string-object-hash-map-key-1"),
                eq(StoredValue.stringObjectHashMapValue(expected1))
        );

        verify(repository).put(
                eq("/helloWorld::string-object-hash-map-key-2"),
                eq(StoredValue.stringObjectHashMapValue(expected2))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_string_object_hash_map_with_array_values_and_stores_it() {
        ArrayList<String> expectedList = new ArrayList<>();
        expectedList.add("one");
        expectedList.add(null);
        expectedList.add("three");

        LinkedHashMap<String, Object> expected = new LinkedHashMap<>();
        expected.put("payload", new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05
        });
        expected.put("items", new String[] {
                "one", null, "three"
        });
        expected.put("list", expectedList);

        GemFrame frame = putAllFrame(
                "/helloWorld",
                1,
                entry("string-object-hash-map-array-key", geodeStringObjectHashMapArrays())
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::string-object-hash-map-array-key"),
                eq(StoredValue.stringObjectHashMapValue(expected))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_object_array_list_values_and_stores_them() {
        byte[] objectArrayList1 = geodeObjectArrayListStringIntegerBoolean();
        byte[] objectArrayList2 = geodeObjectArrayListMixedSupportedValues();

        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("object-array-list-key-1", objectArrayList1),
                entry("object-array-list-key-2", objectArrayList2)
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::object-array-list-key-1"),
                eq(StoredValue.objectArrayListValue(objectArrayList1))
        );

        verify(repository).put(
                eq("/helloWorld::object-array-list-key-2"),
                eq(StoredValue.objectArrayListValue(objectArrayList2))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_object_array_values_and_stores_them() {
        byte[] objectArray1 = geodeObjectArrayStringIntegerBoolean();
        byte[] objectArray2 = geodeObjectArrayMixedSupportedValues();

        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("object-array-key-1", objectArray1),
                entry("object-array-key-2", objectArray2)
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::object-array-key-1"),
                eq(StoredValue.objectArrayValue(objectArray1))
        );

        verify(repository).put(
                eq("/helloWorld::object-array-key-2"),
                eq(StoredValue.objectArrayValue(objectArray2))
        );

        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_java_serialized_pojo_values_and_stores_them() {
        byte[] expectedSerializedValue = javaSerializedPojoBytes();

        GemFrame frame = putAllFrame(
                "/helloWorld",
                2,
                entry("java-serialized-pojo-key-1", geodeJavaSerializedPojo()),
                entry("java-serialized-pojo-key-2", geodeJavaSerializedPojoWithNullField())
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::java-serialized-pojo-key-1"),
                eq(StoredValue.javaSerializedObjectValue(
                        "com.protogemcouch.wire.SerializablePojoShapeTest$CustomerProfile",
                        expectedSerializedValue
                ))
        );

        verify(repository).put(
                eq("/helloWorld::java-serialized-pojo-key-2"),
                eq(StoredValue.javaSerializedObjectValue(
                        "com.protogemcouch.wire.SerializablePojoShapeTest$CustomerProfile",
                        javaSerializedPojoWithNullFieldBytes()
                ))
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
        ArrayList<String> expectedStringArrayList = new ArrayList<>();
        expectedStringArrayList.add("one");
        expectedStringArrayList.add(null);
        expectedStringArrayList.add("three");

        LinkedHashMap<String, String> expectedStringHashMap = new LinkedHashMap<>();
        expectedStringHashMap.put("one", "value-1");
        expectedStringHashMap.put("two", null);
        expectedStringHashMap.put("three", "value-3");

        LinkedHashMap<String, Object> expectedStringObjectHashMap = new LinkedHashMap<>();
        expectedStringObjectHashMap.put("name", "rob");
        expectedStringObjectHashMap.put("age", Integer.valueOf(42));
        expectedStringObjectHashMap.put("active", Boolean.TRUE);

        GemFrame frame = putAllFrame(
                "/helloWorld",
                18,
                entry("string-key", ValueEncoding.encodeGeodeStringValue("value-1")),
                entry("boolean-key", geodeBoolean(true)),
                entry("character-key", geodeCharacter('A')),
                entry("byte-key", geodeByte((byte) 7)),
                entry("byte-array-key", geodeByteArray(new byte[] {
                        0x01, 0x02, 0x03, 0x04, 0x05
                })),
                entry("string-array-key", geodeStringArray(new String[] {
                        "one",
                        null,
                        "three"
                })),
                entry("string-array-list-key", geodeStringArrayList(expectedStringArrayList)),
                entry("string-hash-map-key", geodeStringHashMapWithNullValue()),
                entry("string-object-hash-map-key", geodeStringObjectHashMapStringIntegerBoolean()),
                entry("java-serialized-pojo-key", geodeJavaSerializedPojo()),
                entry("object-array-key", geodeObjectArrayStringIntegerBoolean()),
                entry("object-array-list-key", geodeObjectArrayListStringIntegerBoolean()),
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
                eq("/helloWorld::byte-array-key"),
                eq(StoredValue.byteArrayValue(new byte[] {
                        0x01, 0x02, 0x03, 0x04, 0x05
                }))
        );

        verify(repository).put(
                eq("/helloWorld::string-array-key"),
                eq(StoredValue.stringArrayValue(new String[] {
                        "one",
                        null,
                        "three"
                }))
        );

        verify(repository).put(
                eq("/helloWorld::string-array-list-key"),
                eq(StoredValue.stringArrayListValue(expectedStringArrayList))
        );

        verify(repository).put(
                eq("/helloWorld::string-hash-map-key"),
                eq(StoredValue.stringHashMapValue(expectedStringHashMap))
        );

        verify(repository).put(
                eq("/helloWorld::string-object-hash-map-key"),
                eq(StoredValue.stringObjectHashMapValue(expectedStringObjectHashMap))
        );

        verify(repository).put(
                eq("/helloWorld::java-serialized-pojo-key"),
                eq(StoredValue.javaSerializedObjectValue(
                        "com.protogemcouch.wire.SerializablePojoShapeTest$CustomerProfile",
                        javaSerializedPojoBytes()
                ))
        );

        verify(repository).put(
                eq("/helloWorld::object-array-key"),
                eq(StoredValue.objectArrayValue(geodeObjectArrayStringIntegerBoolean()))
        );

        verify(repository).put(
                eq("/helloWorld::object-array-list-key"),
                eq(StoredValue.objectArrayListValue(geodeObjectArrayListStringIntegerBoolean()))
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

    private static byte[] geodeByteArray(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("byte[] value must not be null");
        }

        if (value.length > 0x7f) {
            throw new IllegalArgumentException(
                    "Test helper currently supports byte[] lengths from 0 to 127. Actual: " + value.length
            );
        }

        byte[] out = new byte[value.length + 2];
        out[0] = 0x2e;
        out[1] = (byte) value.length;
        System.arraycopy(value, 0, out, 2, value.length);

        return out;
    }

    private static byte[] geodeStringArray(String[] value) {
        if (value == null) {
            throw new IllegalArgumentException("String[] value must not be null");
        }

        if (value.length > 0x7f) {
            throw new IllegalArgumentException(
                    "Test helper currently supports String[] lengths from 0 to 127. Actual: " + value.length
            );
        }

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        out.write(0x40);
        out.write(value.length);

        for (String item : value) {
            if (item == null) {
                out.write(0x45);
            } else {
                byte[] encoded = ValueEncoding.encodeGeodeStringValue(item);
                out.write(encoded, 0, encoded.length);
            }
        }

        return out.toByteArray();
    }

    private static byte[] geodeStringArrayList(ArrayList<String> value) {
        if (value == null) {
            throw new IllegalArgumentException("ArrayList<String> value must not be null");
        }

        if (value.size() > 0x7f) {
            throw new IllegalArgumentException(
                    "Test helper currently supports ArrayList<String> sizes from 0 to 127. Actual: " + value.size()
            );
        }

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        out.write(0x41);
        out.write(value.size());

        for (String item : value) {
            if (item == null) {
                out.write(0x29);
            } else {
                byte[] encoded = ValueEncoding.encodeGeodeStringValue(item);
                out.write(encoded, 0, encoded.length);
            }
        }

        return out.toByteArray();
    }

    private static byte[] geodeEmptyStringHashMap() {
        return new byte[] {
                0x43,
                0x00
        };
    }

    private static byte[] geodeStringHashMapThree() {
        return hexToBytes(
                "2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400036f6e6574000776616c75652d3174000374776f74000776616c75652d32740005746872656574000776616c75652d337800"
        );
    }

    private static byte[] geodeStringHashMapWithNullValue() {
        return hexToBytes(
                "2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400036f6e6574000776616c75652d3174000374776f70740005746872656574000776616c75652d337800"
        );
    }

    private static byte[] geodeStringObjectHashMapStringIntegerBoolean() {
        return hexToBytes(
                "2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400046e616d65740003726f62740003616765737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b02000078700000002a740006616374697665737200116a6176612e6c616e672e426f6f6c65616ecd207280d59cfaee0200015a000576616c75657870017800"
        );
    }

    private static byte[] geodeStringObjectHashMapStringNullDate() {
        return hexToBytes(
                "2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400046e616d65740003726f6274000a6d6964646c654e616d65707400096372656174656441747372000e6a6176612e7574696c2e44617465686a81014b5974190300007870770800000000000003e8787800"
        );
    }

    private static byte[] geodeStringObjectHashMapArrays() {
        /*
         * Shape generated by Java serialization for:
         *
         * LinkedHashMap<String,Object> value = new LinkedHashMap<>();
         * value.put("payload", new byte[] {1,2,3,4,5});
         * value.put("items", new String[] {"one", null, "three"});
         * value.put("list", new ArrayList<>(List.of("one", null, "three")));
         *
         * This is not one of the raw discovery cases because we want all three
         * array/list value families in one PUT_ALL unit test.
         */
        return javaSerializedLinkedHashMap(
                "payload", new byte[] {0x01, 0x02, 0x03, 0x04, 0x05},
                "items", new String[] {"one", null, "three"},
                "list", arrayList("one", null, "three")
        );
    }

    private static byte[] javaSerializedLinkedHashMap(
            String key1,
            Object value1,
            String key2,
            Object value2,
            String key3,
            Object value3
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put(key1, value1);
        value.put(key2, value2);
        value.put(key3, value3);

        try {
            java.io.ByteArrayOutputStream javaBytes = new java.io.ByteArrayOutputStream();

            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(javaBytes)) {
                out.writeObject(value);
            }

            byte[] serialized = javaBytes.toByteArray();
            byte[] framed = new byte[serialized.length + 1];

            framed[0] = 0x2c;
            System.arraycopy(serialized, 0, framed, 1, serialized.length);

            return framed;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to serialize LinkedHashMap test fixture", e);
        }
    }

    private static ArrayList<String> arrayList(String one, String two, String three) {
        ArrayList<String> out = new ArrayList<>();
        out.add(one);
        out.add(two);
        out.add(three);
        return out;
    }

    private static byte[] geodeObjectArrayListStringIntegerBoolean() {
        return hexToBytes(
                "41035700036f6e65390000002a3501"
        );
    }

    private static byte[] geodeObjectArrayListMixedSupportedValues() {
        return hexToBytes(
                "411057000c737472696e672d76616c756536004137072e0301020340035700036f6e6545570005746872656534032b5700106a6176612e6c616e672e4f626a6563745700036f6e65390000002a350141035700036f6e652957000574687265652caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000047400046e616d65740003726f62740003616765737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b02000078700000002a740006616374697665737200116a6176612e6c616e672e426f6f6c65616ecd207280d59cfaee0200015a000576616c75657870017400096372656174656441747372000e6a6176612e7574696c2e44617465686a81014b5974190300007870770800000000000003e87878002caced00057372003f636f6d2e70726f746f67656d636f7563682e776972652e4f626a65637441727261794c69737453686170655465737424437573746f6d657250726f66696c6500000000000000010200045a00066163746976654900036167654c000269647400124c6a6176612f6c616e672f537472696e673b4c00046e616d6571007e00017870010000002a74000a637573746f6d65722d31740003526f62380007390000002a35013a000000024cb016ea3b40e800003c401d0000000000003d00000000000003e8"
        );
    }

    private static byte[] geodeObjectArrayStringIntegerBoolean() {
        return hexToBytes(
                "34032b5700106a6176612e6c616e672e4f626a6563745700036f6e65390000002a3501"
        );
    }

    private static byte[] geodeObjectArrayMixedSupportedValues() {
        return hexToBytes(
                "340f2b5700106a6176612e6c616e672e4f626a65637457000c737472696e672d76616c756536004137072e0301020340035700036f6e6545570005746872656541035700036f6e652957000574687265652caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000047400046e616d65740003726f62740003616765737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b02000078700000002a740006616374697665737200116a6176612e6c616e672e426f6f6c65616ecd207280d59cfaee0200015a000576616c75657870017400096372656174656441747372000e6a6176612e7574696c2e44617465686a81014b5974190300007870770800000000000003e87878002caced00057372003b636f6d2e70726f746f67656d636f7563682e776972652e4f626a656374417272617953686170655465737424437573746f6d657250726f66696c6500000000000000010200045a00066163746976654900036167654c000269647400124c6a6176612f6c616e672f537472696e673b4c00046e616d6571007e00017870010000002a74000a637573746f6d65722d31740003526f62380007390000002a35013a000000024cb016ea3b40e800003c401d0000000000003d00000000000003e8"
        );
    }

    private static byte[] geodeJavaSerializedPojo() {
        return hexToBytes(
                "2caced000573720040636f6d2e70726f746f67656d636f7563682e776972652e53657269616c697a61626c65506f6a6f53686170655465737424437573746f6d657250726f66696c6500000000000000010200045a00066163746976654900036167654c000269647400124c6a6176612f6c616e672f537472696e673b4c00046e616d6571007e00017870010000002a74000a637573746f6d65722d31740003526f62"
        );
    }

    private static byte[] javaSerializedPojoBytes() {
        return hexToBytes(
                "aced000573720040636f6d2e70726f746f67656d636f7563682e776972652e53657269616c697a61626c65506f6a6f53686170655465737424437573746f6d657250726f66696c6500000000000000010200045a00066163746976654900036167654c000269647400124c6a6176612f6c616e672f537472696e673b4c00046e616d6571007e00017870010000002a74000a637573746f6d65722d31740003526f62"
        );
    }

    private static byte[] geodeJavaSerializedPojoWithNullField() {
        return hexToBytes(
                "2caced000573720040636f6d2e70726f746f67656d636f7563682e776972652e53657269616c697a61626c65506f6a6f53686170655465737424437573746f6d657250726f66696c6500000000000000010200045a00066163746976654900036167654c000269647400124c6a6176612f6c616e672f537472696e673b4c00046e616d6571007e00017870000000002b74000a637573746f6d65722d3270"
        );
    }

    private static byte[] javaSerializedPojoWithNullFieldBytes() {
        return hexToBytes(
                "aced000573720040636f6d2e70726f746f67656d636f7563682e776972652e53657269616c697a61626c65506f6a6f53686170655465737424437573746f6d657250726f66696c6500000000000000010200045a00066163746976654900036167654c000269647400124c6a6176612f6c616e672f537472696e673b4c00046e616d6571007e00017870000000002b74000a637573746f6d65722d3270"
        );
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

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must be non-null and have an even length");
        }

        byte[] out = new byte[hex.length() / 2];

        for (int i = 0; i < hex.length(); i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }

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