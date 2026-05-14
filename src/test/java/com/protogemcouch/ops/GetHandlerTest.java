package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;

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
    void handle_existing_byte_array_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-byte-array-key"))
                .thenReturn(StoredValue.byteArrayValue(new byte[] {
                        0x01, 0x02, 0x03, 0x04, 0x05
                }));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-byte-array-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-byte-array-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_int_array_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-int-array-key"))
                .thenReturn(StoredValue.intArrayValue(new int[] {
                        1,
                        42,
                        -7,
                        Integer.MAX_VALUE,
                        Integer.MIN_VALUE
                }));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-int-array-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-int-array-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_string_array_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-string-array-key"))
                .thenReturn(StoredValue.stringArrayValue(new String[] {
                        "one", null, "three"
                }));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-string-array-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-string-array-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_string_array_list_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        ArrayList<String> value = new ArrayList<>();
        value.add("one");
        value.add(null);
        value.add("three");

        when(repository.get("/helloWorld::my-string-array-list-key"))
                .thenReturn(StoredValue.stringArrayListValue(value));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-string-array-list-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-string-array-list-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_string_hash_map_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        LinkedHashMap<String, String> value = new LinkedHashMap<>();
        value.put("one", "value-1");
        value.put("two", null);
        value.put("three", "value-3");

        when(repository.get("/helloWorld::my-string-hash-map-key"))
                .thenReturn(StoredValue.stringHashMapValue(value));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-string-hash-map-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-string-hash-map-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_string_object_hash_map_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("name", "rob");
        value.put("age", Integer.valueOf(42));
        value.put("active", Boolean.TRUE);
        value.put("createdAt", new Date(1_000L));

        when(repository.get("/helloWorld::my-string-object-hash-map-key"))
                .thenReturn(StoredValue.stringObjectHashMapValue(value));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-string-object-hash-map-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-string-object-hash-map-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_java_serialized_object_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(repository.get("/helloWorld::my-java-serialized-pojo-key"))
                .thenReturn(StoredValue.javaSerializedObjectValue(
                        "com.protogemcouch.wire.SerializablePojoShapeTest$CustomerProfile",
                        hexToBytes("aced000573720040636f6d2e70726f746f67656d636f7563682e776972652e53657269616c697a61626c65506f6a6f53686170655465737424437573746f6d657250726f66696c6500000000000000010200045a00066163746976654900036167654c000269647400124c6a6176612f6c616e672f537472696e673b4c00046e616d6571007e00017870010000002a74000a637573746f6d65722d31740003526f62")
                ));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-java-serialized-pojo-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-java-serialized-pojo-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_object_array_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        byte[] objectArrayPayload = hexToBytes(
                "34032b5700106a6176612e6c616e672e4f626a6563745700036f6e65390000002a3501"
        );

        when(repository.get("/helloWorld::my-object-array-key"))
                .thenReturn(StoredValue.objectArrayValue(objectArrayPayload));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-object-array-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-object-array-key");
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_existing_object_array_list_value_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        byte[] objectArrayListPayload = hexToBytes(
                "41035700036f6e65390000002a3501"
        );

        when(repository.get("/helloWorld::my-object-array-list-key"))
                .thenReturn(StoredValue.objectArrayListValue(objectArrayListPayload));

        when(ctx.writeAndFlush(any())).thenReturn(null);

        GetHandler handler = new GetHandler(repository);
        GemFrame frame = mockFrame(
                0,
                stringPart("/helloWorld"),
                stringPart("my-object-array-list-key")
        );

        handler.handle(ctx, frame);

        verify(repository).get("/helloWorld::my-object-array-list-key");
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