package com.protogemcouch.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ContainsKeyIT extends IntegrationTestSupport {

    @Test
    void containsKeyOnServer_false_then_true_then_false() {
        String key = uniqueKey("it-contains");
        String value = "contains-value";

        removeDocIfPresent(key);

        assertFalse(region.containsKeyOnServer(key));

        region.put(key, value);
        assertTrue(region.containsKeyOnServer(key));

        region.remove(key);
        assertFalse(region.containsKeyOnServer(key));
    }
}