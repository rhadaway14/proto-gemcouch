package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
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
    void handle_character_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("character-key-1", StoredValue.characterValue(Character.valueOf('A')));
        repoResult.put("character-key-2", StoredValue.characterValue(Character.valueOf('Z')));

        when(repository.getAll("/helloWorld", List.of("character-key-1", "character-key-2")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("character-key-1", "character-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("character-key-1", "character-key-2"));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_byte_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("byte-key-1", StoredValue.byteValue(Byte.valueOf((byte) 7)));
        repoResult.put("byte-key-2", StoredValue.byteValue(Byte.valueOf((byte) -7)));

        when(repository.getAll("/helloWorld", List.of("byte-key-1", "byte-key-2")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("byte-key-1", "byte-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("byte-key-1", "byte-key-2"));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_byte_array_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("byte-array-key-1", StoredValue.byteArrayValue(new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05
        }));
        repoResult.put("byte-array-key-2", StoredValue.byteArrayValue(new byte[] {
                0x00, 0x01, 0x7f, (byte) 0x80, (byte) 0xff
        }));

        when(repository.getAll("/helloWorld", List.of("byte-array-key-1", "byte-array-key-2")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("byte-array-key-1", "byte-array-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("byte-array-key-1", "byte-array-key-2"));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_string_array_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("string-array-key-1", StoredValue.stringArrayValue(new String[] {
                "one",
                "two",
                "three"
        }));
        repoResult.put("string-array-key-2", StoredValue.stringArrayValue(new String[] {
                "one",
                null,
                "three"
        }));

        when(repository.getAll("/helloWorld", List.of("string-array-key-1", "string-array-key-2")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("string-array-key-1", "string-array-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("string-array-key-1", "string-array-key-2"));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_string_array_list_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        ArrayList<String> list1 = new ArrayList<>();
        list1.add("one");
        list1.add("two");
        list1.add("three");

        ArrayList<String> list2 = new ArrayList<>();
        list2.add("one");
        list2.add(null);
        list2.add("three");

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("string-array-list-key-1", StoredValue.stringArrayListValue(list1));
        repoResult.put("string-array-list-key-2", StoredValue.stringArrayListValue(list2));

        when(repository.getAll("/helloWorld", List.of("string-array-list-key-1", "string-array-list-key-2")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("string-array-list-key-1", "string-array-list-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("string-array-list-key-1", "string-array-list-key-2"));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_string_hash_map_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        LinkedHashMap<String, String> map1 = new LinkedHashMap<>();
        map1.put("one", "value-1");
        map1.put("two", "value-2");
        map1.put("three", "value-3");

        LinkedHashMap<String, String> map2 = new LinkedHashMap<>();
        map2.put("one", "value-1");
        map2.put("two", null);
        map2.put("three", "value-3");

        LinkedHashMap<String, String> map3 = new LinkedHashMap<>();

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("string-hash-map-key-1", StoredValue.stringHashMapValue(map1));
        repoResult.put("string-hash-map-key-2", StoredValue.stringHashMapValue(map2));
        repoResult.put("string-hash-map-key-3", StoredValue.stringHashMapValue(map3));

        when(repository.getAll(
                "/helloWorld",
                List.of("string-hash-map-key-1", "string-hash-map-key-2", "string-hash-map-key-3")
        )).thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("string-hash-map-key-1", "string-hash-map-key-2", "string-hash-map-key-3")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll(
                "/helloWorld",
                List.of("string-hash-map-key-1", "string-hash-map-key-2", "string-hash-map-key-3")
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_string_object_hash_map_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        LinkedHashMap<String, Object> map1 = new LinkedHashMap<>();
        map1.put("name", "rob");
        map1.put("age", Integer.valueOf(42));
        map1.put("active", Boolean.TRUE);

        LinkedHashMap<String, Object> map2 = new LinkedHashMap<>();
        map2.put("name", "rob");
        map2.put("middleName", null);
        map2.put("createdAt", new Date(1_000L));

        ArrayList<String> list = new ArrayList<>();
        list.add("one");
        list.add(null);
        list.add("three");

        LinkedHashMap<String, Object> map3 = new LinkedHashMap<>();
        map3.put("payload", new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05
        });
        map3.put("items", new String[] {
                "one", null, "three"
        });
        map3.put("list", list);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("string-object-hash-map-key-1", StoredValue.stringObjectHashMapValue(map1));
        repoResult.put("string-object-hash-map-key-2", StoredValue.stringObjectHashMapValue(map2));
        repoResult.put("string-object-hash-map-key-3", StoredValue.stringObjectHashMapValue(map3));

        when(repository.getAll(
                "/helloWorld",
                List.of(
                        "string-object-hash-map-key-1",
                        "string-object-hash-map-key-2",
                        "string-object-hash-map-key-3"
                )
        )).thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of(
                        "string-object-hash-map-key-1",
                        "string-object-hash-map-key-2",
                        "string-object-hash-map-key-3"
                )),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll(
                "/helloWorld",
                List.of(
                        "string-object-hash-map-key-1",
                        "string-object-hash-map-key-2",
                        "string-object-hash-map-key-3"
                )
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_object_array_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        byte[] objectArray1 = geodeObjectArrayStringIntegerBoolean();
        byte[] objectArray2 = geodeObjectArrayMixedSupportedValues();

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("object-array-key-1", StoredValue.objectArrayValue(objectArray1));
        repoResult.put("object-array-key-2", StoredValue.objectArrayValue(objectArray2));

        when(repository.getAll(
                "/helloWorld",
                List.of("object-array-key-1", "object-array-key-2")
        )).thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("object-array-key-1", "object-array-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll(
                "/helloWorld",
                List.of("object-array-key-1", "object-array-key-2")
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_java_serialized_object_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put(
                "java-serialized-pojo-key-1",
                StoredValue.javaSerializedObjectValue(
                        "com.protogemcouch.wire.SerializablePojoShapeTest$CustomerProfile",
                        javaSerializedPojoBytes()
                )
        );
        repoResult.put(
                "java-serialized-pojo-key-2",
                StoredValue.javaSerializedObjectValue(
                        "com.protogemcouch.wire.SerializablePojoShapeTest$CustomerProfile",
                        javaSerializedPojoWithNullFieldBytes()
                )
        );

        when(repository.getAll(
                "/helloWorld",
                List.of("java-serialized-pojo-key-1", "java-serialized-pojo-key-2")
        )).thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("java-serialized-pojo-key-1", "java-serialized-pojo-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll(
                "/helloWorld",
                List.of("java-serialized-pojo-key-1", "java-serialized-pojo-key-2")
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_short_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("short-key-1", StoredValue.shortValue(Short.valueOf((short) 7)));
        repoResult.put("short-key-2", StoredValue.shortValue(Short.valueOf((short) -7)));

        when(repository.getAll("/helloWorld", List.of("short-key-1", "short-key-2")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("short-key-1", "short-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("short-key-1", "short-key-2"));
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
    void handle_float_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("float-key-1", StoredValue.floatValue(7.25f));
        repoResult.put("float-key-2", StoredValue.floatValue(-7.25f));

        when(repository.getAll("/helloWorld", List.of("float-key-1", "float-key-2")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("float-key-1", "float-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("float-key-1", "float-key-2"));
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
    void handle_date_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("date-key-1", StoredValue.dateValue(new Date(1_000L)));
        repoResult.put("date-key-2", StoredValue.dateValue(new Date(1_778_265_266_000L)));

        when(repository.getAll("/helloWorld", List.of("date-key-1", "date-key-2")))
                .thenReturn(repoResult);
        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetAllHandler handler = new GetAllHandler(repository);
        GemFrame frame = mockFrame(
                100,
                stringPart("/helloWorld"),
                objectPart(List.of("date-key-1", "date-key-2")),
                intPart(0)
        );

        handler.handle(ctx, frame);

        verify(repository).getAll("/helloWorld", List.of("date-key-1", "date-key-2"));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_mixed_typed_values_in_repository_result_are_encoded_in_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        ArrayList<String> arrayList = new ArrayList<>();
        arrayList.add("one");
        arrayList.add(null);
        arrayList.add("three");

        LinkedHashMap<String, String> stringHashMap = new LinkedHashMap<>();
        stringHashMap.put("one", "value-1");
        stringHashMap.put("two", null);
        stringHashMap.put("three", "value-3");

        LinkedHashMap<String, Object> stringObjectHashMap = new LinkedHashMap<>();
        stringObjectHashMap.put("name", "rob");
        stringObjectHashMap.put("age", Integer.valueOf(42));
        stringObjectHashMap.put("active", Boolean.TRUE);
        stringObjectHashMap.put("createdAt", new Date(1_000L));

        Map<String, StoredValue> repoResult = new LinkedHashMap<>();
        repoResult.put("string-key", StoredValue.stringValue("value-1"));
        repoResult.put("character-key", StoredValue.characterValue(Character.valueOf('A')));
        repoResult.put("byte-key", StoredValue.byteValue(Byte.valueOf((byte) 7)));
        repoResult.put("byte-array-key", StoredValue.byteArrayValue(new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05
        }));
        repoResult.put("string-array-key", StoredValue.stringArrayValue(new String[] {
                "one",
                null,
                "three"
        }));
        repoResult.put("string-array-list-key", StoredValue.stringArrayListValue(arrayList));
        repoResult.put("string-hash-map-key", StoredValue.stringHashMapValue(stringHashMap));
        repoResult.put("string-object-hash-map-key", StoredValue.stringObjectHashMapValue(stringObjectHashMap));
        repoResult.put(
                "java-serialized-pojo-key",
                StoredValue.javaSerializedObjectValue(
                        "com.protogemcouch.wire.SerializablePojoShapeTest$CustomerProfile",
                        javaSerializedPojoBytes()
                )
        );
        repoResult.put("object-array-key", StoredValue.objectArrayValue(geodeObjectArrayStringIntegerBoolean()));
        repoResult.put("short-key", StoredValue.shortValue(Short.valueOf((short) 7)));
        repoResult.put("integer-key", StoredValue.integerValue(12345));
        repoResult.put("boolean-key", StoredValue.booleanValue(Boolean.TRUE));
        repoResult.put("long-key", StoredValue.longValue(9_876_543_210L));
        repoResult.put("float-key", StoredValue.floatValue(7.25f));
        repoResult.put("double-key", StoredValue.doubleValue(7.25d));
        repoResult.put("date-key", StoredValue.dateValue(new Date(1_000L)));
        repoResult.put("missing", null);

        List<String> keys = List.of(
                "string-key",
                "character-key",
                "byte-key",
                "byte-array-key",
                "string-array-key",
                "string-array-list-key",
                "string-hash-map-key",
                "string-object-hash-map-key",
                "java-serialized-pojo-key",
                "object-array-key",
                "short-key",
                "integer-key",
                "boolean-key",
                "long-key",
                "float-key",
                "double-key",
                "date-key",
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

    private static byte[] javaSerializedPojoBytes() {
        return hexToBytes(
                "aced000573720040636f6d2e70726f746f67656d636f7563682e776972652e53657269616c697a61626c65506f6a6f53686170655465737424437573746f6d657250726f66696c6500000000000000010200045a00066163746976654900036167654c000269647400124c6a6176612f6c616e672f537472696e673b4c00046e616d6571007e00017870010000002a74000a637573746f6d65722d31740003526f62"
        );
    }

    private static byte[] javaSerializedPojoWithNullFieldBytes() {
        return hexToBytes(
                "aced000573720040636f6d2e70726f746f67656d636f7563682e776972652e53657269616c697a61626c65506f6a6f53686170655465737424437573746f6d657250726f66696c6500000000000000010200045a00066163746976654900036167654c000269647400124c6a6176612f6c616e672f537472696e673b4c00046e616d6571007e00017870000000002b74000a637573746f6d65722d3270"
        );
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

}