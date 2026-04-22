package com.protogemcouch.integration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PutAllIT extends IntegrationTestSupport {

    @Test
    void putAll_writes_all_entries() {
        String key1 = uniqueKey("it-putall-1");
        String key2 = uniqueKey("it-putall-2");
        String key3 = uniqueKey("it-putall-3");

        removeDocIfPresent(key1);
        removeDocIfPresent(key2);
        removeDocIfPresent(key3);

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(key1, "value-1");
        entries.put(key2, "value-2");
        entries.put(key3, "value-3");

        region.putAll(entries);

        assertEquals("value-1", region.get(key1));
        assertEquals("value-2", region.get(key2));
        assertEquals("value-3", region.get(key3));

        assertEquals("value-1", readStoredValue(key1));
        assertEquals("value-2", readStoredValue(key2));
        assertEquals("value-3", readStoredValue(key3));
    }
}