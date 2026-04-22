package com.protogemcouch.integration;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class GetAllIT extends IntegrationTestSupport {

    @Test
    void getAll_returns_present_and_missing_keys() {
        String key1 = uniqueKey("it-getall-1");
        String key2 = uniqueKey("it-getall-2");
        String missing = uniqueKey("it-getall-missing");

        removeDocIfPresent(key1);
        removeDocIfPresent(key2);
        removeDocIfPresent(missing);

        region.put(key1, "value-1");
        region.put(key2, "value-2");

        Map<String, String> results = region.getAll(Arrays.asList(key1, key2, missing));

        assertEquals("value-1", results.get(key1));
        assertEquals("value-2", results.get(key2));
        assertTrue(results.containsKey(missing));
        assertNull(results.get(missing));
    }
}