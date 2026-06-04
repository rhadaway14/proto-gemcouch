package com.protogemcouch.couchbase;

import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.testsupport.FakeRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the atomic {@link Repository} operations, exercised through the default
 * (non-atomic) implementations on {@link FakeRepository}. These pin the observable semantics that
 * {@code CouchbaseRepository}'s CAS overrides must also honor (the CAS behavior under concurrency is
 * validated separately by the integration tests against a real Couchbase + Geode client).
 */
class RepositoryAtomicOpsTest {

    private static final String DOC = "region::k1";

    private final FakeRepository repo = new FakeRepository();

    @Test
    void putIfAbsentStoresWhenAbsentAndReturnsNull() {
        StoredValue previous = repo.putIfAbsent(DOC, StoredValue.stringValue("v1"));

        assertNull(previous, "no previous value when the key was absent");
        assertEquals(StoredValue.stringValue("v1"), repo.get(DOC));
    }

    @Test
    void putIfAbsentDoesNotOverwriteAndReturnsExisting() {
        repo.put(DOC, StoredValue.stringValue("original"));

        StoredValue previous = repo.putIfAbsent(DOC, StoredValue.stringValue("attempted"));

        assertEquals(StoredValue.stringValue("original"), previous, "returns the existing value");
        assertEquals(StoredValue.stringValue("original"), repo.get(DOC), "value is left unchanged");
    }

    @Test
    void replaceReplacesWhenPresentAndReturnsPrevious() {
        repo.put(DOC, StoredValue.stringValue("old"));

        StoredValue previous = repo.replace(DOC, StoredValue.stringValue("new"));

        assertEquals(StoredValue.stringValue("old"), previous);
        assertEquals(StoredValue.stringValue("new"), repo.get(DOC));
    }

    @Test
    void replaceIsNoOpWhenAbsentAndReturnsNull() {
        StoredValue previous = repo.replace(DOC, StoredValue.stringValue("new"));

        assertNull(previous);
        assertNull(repo.get(DOC), "nothing stored when the key was absent");
    }

    @Test
    void compareReplaceSucceedsOnMatch() {
        repo.put(DOC, StoredValue.integerValue(1));

        boolean replaced = repo.replace(DOC, StoredValue.integerValue(1), StoredValue.integerValue(2));

        assertTrue(replaced);
        assertEquals(StoredValue.integerValue(2), repo.get(DOC));
    }

    @Test
    void compareReplaceFailsOnMismatchAndLeavesValue() {
        repo.put(DOC, StoredValue.integerValue(1));

        boolean replaced = repo.replace(DOC, StoredValue.integerValue(99), StoredValue.integerValue(2));

        assertFalse(replaced);
        assertEquals(StoredValue.integerValue(1), repo.get(DOC), "value unchanged on mismatch");
    }

    @Test
    void compareReplaceFailsWhenAbsent() {
        assertFalse(repo.replace(DOC, StoredValue.integerValue(1), StoredValue.integerValue(2)));
        assertNull(repo.get(DOC));
    }

    @Test
    void removeIfValueRemovesOnMatch() {
        repo.put(DOC, StoredValue.stringValue("v"));

        boolean removed = repo.removeIfValue(DOC, StoredValue.stringValue("v"));

        assertTrue(removed);
        assertNull(repo.get(DOC));
    }

    @Test
    void removeIfValueFailsOnMismatchAndLeavesValue() {
        repo.put(DOC, StoredValue.stringValue("v"));

        boolean removed = repo.removeIfValue(DOC, StoredValue.stringValue("other"));

        assertFalse(removed);
        assertEquals(StoredValue.stringValue("v"), repo.get(DOC));
    }

    @Test
    void removeIfValueFailsWhenAbsent() {
        assertFalse(repo.removeIfValue(DOC, StoredValue.stringValue("v")));
    }
}
