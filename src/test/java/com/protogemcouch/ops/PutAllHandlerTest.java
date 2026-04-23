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

class PutAllHandlerTest {

    @Test
    void handle_stores_all_entries_and_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        when(ctx.writeAndFlush(any())).thenReturn(null);

        PutAllHandler handler = new PutAllHandler(repository);
        GemFrame frame = mockFrame(
                56,
                stringPart("/helloWorld"),
                part(new byte[] {0x02, 0x00, 0x01}),
                intPart(0),
                intPart(3),
                intPart(2),
                stringPart("key-1"),
                objectPart("value-1"),
                stringPart("key-2"),
                objectPart("value-2")
        );

        handler.handle(ctx, frame);

        verify(repository).put("/helloWorld::key-1", "value-1");
        verify(repository).put("/helloWorld::key-2", "value-2");
        verify(ctx).writeAndFlush(any());
    }
}