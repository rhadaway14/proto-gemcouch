package com.protogemcouch.couchbase;

import com.couchbase.client.core.msg.kv.DurabilityLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CouchbaseDurabilityTest {

    @Test
    void parsesKnownLevelsCaseAndSeparatorInsensitive() {
        assertEquals(DurabilityLevel.MAJORITY, CouchbaseRepository.parseDurability("majority"));
        assertEquals(DurabilityLevel.MAJORITY, CouchbaseRepository.parseDurability("MAJORITY"));
        assertEquals(DurabilityLevel.MAJORITY_AND_PERSIST_TO_ACTIVE,
                CouchbaseRepository.parseDurability("majorityAndPersistToActive"));
        assertEquals(DurabilityLevel.MAJORITY_AND_PERSIST_TO_ACTIVE,
                CouchbaseRepository.parseDurability("MAJORITY_AND_PERSIST_TO_ACTIVE"));
        assertEquals(DurabilityLevel.PERSIST_TO_MAJORITY,
                CouchbaseRepository.parseDurability("persistToMajority"));
        assertEquals(DurabilityLevel.PERSIST_TO_MAJORITY,
                CouchbaseRepository.parseDurability("PERSIST_TO_MAJORITY"));
    }

    @Test
    void defaultsToNoneForBlankUnsetOrUnknown() {
        assertEquals(DurabilityLevel.NONE, CouchbaseRepository.parseDurability(null));
        assertEquals(DurabilityLevel.NONE, CouchbaseRepository.parseDurability(""));
        assertEquals(DurabilityLevel.NONE, CouchbaseRepository.parseDurability("  "));
        assertEquals(DurabilityLevel.NONE, CouchbaseRepository.parseDurability("none"));
        assertEquals(DurabilityLevel.NONE, CouchbaseRepository.parseDurability("bogus"));
    }
}
