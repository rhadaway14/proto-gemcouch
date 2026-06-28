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
 * Unit tests for 1.6.0-M1 ORDER BY pushdown: {@link OqlQuery#pushdownOrderBy()} eligibility and the
 * {@link QueryHandler} server-side-sort fast path.
 *
 * <p>The end-to-end backend ordering / top-N correctness against real N1QL is covered by
 * {@code ProtoGemCouchQueryPushdownIntegrationTest}; these unit tests pin the eligibility gate and the
 * handler wiring (which pushdown method is called, with which ORDER BY keys, and the fallbacks).
 */
class OqlQueryOrderByPushdownTest {

    // -----------------------------------------------------------------------
    // pushdownOrderBy() eligibility
    // -----------------------------------------------------------------------

    @Test
    void noOrderByReturnsEmpty() {
        assertTrue(OqlQuery.parse("SELECT * FROM /r WHERE status = 'active'")
                .pushdownOrderBy().isEmpty());
    }

    @Test
    void singleFieldDefaultAscending() {
        var result = OqlQuery.parse("SELECT * FROM /r ORDER BY amount").pushdownOrderBy();
        assertTrue(result.isPresent());
        assertEquals(1, result.get().size());
        assertEquals("amount", result.get().get(0).field());
        assertTrue(result.get().get(0).ascending());
    }

    @Test
    void singleFieldDescending() {
        var result = OqlQuery.parse("SELECT * FROM /r ORDER BY amount DESC").pushdownOrderBy();
        assertTrue(result.isPresent());
        assertFalse(result.get().get(0).ascending());
    }

    @Test
    void explicitAscending() {
        var result = OqlQuery.parse("SELECT * FROM /r ORDER BY name ASC").pushdownOrderBy();
        assertTrue(result.isPresent());
        assertTrue(result.get().get(0).ascending());
    }

    @Test
    void multiFieldKeysPreserveOrderAndDirection() {
        var result = OqlQuery.parse("SELECT * FROM /r ORDER BY status ASC, amount DESC")
                .pushdownOrderBy();
        assertTrue(result.isPresent());
        List<OqlQuery.OrderByKey> keys = result.get();
        assertEquals(2, keys.size());
        assertEquals("status", keys.get(0).field());
        assertTrue(keys.get(0).ascending());
        assertEquals("amount", keys.get(1).field());
        assertFalse(keys.get(1).ascending());
    }

    @Test
    void aliasQualifiedFieldIsTopLevel() {
        var result = OqlQuery.parse("SELECT * FROM /r r ORDER BY r.amount").pushdownOrderBy();
        assertTrue(result.isPresent());
        assertEquals("amount", result.get().get(0).field());
    }

    @Test
    void nestedFieldNotEligible() {
        assertTrue(OqlQuery.parse("SELECT * FROM /r ORDER BY address.zip")
                .pushdownOrderBy().isEmpty());
    }

    @Test
    void arrayIndexFieldNotEligible() {
        assertTrue(OqlQuery.parse("SELECT * FROM /r ORDER BY tags[0]")
                .pushdownOrderBy().isEmpty());
    }

    @Test
    void mixedEligibleAndNestedKeysNotEligible() {
        // One nested key disqualifies the whole ORDER BY from pushdown (it still sorts in-shim).
        assertTrue(OqlQuery.parse("SELECT * FROM /r ORDER BY status, address.zip")
                .pushdownOrderBy().isEmpty());
    }

    // -----------------------------------------------------------------------
    // QueryHandler wiring: ORDER BY pushdown fast path
    // -----------------------------------------------------------------------

    @Test
    void handlerPushesOrderByWithWhere() {
        AtomicReference<List<OqlQuery.OrderByKey>> capturedOrder = new AtomicReference<>();
        AtomicBoolean getAllCalled = new AtomicBoolean(false);

        Repository repo = new StubRepository() {
            @Override
            public Optional<List<StoredValue>> queryPushdownByPredicates(
                    String region, List<OqlQuery.FieldPredicate> predicates,
                    List<OqlQuery.OrderByKey> orderBy, int limit) {
                capturedOrder.set(orderBy);
                return Optional.of(List.of(mapValue("amount", 1)));
            }

            @Override
            public Map<String, StoredValue> getAll(String region, List<String> keys) {
                getAllCalled.set(true);
                return Collections.emptyMap();
            }
        };

        runHandler(repo, "SELECT * FROM /r WHERE status = 'active' ORDER BY amount DESC");

        assertNotNull(capturedOrder.get(), "ordered pushdown should have been called");
        assertEquals(1, capturedOrder.get().size());
        assertEquals("amount", capturedOrder.get().get(0).field());
        assertFalse(capturedOrder.get().get(0).ascending());
        assertFalse(getAllCalled.get(), "no full-region scan when ordered pushdown succeeds");
    }

    @Test
    void handlerPushesRegionOrderedTopNWithNoWhere() {
        AtomicReference<List<OqlQuery.FieldPredicate>> capturedPreds = new AtomicReference<>();
        AtomicReference<Integer> capturedLimit = new AtomicReference<>();

        Repository repo = new StubRepository() {
            @Override
            public Optional<List<StoredValue>> queryPushdownByPredicates(
                    String region, List<OqlQuery.FieldPredicate> predicates,
                    List<OqlQuery.OrderByKey> orderBy, int limit) {
                capturedPreds.set(predicates);
                capturedLimit.set(limit);
                return Optional.of(List.of(mapValue("name", "a")));
            }
        };

        runHandler(repo, "SELECT * FROM /r ORDER BY name LIMIT 5");

        assertNotNull(capturedPreds.get(), "ordered pushdown should have been called");
        assertTrue(capturedPreds.get().isEmpty(), "no WHERE → empty predicate list");
        assertEquals(5, capturedLimit.get(), "LIMIT pushed for server-side top-N");
    }

    @Test
    void handlerPushesOrderByForOrWhere() {
        AtomicReference<List<OqlQuery.OrderByKey>> capturedOrder = new AtomicReference<>();

        Repository repo = new StubRepository() {
            @Override
            public Optional<List<StoredValue>> queryPushdownByOrGroups(
                    String region, List<List<OqlQuery.FieldPredicate>> orGroups,
                    List<OqlQuery.OrderByKey> orderBy, int limit) {
                capturedOrder.set(orderBy);
                return Optional.of(List.of(mapValue("amount", 1)));
            }
        };

        runHandler(repo, "SELECT * FROM /r WHERE status = 'a' OR status = 'b' ORDER BY amount");

        assertNotNull(capturedOrder.get(), "OR-group ordered pushdown should have been called");
        assertEquals("amount", capturedOrder.get().get(0).field());
    }

    @Test
    void handlerFallsBackToScanWhenOrderedPushdownEmpty() {
        AtomicBoolean orderedCalled = new AtomicBoolean(false);
        AtomicBoolean getAllCalled = new AtomicBoolean(false);

        Repository repo = new StubRepository() {
            @Override
            public Optional<List<StoredValue>> queryPushdownByPredicates(
                    String region, List<OqlQuery.FieldPredicate> predicates,
                    List<OqlQuery.OrderByKey> orderBy, int limit) {
                orderedCalled.set(true);
                return Optional.empty();
            }

            @Override
            public Map<String, StoredValue> getAll(String region, List<String> keys) {
                getAllCalled.set(true);
                return Collections.emptyMap();
            }
        };

        runHandler(repo, "SELECT * FROM /r WHERE status = 'active' ORDER BY amount");

        assertTrue(orderedCalled.get(), "ordered pushdown attempted");
        assertTrue(getAllCalled.get(), "falls back to the full-region scan");
    }

    @Test
    void handlerSkipsOrderByPushdownForNestedKey() {
        AtomicBoolean orderedCalled = new AtomicBoolean(false);

        Repository repo = new StubRepository() {
            @Override
            public Optional<List<StoredValue>> queryPushdownByPredicates(
                    String region, List<OqlQuery.FieldPredicate> predicates,
                    List<OqlQuery.OrderByKey> orderBy, int limit) {
                orderedCalled.set(true);
                return Optional.of(List.of());
            }
        };

        // Nested ORDER BY key → not pushdown-eligible → the ordered fast path is never attempted.
        runHandler(repo, "SELECT * FROM /r WHERE status = 'active' ORDER BY address.zip");
        assertFalse(orderedCalled.get(), "ordered pushdown must not fire for a nested ORDER BY key");
    }

    @Test
    void handlerSkipsOrderByPushdownWhenDisabled() {
        AtomicBoolean orderedCalled = new AtomicBoolean(false);

        Repository repo = new StubRepository() {
            @Override
            public Optional<List<StoredValue>> queryPushdownByPredicates(
                    String region, List<OqlQuery.FieldPredicate> predicates,
                    List<OqlQuery.OrderByKey> orderBy, int limit) {
                orderedCalled.set(true);
                return Optional.of(List.of());
            }
        };

        QueryHandler handler = new QueryHandler(repo, OqlQuery.MAP_RESOLVER, false);
        handler.handle(new CapturingContext(), makeFrame("SELECT * FROM /r ORDER BY amount"));
        assertFalse(orderedCalled.get(), "ordered pushdown must not fire when pushdown is disabled");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static StoredValue mapValue(String field, Object value) {
        return StoredValue.stringObjectHashMapValue(Map.of(field, value));
    }

    private static List<byte[]> runHandler(Repository repo, String oql) {
        QueryHandler handler = new QueryHandler(repo, OqlQuery.MAP_RESOLVER, true);
        CapturingContext ctx = new CapturingContext();
        handler.handle(ctx, makeFrame(oql));
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
