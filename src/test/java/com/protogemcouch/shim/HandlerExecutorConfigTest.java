package com.protogemcouch.shim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerExecutorConfigTest {

    @Test
    void defaultsToConfiguredValues() {
        HandlerExecutorConfig config = HandlerExecutorConfig.defaults();
        assertEquals(HandlerExecutorConfig.DEFAULT_THREADS, config.threads());
        assertEquals(HandlerExecutorConfig.DEFAULT_MAX_PENDING_TASKS, config.maxPendingTasks());
        assertTrue(config.queueBounded());
    }

    @Test
    void retainsCustomValues() {
        HandlerExecutorConfig config = new HandlerExecutorConfig(16, 500);
        assertEquals(16, config.threads());
        assertEquals(500, config.maxPendingTasks());
        assertTrue(config.queueBounded());
    }

    @Test
    void nonPositiveMaxPendingMeansUnbounded() {
        HandlerExecutorConfig config = new HandlerExecutorConfig(8, 0);
        assertEquals(Integer.MAX_VALUE, config.maxPendingTasks());
        assertFalse(config.queueBounded());
    }

    @Test
    void rejectsNonPositiveThreadCount() {
        assertThrows(IllegalArgumentException.class, () -> new HandlerExecutorConfig(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new HandlerExecutorConfig(-4, 100));
    }

    @Test
    void threadParseFallsBackForInvalidValues() {
        assertEquals(64, HandlerExecutorConfig.parsePositiveOrDefault(null, 64));
        assertEquals(64, HandlerExecutorConfig.parsePositiveOrDefault("  ", 64));
        assertEquals(64, HandlerExecutorConfig.parsePositiveOrDefault("nope", 64));
        assertEquals(64, HandlerExecutorConfig.parsePositiveOrDefault("0", 64));
        assertEquals(128, HandlerExecutorConfig.parsePositiveOrDefault("128", 64));
    }

    @Test
    void maxPendingParseTreatsNonPositiveAsUnbounded() {
        assertEquals(10_000, HandlerExecutorConfig.parseMaxPendingOrDefault(null, 10_000));
        assertEquals(10_000, HandlerExecutorConfig.parseMaxPendingOrDefault("bad", 10_000));
        assertEquals(Integer.MAX_VALUE, HandlerExecutorConfig.parseMaxPendingOrDefault("0", 10_000));
        assertEquals(Integer.MAX_VALUE, HandlerExecutorConfig.parseMaxPendingOrDefault("-1", 10_000));
        assertEquals(2_000, HandlerExecutorConfig.parseMaxPendingOrDefault("2000", 10_000));
    }
}
