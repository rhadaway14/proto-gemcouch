package com.protogemcouch.couchbase;

import com.protogemcouch.serialization.StoredValue;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A {@link Repository} decorator that wraps each backend call in an OpenTelemetry CLIENT span
 * ({@code couchbase.<op>}). Because the span is created on the same thread that is already inside the
 * per-operation request span (made current in the dispatch loop), each backend span nests under its
 * operation span automatically — giving a trace that shows how much of an operation's latency was the
 * shim vs. the Couchbase backend.
 *
 * <p>Installed only when tracing is enabled (see {@code Tracing}); otherwise the bare repository is used
 * with no overhead. Every {@link Repository} method — including the ones with interface defaults — is
 * overridden and delegated, so the real (optimized) {@code CouchbaseRepository} implementations run.
 */
public class TracingRepository implements Repository {

    private final Repository delegate;
    private final Tracer tracer;

    public TracingRepository(Repository delegate, Tracer tracer) {
        this.delegate = delegate;
        this.tracer = tracer;
    }

    private <T> T traced(String op, String attrKey, String attrValue, Supplier<T> call) {
        Span span = tracer.spanBuilder("couchbase." + op)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("db.system", "couchbase")
                .setAttribute("db.operation", op)
                .startSpan();
        if (attrKey != null && attrValue != null) {
            span.setAttribute(attrKey, attrValue);
        }
        try (Scope ignored = span.makeCurrent()) {
            return call.get();
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }

    private void traced(String op, String attrKey, String attrValue, Runnable call) {
        traced(op, attrKey, attrValue, () -> {
            call.run();
            return null;
        });
    }

    @Override
    public StoredValue get(String docId) {
        return traced("get", "doc.id", docId, () -> delegate.get(docId));
    }

    @Override
    public Map<String, StoredValue> getAll(String region, List<String> keys) {
        return traced("getAll", "region", region, () -> delegate.getAll(region, keys));
    }

    @Override
    public void put(String docId, StoredValue value) {
        traced("put", "doc.id", docId, () -> delegate.put(docId, value));
    }

    @Override
    public void putAll(String region, Map<String, StoredValue> values) {
        traced("putAll", "region", region, () -> delegate.putAll(region, values));
    }

    @Override
    public void remove(String docId) {
        traced("remove", "doc.id", docId, () -> delegate.remove(docId));
    }

    @Override
    public void commitAtomically(List<WriteOp> ops) {
        traced("commit", null, null, () -> delegate.commitAtomically(ops));
    }

    @Override
    public StoredValue putIfAbsent(String docId, StoredValue value) {
        return traced("putIfAbsent", "doc.id", docId, () -> delegate.putIfAbsent(docId, value));
    }

    @Override
    public StoredValue replace(String docId, StoredValue value) {
        return traced("replace", "doc.id", docId, () -> delegate.replace(docId, value));
    }

    @Override
    public boolean replace(String docId, StoredValue expected, StoredValue newValue) {
        return traced("replaceIfEquals", "doc.id", docId, () -> delegate.replace(docId, expected, newValue));
    }

    @Override
    public boolean removeIfValue(String docId, StoredValue expected) {
        return traced("removeIfValue", "doc.id", docId, () -> delegate.removeIfValue(docId, expected));
    }

    @Override
    public void invalidate(String docId) {
        traced("invalidate", "doc.id", docId, () -> delegate.invalidate(docId));
    }

    @Override
    public void clear(String region) {
        traced("clear", "region", region, () -> delegate.clear(region));
    }

    @Override
    public boolean containsKey(String docId) {
        return traced("containsKey", "doc.id", docId, () -> delegate.containsKey(docId));
    }

    @Override
    public boolean containsValueForKey(String docId) {
        return traced("containsValueForKey", "doc.id", docId, () -> delegate.containsValueForKey(docId));
    }

    @Override
    public int size(String region) {
        return traced("size", "region", region, () -> delegate.size(region));
    }

    @Override
    public List<String> keySet(String region) {
        return traced("keySet", "region", region, () -> delegate.keySet(region));
    }
}
