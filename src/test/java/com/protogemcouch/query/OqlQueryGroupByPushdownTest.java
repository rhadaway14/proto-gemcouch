package com.protogemcouch.query;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.ops.QueryHandler;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.wire.GemFrame;
import com.protogemcouch.wire.GemPart;
import com.protogemcouch.wire.MessageTypes;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for M2 GROUP BY pushdown eligibility and QueryHandler wiring.
 */
class OqlQueryGroupByPushdownTest {

    // -----------------------------------------------------------------------
    // pushdownGroupBy() eligibility
    // -----------------------------------------------------------------------

    @Test
    void eligibleSingleKeyCountStarNoWhere() {
        OqlQuery q = OqlQuery.parse("SELECT status, COUNT(*) FROM /r GROUP BY status");
        Optional<List<OqlQuery.FieldPredicate>> result = q.pushdownGroupBy();
        assertTrue(result.isPresent(), "single-key COUNT(*) no-WHERE should be eligible");
        assertTrue(result.get().isEmpty(), "no WHERE → empty predicate list");
    }

    @Test
    void eligibleSingleKeyCountStarWithAndWhere() {
        OqlQuery q = OqlQuery.parse("SELECT status, COUNT(*) FROM /r WHERE type = 'order' GROUP BY status");
        Optional<List<OqlQuery.FieldPredicate>> result = q.pushdownGroupBy();
        assertTrue(result.isPresent(), "single-key + AND WHERE should be eligible");
        assertEquals(1, result.get().size());
        assertEquals("type", result.get().get(0).field());
    }

    @Test
    void eligibleSingleKeySum() {
        OqlQuery q = OqlQuery.parse("SELECT category, SUM(amount) FROM /r GROUP BY category");
        Optional<List<OqlQuery.FieldPredicate>> result = q.pushdownGroupBy();
        assertTrue(result.isPresent(), "single-key SUM should be eligible");
    }

    @Test
    void ineligibleOrWhere() {
        OqlQuery q = OqlQuery.parse("SELECT status, COUNT(*) FROM /r WHERE status = 'a' OR status = 'b' GROUP BY status");
        Optional<List<OqlQuery.FieldPredicate>> result = q.pushdownGroupBy();
        assertFalse(result.isPresent(), "OR WHERE makes GROUP BY pushdown ineligible");
    }

    @Test
    void ineligibleWhenNotGroupBy() {
        OqlQuery q = OqlQuery.parse("SELECT COUNT(*) FROM /r");
        assertFalse(q.pushdownGroupBy().isPresent(), "non-GROUP BY query must return empty");
    }

    // -----------------------------------------------------------------------
    // QueryHandler wiring
    // -----------------------------------------------------------------------

    @Test
    void handlerUsesGroupByPushdownWhenEligible() {
        AtomicBoolean pushdownCalled = new AtomicBoolean(false);
        List<List<Object>> fakeResult = List.of(List.of("active", 5), List.of("closed", 3));

        Repository repo = new StubRepository() {
            @Override
            public Optional<List<List<Object>>> groupByPushdown(String region,
                    List<OqlQuery.GroupByColumn> cols, List<OqlQuery.FieldPredicate> predicates) {
                pushdownCalled.set(true);
                return Optional.of(fakeResult);
            }
        };

        List<byte[]> responses = runHandler(repo,
                "SELECT status, COUNT(*) FROM /r GROUP BY status", true);

        assertTrue(pushdownCalled.get(), "groupByPushdown() should have been called");
        assertFalse(responses.isEmpty(), "should have received a response");
    }

    @Test
    void handlerFallsBackWhenPushdownEmpty() {
        AtomicBoolean pushdownCalled = new AtomicBoolean(false);

        Repository repo = new StubRepository() {
            @Override
            public Optional<List<List<Object>>> groupByPushdown(String region,
                    List<OqlQuery.GroupByColumn> cols, List<OqlQuery.FieldPredicate> predicates) {
                pushdownCalled.set(true);
                return Optional.empty();
            }
        };

        List<byte[]> responses = runHandler(repo,
                "SELECT status, COUNT(*) FROM /r GROUP BY status", true);

        assertTrue(pushdownCalled.get(), "groupByPushdown() should have been attempted");
        assertFalse(responses.isEmpty(), "should have received a fallback response");
    }

    @Test
    void handlerSkipsGroupByPushdownForOrWhere() {
        AtomicBoolean pushed = new AtomicBoolean(false);

        Repository repo = new StubRepository() {
            @Override
            public Optional<List<List<Object>>> groupByPushdown(String region,
                    List<OqlQuery.GroupByColumn> cols, List<OqlQuery.FieldPredicate> predicates) {
                pushed.set(true);
                return Optional.empty();
            }
        };

        runHandler(repo,
                "SELECT status, COUNT(*) FROM /r WHERE status = 'a' OR status = 'b' GROUP BY status",
                true);

        assertFalse(pushed.get(), "groupByPushdown() must not be called for OR WHEREs");
    }

