package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.wire.GemFrame;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.protogemcouch.testsupport.FrameTestUtil.intPart;
import static com.protogemcouch.testsupport.FrameTestUtil.mockFrame;
import static com.protogemcouch.testsupport.FrameTestUtil.objectPart;
import static com.protogemcouch.testsupport.FrameTestUtil.stringPart;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GetAllHandlerTest {

    @Test
    void handle_calls_repository_getAll_and_writes_response() {
        Repository repository = mock(Repository.class);
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        Map<String, String> repoResult = new LinkedHashMap<>();
        repoResult.put("key-1", "value-1");
        repoResult.put("key-2", "value-2");
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
}