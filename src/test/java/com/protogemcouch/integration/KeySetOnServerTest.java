package com.protogemcouch.integration;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KeySetOnServerTest extends IntegrationTestSupport {

    @Test
    void keySetOnServer_returns_region_keys() {
        String key1 = uniqueKey("it-keyset-1");
        String key2 = uniqueKey("it-keyset-2");
        String key3 = uniqueKey("it-keyset-3");

        removeDocIfPresent(key1);
        removeDocIfPresent(key2);
        removeDocIfPresent(key3);

        region.put(key1, "value-1");
        region.put(key2, "value-2");
        region.put(key3, "value-3");

        Set<String> keys = region.keySetOnServer();

        assertTrue(keys.contains(key1));
        assertTrue(keys.contains(key2));
        assertTrue(keys.contains(key3));

        region.remove(key1);
        region.remove(key2);
        region.remove(key3);

        Set<String> afterRemove = region.keySetOnServer();

        assertFalse(afterRemove.contains(key1));
        assertFalse(afterRemove.contains(key2));
        assertFalse(afterRemove.contains(key3));
    }
}