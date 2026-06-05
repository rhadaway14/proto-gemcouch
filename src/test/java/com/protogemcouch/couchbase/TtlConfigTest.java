package com.protogemcouch.couchbase;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtlConfigTest {

    @Test
    void parseSecondsHandlesBlankInvalidAndNonPositive() {
        assertEquals(60, TtlConfig.parseSeconds("60"));
        assertEquals(0, TtlConfig.parseSeconds(""));
        assertEquals(0, TtlConfig.parseSeconds(null));
        assertEquals(0, TtlConfig.parseSeconds("0"));
        assertEquals(0, TtlConfig.parseSeconds("-5"));
        assertEquals(0, TtlConfig.parseSeconds("abc"));
    }

    @Test
    void parseRegionsBuildsMapAndSkipsMalformed() {
        Map<String, Long> map = TtlConfig.parseRegions("sessions:1800, cacheA:60 , bad, x:notnum, :30");
        assertEquals(2, map.size());
        assertEquals(1800L, map.get("sessions"));
        assertEquals(60L, map.get("cacheA"));
    }

    @Test
    void parseRegionsStripsLeadingSlash() {
        Map<String, Long> map = TtlConfig.parseRegions("/sessions:1800");
        assertEquals(1800L, map.get("sessions"));
    }

    @Test
    void perRegionOverrideWinsOverDefault() {
        TtlConfig config = new TtlConfig(10, Map.of("fast", 2L, "slow", 600L), false);

        assertEquals(2, config.secondsFor("fast"));
        assertEquals(600, config.secondsFor("slow"));
        assertEquals(10, config.secondsFor("unlisted"), "falls back to the default");
        assertEquals(2, config.secondsFor("/fast"), "Geode leading-slash region name matches");
    }

    @Test
    void durationAndEnabledReflectSeconds() {
        TtlConfig config = new TtlConfig(0, Map.of("fast", 5L), false);

        assertNull(config.durationFor("unlisted"), "no default -> no expiry");
        assertFalse(config.enabledFor("unlisted"));
        assertEquals(Duration.ofSeconds(5), config.durationFor("fast"));
        assertTrue(config.enabledFor("fast"));
        assertTrue(config.anyEnabled());
    }

    @Test
    void anyEnabledFalseWhenNothingPositive() {
        assertFalse(new TtlConfig(0, Map.of(), false).anyEnabled());
        assertFalse(new TtlConfig(0, Map.of("r", 0L), false).anyEnabled());
    }

    @Test
    void idleFlag() {
        assertTrue(new TtlConfig(5, Map.of(), true).idle());
        assertFalse(new TtlConfig(5, Map.of(), false).idle());
    }
}
