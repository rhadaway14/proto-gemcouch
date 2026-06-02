package com.protogemcouch.shim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HandlerExecutorConfigTest {

    @Test
    void defaultsToConfiguredThreadCount() {
        assertEquals(HandlerExecutorConfig.DEFAULT_THREADS, HandlerExecutorConfig.defaults().threads());
    }

    @Test
    void retainsCustomThreadCount() {
        assertEquals(16, new HandlerExecutorConfig(16).threads());
    }

    @Test
    void rejectsNonPositiveThreadCount() {
        assertThrows(IllegalArgumentException.class, () -> new HandlerExecutorConfig(0));
        assertThrows(IllegalArgumentException.class, () -> new HandlerExecutorConfig(-4));
    }

    @Test
    void parseFallsBackForInvalidValues() {
        assertEquals(64, HandlerExecutorConfig.parsePositiveOrDefault(null, 64));
        assertEquals(64, HandlerExecutorConfig.parsePositiveOrDefault("  ", 64));
        assertEquals(64, HandlerExecutorConfig.parsePositiveOrDefault("not-a-number", 64));
        assertEquals(64, HandlerExecutorConfig.parsePositiveOrDefault("0", 64));
        assertEquals(64, HandlerExecutorConfig.parsePositiveOrDefault("-8", 64));
        assertEquals(128, HandlerExecutorConfig.parsePositiveOrDefault("128", 64));
    }
}
