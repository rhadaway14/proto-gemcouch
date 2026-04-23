package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import static com.protogemcouch.testsupport.FrameTestUtil.intPart;
import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.objectPart;
import static com.protogemcouch.testsupport.FrameTestUtil.part;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
import static org.mockito.ArgumentMatchers.any;
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
                part(new byte[] {0x0c}),
                intPart(0),
                stringPart("my-key"),
                objectPart("5"),
                objectPart("my-value"),
                part(new byte[] {0x02, 0x00, 0x01})
        );

        handler.handle(ctx, frame);

        verify(repository).put("/helloWorld::my-key", "my-value");
        verify(ctx).writeAndFlush(any());
    }
}