package com.protogemcouch.couchbase;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RepositoryPutAllDefaultTest {

    @Test
    void defaultPutAllDelegatesToPutPerEntryAndSkipsNulls() {
        Repository repository = mock(Repository.class, CALLS_REAL_METHODS);

        Map<String, StoredValue> values = new LinkedHashMap<>();
        values.put("a", StoredValue.stringValue("1"));
        values.put("b", null);
        values.put("c", StoredValue.stringValue("3"));

        repository.putAll("/region", values);

        verify(repository).put("/region::a", StoredValue.stringValue("1"));
        verify(repository).put("/region::c", StoredValue.stringValue("3"));
        verify(repository, never()).put(eq("/region::b"), any());
    }
}
