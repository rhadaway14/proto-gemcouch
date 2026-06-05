package com.protogemcouch.couchbase;

import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.testsupport.FakeRepository;
import com.protogemcouch.util.DocumentKeyUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the region operations {@code invalidate} and {@code clear}. */
class RepositoryRegionOpsTest {

    private static final String REGION = "region";

    private final FakeRepository repo = new FakeRepository();

    @Test
    void invalidateKeepsKeyButDropsValue() {
        String docId = DocumentKeyUtil.docId(REGION, "k1");
        repo.put(docId, StoredValue.stringValue("v"));

        repo.invalidate(docId);

        assertNull(repo.get(docId), "value gone after invalidate");
        assertTrue(repo.containsKey(docId), "key still present after invalidate");
        assertFalse(repo.containsValueForKey(docId), "no value for key after invalidate");
        assertTrue(repo.keySet(REGION).contains("k1"), "key still in the keyset");
        assertEquals(1, repo.size(REGION), "invalidated entry is still counted");
    }

    @Test
    void clearRemovesEveryEntry() {
        repo.put(DocumentKeyUtil.docId(REGION, "a"), StoredValue.stringValue("1"));
        repo.put(DocumentKeyUtil.docId(REGION, "b"), StoredValue.stringValue("2"));
        repo.put(DocumentKeyUtil.docId(REGION, "c"), StoredValue.stringValue("3"));
        assertEquals(3, repo.size(REGION));

        repo.clear(REGION);

        assertEquals(0, repo.size(REGION), "region empty after clear");
        assertTrue(repo.keySet(REGION).isEmpty(), "keyset empty after clear");
        assertNull(repo.get(DocumentKeyUtil.docId(REGION, "a")));
    }
}
