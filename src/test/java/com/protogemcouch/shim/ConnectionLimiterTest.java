package com.protogemcouch.shim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionLimiterTest {

    @Test
    void unlimitedNeverRejectsButStillCounts() {
        ConnectionLimiter limiter = new ConnectionLimiter(0);
        for (int i = 0; i < 1000; i++) {
            assertTrue(limiter.tryAcquire());
        }
        assertEquals(1000, limiter.activeConnections());
    }

    @Test
    void rejectsOverTheCapAndAdmitsAgainAfterRelease() {
        ConnectionLimiter limiter = new ConnectionLimiter(2);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertEquals(2, limiter.activeConnections());

        // Third is over the cap and must be rejected without inflating the active count.
        assertFalse(limiter.tryAcquire());
        assertEquals(2, limiter.activeConnections());

        // Freeing a slot admits a new connection.
        limiter.release();
        assertEquals(1, limiter.activeConnections());
        assertTrue(limiter.tryAcquire());
        assertEquals(2, limiter.activeConnections());
    }

    @Test
    void releaseNeverGoesNegative() {
        ConnectionLimiter limiter = new ConnectionLimiter(5);
        limiter.release();
        limiter.release();
        assertEquals(0, limiter.activeConnections());
    }
}
