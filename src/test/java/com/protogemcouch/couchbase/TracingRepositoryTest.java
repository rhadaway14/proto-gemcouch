package com.protogemcouch.couchbase;

import com.protogemcouch.serialization.StoredValue;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tracing decorator must be behavior-transparent: every {@link Repository} method (including the
 * ones with interface defaults, so the real optimized implementations run) delegates to the wrapped
 * repository and returns its result. Uses a no-op tracer, so this exercises the delegation without a
 * tracing backend.
 */
class TracingRepositoryTest {

    private Repository delegate;
    private TracingRepository tracing;

    @BeforeEach
    void setUp() {
        delegate = mock(Repository.class);
        Tracer noop = OpenTelemetry.noop().getTracer("test");
        tracing = new TracingRepository(delegate, noop);
    }

    @Test
    void delegatesReadsAndReturnsResults() {
        StoredValue v = StoredValue.stringValue("v");
        when(delegate.get("r::k")).thenReturn(v);
        when(delegate.containsKey("r::k")).thenReturn(true);
        when(delegate.containsValueForKey("r::k")).thenReturn(true);
        when(delegate.size("r")).thenReturn(3);
        when(delegate.keySet("r")).thenReturn(List.of("a", "b"));
        when(delegate.getAll("r", List.of("k"))).thenReturn(Map.of("k", v));

        assertSame(v, tracing.get("r::k"));
        assertTrue(tracing.containsKey("r::k"));
        assertTrue(tracing.containsValueForKey("r::k"));
        assertEquals(3, tracing.size("r"));
        assertEquals(List.of("a", "b"), tracing.keySet("r"));
        assertEquals(Map.of("k", v), tracing.getAll("r", List.of("k")));

        verify(delegate).get("r::k");
        verify(delegate).size("r");
        verify(delegate).keySet("r");
        verify(delegate).getAll("r", List.of("k"));
    }

    @Test
    void delegatesWritesAndAtomics() {
        StoredValue v = StoredValue.stringValue("v");
        StoredValue old = StoredValue.stringValue("old");
        when(delegate.putIfAbsent("r::k", v)).thenReturn(null);
        when(delegate.replace("r::k", v)).thenReturn(old);
        when(delegate.replace("r::k", old, v)).thenReturn(true);
        when(delegate.removeIfValue("r::k", old)).thenReturn(true);

        tracing.put("r::k", v);
        tracing.remove("r::k");
        tracing.invalidate("r::k");
        tracing.clear("r");
        tracing.putAll("r", Map.of("k", v));
        tracing.commitAtomically(List.of(new Repository.WriteOp("r::k", v, false)));
        assertEquals(null, tracing.putIfAbsent("r::k", v));
        assertSame(old, tracing.replace("r::k", v));
        assertTrue(tracing.replace("r::k", old, v));
        assertTrue(tracing.removeIfValue("r::k", old));

        verify(delegate).put("r::k", v);
        verify(delegate).remove("r::k");
        verify(delegate).invalidate("r::k");
        verify(delegate).clear("r");
        verify(delegate).putAll("r", Map.of("k", v));
        verify(delegate).commitAtomically(List.of(new Repository.WriteOp("r::k", v, false)));
        verify(delegate).putIfAbsent("r::k", v);
        verify(delegate).replace("r::k", v);
        verify(delegate).replace("r::k", old, v);
        verify(delegate).removeIfValue("r::k", old);
    }

    @Test
    void propagatesBackendExceptions() {
        when(delegate.get("r::k")).thenThrow(new RepositoryException("backend down"));
        try {
            tracing.get("r::k");
            org.junit.jupiter.api.Assertions.fail("exception should propagate through the decorator");
        } catch (RepositoryException expected) {
            assertEquals("backend down", expected.getMessage());
        }
    }
}
