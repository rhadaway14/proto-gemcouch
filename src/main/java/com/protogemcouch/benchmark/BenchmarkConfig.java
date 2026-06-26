package com.protogemcouch.benchmark;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

public class BenchmarkConfig {

    private final String host;
    private final int port;
    private final String regionName;
    private final int concurrency;
    private final Duration warmupDuration;
    private final Duration duration;
    private final int keySpaceSize;
    private final boolean seedBeforeRun;
    private final int seedCount;
    private final Duration progressInterval;
    private final String profileName;
    private final boolean queryableValues;
    private final boolean richValues;
    private final Map<OperationType, Integer> weights;

    public BenchmarkConfig(String host,
                           int port,
                           String regionName,
                           int concurrency,
                           Duration warmupDuration,
                           Duration duration,
                           int keySpaceSize,
                           boolean seedBeforeRun,
                           int seedCount,
                           Duration progressInterval,
                           String profileName,
                           boolean queryableValues,
                           boolean richValues,
                           Map<OperationType, Integer> weights) {
        this.host = host;
        this.port = port;
        this.regionName = regionName;
        this.concurrency = concurrency;
        this.warmupDuration = warmupDuration;
        this.duration = duration;
        this.keySpaceSize = keySpaceSize;
        this.seedBeforeRun = seedBeforeRun;
        this.seedCount = seedCount;
        this.progressInterval = progressInterval;
        this.profileName = profileName;
        this.queryableValues = queryableValues;
        this.richValues = richValues;
        this.weights = new EnumMap<>(weights);
    }

    public static BenchmarkConfig fromEnv() {
        String profile = envOrDefault("BENCH_PROFILE", "mixed");

        return new BenchmarkConfig(
                envOrDefault("BENCH_HOST", "127.0.0.1"),
                intEnv("BENCH_PORT", 40405),
                envOrDefault("BENCH_REGION", "helloWorld"),
                intEnv("BENCH_CONCURRENCY", 20),
                Duration.ofSeconds(intEnv("BENCH_WARMUP_SECONDS", 15)),
                Duration.ofSeconds(intEnv("BENCH_DURATION_SECONDS", 60)),
                intEnv("BENCH_KEYSPACE", 1000),
                boolEnv("BENCH_SEED", true),
                intEnv("BENCH_SEED_COUNT", 1000),
                Duration.ofSeconds(intEnv("BENCH_PROGRESS_SECONDS", 15)),
                profile,
                boolEnv("BENCH_QUERYABLE_VALUES", false),
                boolEnv("BENCH_RICH_VALUES", false),
                BenchmarkProfiles.forName(profile)
        );
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getRegionName() {
        return regionName;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public Duration getWarmupDuration() {
        return warmupDuration;
    }

    public Duration getDuration() {
        return duration;
    }

    public int getKeySpaceSize() {
        return keySpaceSize;
    }

    public boolean isSeedBeforeRun() {
        return seedBeforeRun;
    }

    public int getSeedCount() {
        return seedCount;
    }

    public Duration getProgressInterval() {
        return progressInterval;
    }

    public String getProfileName() {
        return profileName;
    }

    /**
     * When true, seed map values with a queryable top-level field and have the QUERY op filter that
     * field — so the query path (and OQL pushdown) is exercised, not a whole-value scan that matches
     * nothing. Off keeps the original string-value seed + whole-value query.
     */
    public boolean isQueryableValues() {
        return queryableValues;
    }

    /**
     * When true, PUT / seed values are rich maps exercising the 1.3.0 structured nested value types
     * (typed object arrays, Sets, non-ArrayList Lists, java.time) so a soak drives the new encode/decode
     * paths under sustained load.
     */
    public boolean isRichValues() {
        return richValues;
    }

    public Map<OperationType, Integer> getWeights() {
        return new EnumMap<>(weights);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boolEnv(String name, boolean fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }
}