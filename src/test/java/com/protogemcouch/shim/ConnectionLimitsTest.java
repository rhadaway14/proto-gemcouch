package com.protogemcouch.shim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionLimitsTest {

    @Test
    void defaultsAreApplied() {
        ConnectionLimits limits = ConnectionLimits.defaults();
        assertEquals(ConnectionLimits.DEFAULT_IDLE_TIMEOUT_SECONDS, limits.idleTimeoutSeconds());
        assertEquals(ConnectionLimits.DEFAULT_MAX_CONNECTIONS, limits.maxConnections());
    }

    @Test
    void idleReapingDisabledWhenZero() {
        assertFalse(new ConnectionLimits(0, 0).idleReapingEnabled());
        assertTrue(new ConnectionLimits(30, 0).idleReapingEnabled());
    }

    @Test
    void rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionLimits(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionLimits(0, -1));
    }

    @Test
    void parseFallsBackForInvalidOrNegativeValues() {
        assertEquals(300, ConnectionLimits.parseNonNegativeOrDefault(null, 300));
        assertEquals(300, ConnectionLimits.parseNonNegativeOrDefault("x", 300));
        assertEquals(300, ConnectionLimits.parseNonNegativeOrDefault("-5", 300));
        assertEquals(0, ConnectionLimits.parseNonNegativeOrDefault("0", 300)); // zero is valid (disabled)
        assertEquals(120, ConnectionLimits.parseNonNegativeOrDefault("120", 300));
    }
}
