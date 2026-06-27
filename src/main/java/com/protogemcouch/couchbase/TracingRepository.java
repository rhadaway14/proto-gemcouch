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

    @Override
    public java.util.Optional<List<StoredValue>> queryPushdownByPredicates(
            String region, List<com.protogemcouch.query.OqlQuery.FieldPredicate> predicates, int limit) {
        return traced("queryPushdown", "region", region,
                () -> delegate.queryPushdownByPredicates(region, predicates, limit));
    }

    @Override
    public java.util.Optional<List<StoredValue>> queryPushdownByOrGroups(
            String region, List<List<com.protogemcouch.query.OqlQuery.FieldPredicate>> orGroups, int limit) {
        return traced("queryPushdownOr", "region", region,
                () -> delegate.queryPushdownByOrGroups(region, orGroups, limit));
    }

    @Override
    public void setPdxScalarExtractor(
            java.util.function.Function<byte[], java.util.Map<String, Object>> extractor) {
        delegate.setPdxScalarExtractor(extractor);
    }

    @Override
    public void saveDurable(DurableRecord record) {
        traced("saveDurable", "durable.id", record == null ? null : record.durableId(),
                () -> delegate.saveDurable(record));
    }

    @Override
    public java.util.Optional<DurableRecord> loadDurable(String durableId) {
        return traced("loadDurable", "durable.id", durableId, () -> delegate.loadDurable(durableId));
    }

    @Override
    public void enqueueDurableEvent(String durableId, byte[] event) {
        traced("enqueueDurableEvent", "durable.id", durableId,
                () -> delegate.enqueueDurableEvent(durableId, event));
    }

    @Override
    public List<byte[]> drainDurableQueue(String durableId) {
        return traced("drainDurableQueue", "durable.id", durableId,
                () -> delegate.drainDurableQueue(durableId));
    }

    @Override
    public void dropDurable(String durableId) {
        traced("dropDurable", "durable.id", durableId, () -> delegate.dropDurable(durableId));
    }

    @Override
    public void markDurableAway(String durableId, boolean away) {
        traced("markDurableAway", "durable.id", durableId, () -> delegate.markDurableAway(durableId, away));
    }

    @Override
    public int sweepExpiredDurable() {
        return traced("sweepExpiredDurable", null, null, delegate::sweepExpiredDurable);
    }

    @Override
    public List<DurableRecord> listAwayDurable() {
        return traced("listAwayDurable", null, null, delegate::listAwayDurable);
    }

    @Override
    public java.util.OptionalInt allocatePdxTypeId(String fingerprint, byte[] serializedType) {
        return traced("allocatePdxTypeId", "pdx.fingerprint", fingerprint,
                () -> delegate.allocatePdxTypeId(fingerprint, serializedType));
    }

    @Override
    public byte[] loadPdxType(int typeId) {
        return traced("loadPdxType", "pdx.typeId", String.valueOf(typeId),
                () -> delegate.loadPdxType(typeId));
    }

    @Override
    public java.util.Map<Integer, byte[]> loadAllPdxTypes() {
        return traced("loadAllPdxTypes", null, null, delegate::loadAllPdxTypes);
    }

    @Override
    public java.util.OptionalInt allocatePdxEnumId(String fingerprint, byte[] serializedEnum) {
        return traced("allocatePdxEnumId", "pdx.fingerprint", fingerprint,
                () -> delegate.allocatePdxEnumId(fingerprint, serializedEnum));
    }

    @Override
    public byte[] loadPdxEnum(int enumId) {
        return traced("loadPdxEnum", "pdx.enumId", String.valueOf(enumId),
                () -> delegate.loadPdxEnum(enumId));
    }

    @Override
    public java.util.Map<Integer, byte[]> loadAllPdxEnums() {
        return traced("loadAllPdxEnums", null, null, delegate::loadAllPdxEnums);
    }
}