    @Test
    void handlerSkipsGroupByPushdownWhenDisabled() {
        AtomicBoolean pushed = new AtomicBoolean(false);

        Repository repo = new StubRepository() {
            @Override
            public Optional<List<List<Object>>> groupByPushdown(String region,
                    List<OqlQuery.GroupByColumn> cols, List<OqlQuery.FieldPredicate> predicates) {
                pushed.set(true);
                return Optional.empty();
            }
        };

        runHandler(repo, "SELECT status, COUNT(*) FROM /r GROUP BY status", false);

        assertFalse(pushed.get(), "groupByPushdown() must not be called when pushdown disabled");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static List<byte[]> runHandler(Repository repo, String oql, boolean pushdownEnabled) {
        QueryHandler handler = new QueryHandler(repo, OqlQuery.MAP_RESOLVER, pushdownEnabled);
        GemFrame frame = makeFrame(oql);
        CapturingContext ctx = new CapturingContext();
        handler.handle(ctx, frame);
        return ctx.responses;
    }

    private static GemFrame makeFrame(String oql) {
        byte[] oqlBytes = oql.getBytes(StandardCharsets.UTF_8);
        GemPart part = new GemPart(oqlBytes.length, (byte) 0x00, oqlBytes);
        GemFrame frame = mock(GemFrame.class);
        when(frame.getMessageType()).thenReturn(MessageTypes.QUERY);
        when(frame.getTransactionId()).thenReturn(1);
        when(frame.getParts()).thenReturn(List.of(part));
        return frame;
    }

    private static class StubRepository implements Repository {
        @Override public StoredValue get(String docId) { return null; }
        @Override public Map<String, StoredValue> getAll(String region, List<String> keys) { return Collections.emptyMap(); }
        @Override public void put(String docId, StoredValue value) {}
        @Override public void remove(String docId) {}
        @Override public boolean containsKey(String docId) { return false; }
        @Override public boolean containsValueForKey(String docId) { return false; }
        @Override public int size(String region) { return 0; }
        @Override public List<String> keySet(String region) { return Collections.emptyList(); }
    }

    private static class CapturingContext implements ChannelHandlerContext {
        final List<byte[]> responses = new ArrayList<>();

        @Override
        public io.netty.channel.ChannelFuture writeAndFlush(Object msg) {
            if (msg instanceof io.netty.buffer.ByteBuf buf) {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), bytes);
                responses.add(bytes);
            }
            return null;
        }

        @Override public io.netty.channel.Channel channel() { return null; }
        @Override public io.netty.util.concurrent.EventExecutor executor() { return null; }
        @Override public String name() { return "stub"; }
        @Override public io.netty.channel.ChannelHandler handler() { return null; }
        @Override public boolean isRemoved() { return false; }
        @Override public ChannelHandlerContext fireChannelRegistered() { return this; }
        @Override public ChannelHandlerContext fireChannelUnregistered() { return this; }
        @Override public ChannelHandlerContext fireChannelActive() { return this; }
        @Override public ChannelHandlerContext fireChannelInactive() { return this; }
        @Override public ChannelHandlerContext fireExceptionCaught(Throwable cause) { return this; }
        @Override public ChannelHandlerContext fireUserEventTriggered(Object evt) { return this; }
        @Override public ChannelHandlerContext fireChannelRead(Object msg) { return this; }
        @Override public ChannelHandlerContext fireChannelReadComplete() { return this; }
        @Override public ChannelHandlerContext fireChannelWritabilityChanged() { return this; }
        @Override public io.netty.channel.ChannelFuture bind(java.net.SocketAddress addr) { return null; }
        @Override public io.netty.channel.ChannelFuture connect(java.net.SocketAddress addr) { return null; }
        @Override public io.netty.channel.ChannelFuture connect(java.net.SocketAddress r, java.net.SocketAddress l) { return null; }
        @Override public io.netty.channel.ChannelFuture disconnect() { return null; }
        @Override public io.netty.channel.ChannelFuture close() { return null; }
        @Override public io.netty.channel.ChannelFuture deregister() { return null; }
        @Override public io.netty.channel.ChannelFuture bind(java.net.SocketAddress addr, io.netty.channel.ChannelPromise p) { return null; }
        @Override public io.netty.channel.ChannelFuture connect(java.net.SocketAddress addr, io.netty.channel.ChannelPromise p) { return null; }
        @Override public io.netty.channel.ChannelFuture connect(java.net.SocketAddress r, java.net.SocketAddress l, io.netty.channel.ChannelPromise p) { return null; }
        @Override public io.netty.channel.ChannelFuture disconnect(io.netty.channel.ChannelPromise p) { return null; }
        @Override public io.netty.channel.ChannelFuture close(io.netty.channel.ChannelPromise p) { return null; }
        @Override public io.netty.channel.ChannelFuture deregister(io.netty.channel.ChannelPromise p) { return null; }
        @Override public ChannelHandlerContext read() { return this; }
        @Override public io.netty.channel.ChannelFuture write(Object msg) { return null; }
        @Override public io.netty.channel.ChannelFuture write(Object msg, io.netty.channel.ChannelPromise p) { return null; }
        @Override public ChannelHandlerContext flush() { return this; }
        @Override public io.netty.channel.ChannelFuture writeAndFlush(Object msg, io.netty.channel.ChannelPromise p) { return null; }
        @Override public io.netty.channel.ChannelPipeline pipeline() { return null; }
        @Override public io.netty.buffer.ByteBufAllocator alloc() { return io.netty.buffer.UnpooledByteBufAllocator.DEFAULT; }
        @Override public io.netty.channel.ChannelPromise newPromise() { return null; }
        @Override public io.netty.channel.ChannelProgressivePromise newProgressivePromise() { return null; }
        @Override public io.netty.channel.ChannelFuture newSucceededFuture() { return null; }
        @Override public io.netty.channel.ChannelFuture newFailedFuture(Throwable cause) { return null; }
        @Override public io.netty.channel.ChannelPromise voidPromise() { return null; }
        @Override public <T> io.netty.util.Attribute<T> attr(io.netty.util.AttributeKey<T> key) { return null; }
        @Override public <T> boolean hasAttr(io.netty.util.AttributeKey<T> key) { return false; }
    }
}
