package com.protogemcouch.couchbase;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry time-to-live configuration, resolved from the environment.
 *
 * <ul>
 *   <li>{@code CB_TTL_SECONDS} — default entry TTL in seconds (0 = no expiry).</li>
 *   <li>{@code CB_TTL_REGIONS} — per-region overrides, e.g. {@code "sessions:1800,cacheA:60"};
 *       an exact region-name match wins over the default.</li>
 *   <li>{@code CB_TTL_MODE} — {@code ttl} (default; expiry counts from the last write,
 *       i.e. entry-time-to-live) or {@code idle} (expiry is refreshed on every read too,
 *       i.e. entry-idle-time, implemented via Couchbase get-and-touch).</li>
 * </ul>
 *
 * <p>Region names are matched exactly. Geode prefixes region names with {@code /}; both the raw and
 * leading-slash-stripped forms are accepted in {@code CB_TTL_REGIONS} for convenience.
 */
public final class TtlConfig {

    private final long defaultSeconds;
    private final Map<String, Long> perRegionSeconds;
    private final boolean idle;

    TtlConfig(long defaultSeconds, Map<String, Long> perRegionSeconds, boolean idle) {
        this.defaultSeconds = Math.max(0, defaultSeconds);
        this.perRegionSeconds = Collections.unmodifiableMap(new LinkedHashMap<>(perRegionSeconds));
        this.idle = idle;
    }

    public static TtlConfig fromEnv() {
        return new TtlConfig(
                parseSeconds(System.getenv("CB_TTL_SECONDS")),
                parseRegions(System.getenv("CB_TTL_REGIONS")),
                "idle".equalsIgnoreCase(trim(System.getenv("CB_TTL_MODE"))));
    }

    /** Resolve the TTL (seconds) for a region: an exact per-region override, else the default. */
    public long secondsFor(String region) {
        if (region != null) {
            Long override = perRegionSeconds.get(region);
            if (override == null) {
                override = perRegionSeconds.get(stripLeadingSlash(region));
            }
            if (override != null) {
                return override;
            }
        }
        return defaultSeconds;
    }

    /** TTL as a {@link Duration} for a region, or {@code null} when no expiry applies. */
    public Duration durationFor(String region) {
        long seconds = secondsFor(region);
        return seconds > 0 ? Duration.ofSeconds(seconds) : null;
    }

    public boolean enabledFor(String region) {
        return secondsFor(region) > 0;
    }

    /** True if any TTL (default or per-region) is configured. */
    public boolean anyEnabled() {
        if (defaultSeconds > 0) {
            return true;
        }
        for (Long seconds : perRegionSeconds.values()) {
            if (seconds != null && seconds > 0) {
                return true;
            }
        }
        return false;
    }

    /** True when expiry should also be refreshed on reads (entry-idle-time semantics). */
    public boolean idle() {
        return idle;
    }

    public long defaultSeconds() {
        return defaultSeconds;
    }

    public Map<String, Long> perRegionSeconds() {
        return perRegionSeconds;
    }

    static long parseSeconds(String raw) {
        String trimmed = trim(raw);
        if (trimmed.isEmpty()) {
            return 0;
        }
        try {
            long value = Long.parseLong(trimmed);
            return value > 0 ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parse {@code "regionA:60, regionB:120"} into a region→seconds map, ignoring malformed pairs. */
    static Map<String, Long> parseRegions(String raw) {
        Map<String, Long> out = new LinkedHashMap<>();
        String trimmed = trim(raw);
        if (trimmed.isEmpty()) {
            return out;
        }
        for (String pair : trimmed.split(",")) {
            int sep = pair.lastIndexOf(':');
            if (sep <= 0 || sep == pair.length() - 1) {
                continue;
            }
            String region = stripLeadingSlash(pair.substring(0, sep).trim());
            String secondsText = pair.substring(sep + 1).trim();
            if (region.isEmpty()) {
                continue;
            }
            try {
                long seconds = Long.parseLong(secondsText);
                if (seconds >= 0) {
                    out.put(region, seconds);
                }
            } catch (NumberFormatException ignored) {
                // skip malformed pair
            }
        }
        return out;
    }

    private static String stripLeadingSlash(String region) {
        return region != null && region.startsWith("/") ? region.substring(1) : region;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
