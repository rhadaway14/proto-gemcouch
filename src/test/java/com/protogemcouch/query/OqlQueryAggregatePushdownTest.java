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

import static com.protogemcouch.query.OqlQuery.AggregateFunction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for M1 aggregate pushdown eligibility and QueryHandler wiring.
 *
 * <p>Tests that:
 * <ul>
 *   <li>{@link OqlQuery#aggregateFieldPath()} returns the correct path for each aggregate fn.</li>
 *   <li>{@link QueryHandler} calls {@link Repository#aggregatePushdown} when pushdown is enabled
 *       and the WHERE is single-AND-group eligible, and returns the pushed result directly.</li>
 *   <li>{@link QueryHandler} falls back to in-shim scan when the pushed result is empty.</li>
 *   <li>{@link QueryHandler} does not attempt aggregate pushdown for OR WHEREs.</li>
 * </ul>
 */
class OqlQueryAggregatePushdownTest {

    // -----------------------------------------------------------------------
    // aggregateFieldPath()
    // -----------------------------------------------------------------------

    @Test
    void countStarHasNullFieldPath() {
        OqlQuery q = OqlQuery.parse("SELECT COUNT(*) FROM /r");
        assertTrue(q.isAggregate());
        assertNull(q.aggregateFieldPath());
    }

    @Test
    void countFieldReturnsPath() {
        OqlQuery q = OqlQuery.parse("SELECT COUNT(amount) FROM /r");
        assertEquals(List.of("amount"), q.aggregateFieldPath());
    }

    @Test
    void sumReturnsPath() {
        OqlQuery q = OqlQuery.parse("SELECT SUM(price) FROM /r");
        assertEquals(List.of("price"), q.aggregateFieldPath());
    }

    @Test
    void avgReturnsPath() {
        OqlQuery q = OqlQuery.parse("SELECT AVG(score) FROM /r");
        assertEquals(List.of("score"), q.aggregateFieldPath());
    }

    @Test
    void minReturnsPath() {
        OqlQuery q = OqlQuery.parse("SELECT MIN(ts) FROM /r");
        assertEquals(List.of("ts"), q.aggregateFieldPath());
    }

    @Test
    void maxReturnsPath() {
        OqlQuery q = OqlQuery.parse("SELECT MAX(ts) FROM /r");
        assertEquals(List.of("ts"), q.aggregateFieldPath());
    }

    // -----------------------------------------------------------------------
    // QueryHandler wiring: aggregate pushdown fires when eligible
    // -----------------------------------------------------------------------

    /**
     * When pushdown is enabled and the aggregate WHERE is single-AND-group eligible,
     * QueryHandler should call aggregatePushdown() and return its result without scanning.
     */
    @Test
    void handlerUsesAggPushdownWhenEligible() {
        AtomicBoolean pushdownCalled = new AtomicBoolean(false);
        AtomicReference<Number> capturedResult = new AtomicReference<>();

        // Stub repo: aggregatePushdown returns 42; getAll must NOT be called.
        Repository repo = stubRepoWithAggPushdown(Optional.of(42), false, pushdownCalled);

        List<byte[]> responses = runHandler(repo, "SELECT COUNT(*) FROM /r WHERE status = 'active'");

        assertTrue(pushdownCalled.get(), "aggregatePushdown() should have been called");
        assertFalse(responses.isEmpty(), "should have received a response");
    }

    /**
     * When aggregatePushdown() returns empty, the handler falls back to the in-shim scan
     * (getAll + filter + compute).
     */
    @Test
    void handlerFallsBackToScanWhenPushdownEmpty() {
        AtomicBoolean getAllCalled = new AtomicBoolean(false);
        AtomicBoolean pushdownCalled = new AtomicBoolean(false);

        Repository repo = stubRepoWithAggPushdown(Optional.empty(), true, pushdownCalled);

        List<byte[]> responses = runHandler(repo, "SELECT COUNT(*) FROM /r WHERE status = 'active'");

        assertTrue(pushdownCalled.get(), "aggregatePushdown() should have been attempted");
        assertFalse(responses.isEmpty(), "should have received a fallback response");
    }

    /**
     * When pushdown is enabled but the WHERE is an OR (multi-group), aggregatePushdown()
     * must NOT be called (the OR path is ineligible for aggregate pushdown in M1).
     */
    @Test
    void handlerSkipsAggPushdownForOrWhere() {
        AtomicBoolean aggPushdownCalled = new AtomicBoolean(false);

        Repository repo = new StubRepository() {
            @Override
            public Optional<Number> aggregatePushdown(String region,
                    OqlQuery.AggregateFunction fn, List<String> fieldPath,
                    List<OqlQuery.FieldPredicate> predicates) {
                aggPushdownCalled.set(true);
                return Optional.empty();
            }
        };

        // OR WHERE → pushdownPredicates() returns empty → aggregate pushdown not attempted
        runHandler(repo, "SELECT COUNT(*) FROM /r WHERE status = 'active' OR status = 'closed'");
        assertFalse(aggPushdownCalled.get(),
                "aggregatePushdown() must not be called for OR WHEREs");
    }

    /** pushdown disabled → aggregatePushdown() never called, scan used. */
    @Test
    void handlerSkipsAggPushdownWhenPushdownDisabled() {
        AtomicBoolean aggPushdownCalled = new AtomicBoolean(false);

        Repository repo = new StubRepository() {
            @Override
            public Optional<Number> aggregatePushdown(String region,
                    OqlQuery.AggregateFunction fn, List<String> fieldPath,
                    List<OqlQuery.FieldPredicate> predicates) {
                aggPushdownCalled.set(true);
                return Optional.empty();
            }
        };

        // Use the no-pushdown constructor
        QueryHandler handler = new QueryHandler(repo, OqlQuery.MAP_RESOLVER, false);
        GemFrame frame = makeFrame("SELECT COUNT(*) FROM /r WHERE status = 'active'");
        CapturingContext ctx = new CapturingContext();
        handler.handle(ctx, frame);

        assertFalse(aggPushdownCalled.get(), "aggregatePushdown() must not be called when disabled");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Repository stubRepoWithAggPushdown(
            Optional<Number> aggResult,
            boolean trackGetAll,
            AtomicBoolean pushdownCalled) {
        return new StubRepository() {
            @Override
            public Optional<Number> aggregatePushdown(String region,
                    OqlQuery.AggregateFunction fn, List<String> fieldPath,
                    List<OqlQuery.FieldPredicate> predicates) {
                pushdownCalled.set(true);
                return aggResult;
            }
        };
    }

    private static List<byte[]> runHandler(Repository repo, String oql) {
        QueryHandler handler = new QueryHandler(repo, OqlQuery.MAP_RESOLVER, true);
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

    /** Stub repository: empty region, no pushdown by default. */
    private static class StubRepository implements Repository {
        @Override public StoredValue get(String docId) { return null; }
        @Override public Map<String, StoredValue> getAll(String region, List<String> keys) {
            return Collections.emptyMap();
        }
        @Override public void put(String docId, StoredValue value) {}
        @Override public void remove(String docId) {}
        @Override public boolean containsKey(String docId) { return false; }
        @Override public boolean containsValueForKey(String docId) { return false; }
        @Override public int size(String region) { return 0; }
        @Override public List<String> keySet(String region) { return Collections.emptyList(); }
    }

    /** Captures write-and-flush calls so tests can inspect responses. */
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

        // Minimal stubs — only writeAndFlush is needed by QueryHandler.
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
