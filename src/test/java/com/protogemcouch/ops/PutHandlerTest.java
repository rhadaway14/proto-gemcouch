package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;

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
    void handle_parses_character_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-character-key"),
                objectPart("5"),
                part(new byte[]{
                        0x36,
                        0x00, 0x41
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-character-key"),
                eq(StoredValue.characterValue(Character.valueOf('A')))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_byte_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-byte-key"),
                objectPart("5"),
                part(new byte[]{
                        0x37,
                        0x07
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-byte-key"),
                eq(StoredValue.byteValue(Byte.valueOf((byte) 7)))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_byte_array_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-byte-array-key"),
                objectPart("5"),
                part(new byte[]{
                        0x2e,
                        0x05,
                        0x01, 0x02, 0x03, 0x04, 0x05
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-byte-array-key"),
                eq(StoredValue.byteArrayValue(new byte[]{
                        0x01, 0x02, 0x03, 0x04, 0x05
                }))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_string_array_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-string-array-key"),
                objectPart("5"),
                part(new byte[]{
                        0x40,
                        0x03,

                        0x57, 0x00, 0x03,
                        0x6f, 0x6e, 0x65,

                        0x45,

                        0x57, 0x00, 0x05,
                        0x74, 0x68, 0x72, 0x65, 0x65
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-string-array-key"),
                eq(StoredValue.stringArrayValue(new String[]{
                        "one", null, "three"
                }))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_string_array_list_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        ArrayList<String> expected = new ArrayList<>();
        expected.add("one");
        expected.add(null);
        expected.add("three");

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-string-array-list-key"),
                objectPart("5"),
                part(new byte[]{
                        0x41,
                        0x03,

                        0x57, 0x00, 0x03,
                        0x6f, 0x6e, 0x65,

                        0x29,

                        0x57, 0x00, 0x05,
                        0x74, 0x68, 0x72, 0x65, 0x65
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-string-array-list-key"),
                eq(StoredValue.stringArrayListValue(expected))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_empty_string_hash_map_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        LinkedHashMap<String, String> expected = new LinkedHashMap<>();

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-empty-string-hash-map-key"),
                objectPart("5"),
                part(new byte[]{
                        0x43,
                        0x00
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-empty-string-hash-map-key"),
                eq(StoredValue.stringHashMapValue(expected))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_string_hash_map_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        LinkedHashMap<String, String> expected = new LinkedHashMap<>();
        expected.put("one", "value-1");
        expected.put("two", null);
        expected.put("three", "value-3");

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-string-hash-map-key"),
                objectPart("5"),
                part(hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400036f6e6574000776616c75652d3174000374776f70740005746872656574000776616c75652d337800")),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-string-hash-map-key"),
                eq(StoredValue.stringHashMapValue(expected))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_string_object_hash_map_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        LinkedHashMap<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "rob");
        expected.put("age", Integer.valueOf(42));
        expected.put("active", Boolean.TRUE);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-string-object-hash-map-key"),
                objectPart("5"),
                part(hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400046e616d65740003726f62740003616765737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b02000078700000002a740006616374697665737200116a6176612e6c616e672e426f6f6c65616ecd207280d59cfaee0200015a000576616c75657870017800")),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-string-object-hash-map-key"),
                eq(StoredValue.stringObjectHashMapValue(expected))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_string_object_hash_map_with_null_and_date_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        LinkedHashMap<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "rob");
        expected.put("middleName", null);
        expected.put("createdAt", new Date(1_000L));

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-string-object-hash-map-date-key"),
                objectPart("5"),
                part(hexToBytes("2caced0005737200176a6176612e7574696c2e4c696e6b6564486173684d617034c04e5c106cc0fb0200015a000b6163636573734f72646572787200116a6176612e7574696c2e486173684d61700507dac1c31660d103000246000a6c6f6164466163746f724900097468726573686f6c6478703f4000000000000c770800000010000000037400046e616d65740003726f6274000a6d6964646c654e616d65707400096372656174656441747372000e6a6176612e7574696c2e44617465686a81014b5974190300007870770800000000000003e8787800")),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-string-object-hash-map-date-key"),
                eq(StoredValue.stringObjectHashMapValue(expected))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_java_serialized_pojo_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        byte[] payload = hexToBytes(
                "2caced000573720040636f6d2e70726f746f67656d636f7563682e776972652e53657269616c697a61626c65506f6a6f53686170655465737424437573746f6d657250726f66696c6500000000000000010200045a00066163746976654900036167654c000269647400124c6a6176612f6c616e672f537472696e673b4c00046e616d6571007e00017870010000002a74000a637573746f6d65722d31740003526f62"
        );

        byte[] expectedSerializedValue = hexToBytes(
                "aced000573720040636f6d2e70726f746f67656d636f7563682e776972652e53657269616c697a61626c65506f6a6f53686170655465737424437573746f6d657250726f66696c6500000000000000010200045a00066163746976654900036167654c000269647400124c6a6176612f6c616e672f537472696e673b4c00046e616d6571007e00017870010000002a74000a637573746f6d65722d31740003526f62"
        );

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-java-serialized-pojo-key"),
                objectPart("5"),
                part(payload),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-java-serialized-pojo-key"),
                eq(StoredValue.javaSerializedObjectValue(
                        "com.protogemcouch.wire.SerializablePojoShapeTest$CustomerProfile",
                        expectedSerializedValue
                ))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_raw_byte_array_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-raw-byte-array-key"),
                objectPart("5"),
                part(new byte[]{
                        0x00, 0x01, 0x02, 0x03
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-raw-byte-array-key"),
                eq(StoredValue.byteArrayValue(new byte[]{
                        0x00, 0x01, 0x02, 0x03
                }))
        );
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void handle_parses_short_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-short-key"),
                objectPart("5"),
                part(new byte[]{
                        0x38,
                        0x00, 0x07
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-short-key"),
                eq(StoredValue.shortValue(Short.valueOf((short) 7)))
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
    void handle_parses_date_put_and_stores_value() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutHandler handler = new PutHandler(repository);
        GemFrame frame = mockFrame(
                7,
                stringPart("/helloWorld"),
                part(new byte[]{0x0c}),
                intPart(0),
                stringPart("my-date-key"),
                objectPart("5"),
                part(new byte[]{
                        0x3d,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x03, (byte) 0xe8
                }),
                part(new byte[]{0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put(
                eq("/helloWorld::my-date-key"),
                eq(StoredValue.dateValue(new Date(1_000L)))
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
                part(new byte[]{0x29}),
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
