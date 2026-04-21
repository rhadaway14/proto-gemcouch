package com.protogemcouch.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PutGetRemoveTest extends IntegrationTestSupport {

    @Test
    void putGetRemove_roundTrip_works() {
        String key = uniqueKey("it-putgetremove");
        String value = "value-" + System.currentTimeMillis();

        removeDocIfPresent(key);

        region.put(key, value);

        String fetched = region.get(key);
        assertEquals(value, fetched);

        String stored = readStoredValue(key);
        assertEquals(value, stored);

        String removed = region.remove(key);
        assertNull(removed);

        String afterRemove = region.get(key);
        assertNull(afterRemove);

        assertFalse(docExists(key));
    }
}