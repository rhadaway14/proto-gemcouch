package com.protogemcouch.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SizeOnServerTest extends IntegrationTestSupport {

    @Test
    void sizeOnServer_returns_region_count() {
        String key1 = uniqueKey("it-size-1");
        String key2 = uniqueKey("it-size-2");
        String key3 = uniqueKey("it-size-3");

        removeDocIfPresent(key1);
        removeDocIfPresent(key2);
        removeDocIfPresent(key3);

        int before = region.sizeOnServer();

        region.put(key1, "value-1");
        region.put(key2, "value-2");
        region.put(key3, "value-3");

        int afterPut = region.sizeOnServer();
        assertEquals(before + 3, afterPut);

        region.remove(key1);
        region.remove(key2);
        region.remove(key3);

        int afterRemove = region.sizeOnServer();
        assertEquals(before, afterRemove);
    }
}