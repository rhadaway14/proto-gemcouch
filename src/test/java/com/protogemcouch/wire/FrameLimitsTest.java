package com.protogemcouch.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameLimitsTest {

    @Test
    void defaultsUseDocumentedConstants() {
        FrameLimits limits = FrameLimits.defaults();

        assertEquals(FrameLimits.DEFAULT_MAX_FRAME_BYTES, limits.maxFrameBytes());
        assertEquals(FrameLimits.DEFAULT_MAX_PARTS, limits.maxParts());
    }

    @Test
    void customValuesAreRetained() {
        FrameLimits limits = new FrameLimits(2048, 25);

        assertEquals(2048, limits.maxFrameBytes());
        assertEquals(25, limits.maxParts());
    }

    @Test
    void rejectsNonPositiveMaxFrameBytes() {
        assertThrows(IllegalArgumentException.class, () -> new FrameLimits(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new FrameLimits(-1, 10));
    }

    @Test
    void rejectsNonPositiveMaxParts() {
        assertThrows(IllegalArgumentException.class, () -> new FrameLimits(1024, 0));
        assertThrows(IllegalArgumentException.class, () -> new FrameLimits(1024, -5));
    }
}
