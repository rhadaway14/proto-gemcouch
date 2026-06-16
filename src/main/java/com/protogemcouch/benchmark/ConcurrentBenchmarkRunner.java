package com.protogemcouch.benchmark;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConcurrentBenchmarkRunner {

    public static void main(String[] args) throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromEnv();

        printConfig(config);

        ClientCache cache = null;
        try {
            cache = new ClientCacheFactory()
                    .addPoolServer(config.getHost(), config.getPort())
                    .setPoolSubscriptionEnabled(false)
                    .set("log-level", "warn")
                    .create();

            Region<String, String> region = cache
                    .<String, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                    .create(config.getRegionName());

            if (config.isSeedBeforeRun()) {
                seed(region, config);
            }

            if (!config.getWarmupDuration().isZero() && !config.getWarmupDuration().isNegative()) {
                System.out.println("Starting warmup...");
                BenchmarkResult warmup = runPhase(region, config, config.getWarmupDuration(), "warmup", false);
                printSummary(config, warmup);
            }

            System.out.println("Starting measured run...");
            BenchmarkResult measured = runPhase(region, config, config.getDuration(), "measured", true);
            printSummary(config, measured);
            printMachineSummary(measured);
        } finally {
            if (cache != null) {
                cache.close();
            }
        }
    }

    private static BenchmarkResult runPhase(Region<String, String> region,
                                            BenchmarkConfig config,
                                            Duration duration,
                                            String phase,
                                            boolean printProgress) throws InterruptedException {
        Map<OperationType, LatencyStats> stats = new EnumMap<>(OperationType.class);
        for (OperationType op : OperationType.values()) {
            stats.put(op, new LatencyStats());
        }

        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(config.getConcurrency());

        long startMillis = System.currentTimeMillis();

        for (int i = 0; i < config.getConcurrency(); i++) {
            int workerId = i;
            Thread thread = new Thread(
                    () -> runWorker(region, config, stats, stop, done, workerId),
                    phase + "-worker-" + workerId
            );
            thread.setDaemon(true);
            thread.start();
        }

        long phaseStart = System.currentTimeMillis();
        long durationMillis = duration.toMillis();
        long progressEveryMillis = config.getProgressInterval().toMillis();
        long nextProgress = phaseStart + progressEveryMillis;

        while (System.currentTimeMillis() - phaseStart < durationMillis) {
            Thread.sleep(500);

            if (printProgress && progressEveryMillis > 0 && System.currentTimeMillis() >= nextProgress) {
                long now = System.currentTimeMillis();
                BenchmarkResult interim = new BenchmarkResult(startMillis, now, stats, phase);
                printProgress(interim);
                nextProgress += progressEveryMillis;
            }
        }

        stop.set(true);
        done.await();

        long endMillis = System.currentTimeMillis();
        return new BenchmarkResult(startMillis, endMillis, stats, phase);
    }

    private static void runWorker(Region<String, String> region,
                                  BenchmarkConfig config,
                                  Map<OperationType, LatencyStats> stats,
                                  AtomicBoolean stop,
                                  CountDownLatch done,
                                  int workerId) {
        try {
            Random random = new Random(1000L + workerId);

            while (!stop.get()) {
                OperationType op = chooseOperation(config.getWeights(), random);
                long start = System.nanoTime();

                try {
                    executeOperation(region, config, random, op);
                    long elapsed = System.nanoTime() - start;
                    stats.get(op).recordSuccess(elapsed);
                } catch (Exception e) {
                    stats.get(op).recordError();
                }
            }
        } finally {
            done.countDown();
        }
    }

    private static void seed(Region<String, String> region, BenchmarkConfig config) {
        System.out.println("Seeding " + config.getSeedCount() + " keys...");
        for (int i = 0; i < config.getSeedCount(); i++) {
            region.put("bench-key-" + i, "seed-value-" + i);
        }
        System.out.println("Seeding complete.");
    }

    private static void executeOperation(Region<String, String> region,
                                         BenchmarkConfig config,
                                         Random random,
                                         OperationType op) {
        String key = randomKey(random, config.getKeySpaceSize());

        switch (op) {
            case GET -> region.get(key);

            case PUT -> region.put(key, randomValue());

            case REMOVE -> region.remove(key);

            case CONTAINS_KEY -> region.containsKeyOnServer(key);

            case GET_ALL -> {
                String key2 = randomKey(random, config.getKeySpaceSize());
                String key3 = randomKey(random, config.getKeySpaceSize());
                region.getAll(Arrays.asList(key, key2, key3));
            }

            case PUT_ALL -> {
                Map<String, String> entries = new LinkedHashMap<>();
                entries.put(key, randomValue());
                entries.put(randomKey(random, config.getKeySpaceSize()), randomValue());
                entries.put(randomKey(random, config.getKeySpaceSize()), randomValue());
                region.putAll(entries);
            }

            case SIZE -> region.sizeOnServer();

            case KEY_SET -> {
                Set<String> ignored = region.keySetOnServer();
            }
        }
    }

    private static OperationType chooseOperation(Map<OperationType, Integer> weights, Random random) {
        int total = 0;
        for (Integer weight : weights.values()) {
            total += weight;
        }

        int pick = random.nextInt(total);
        int running = 0;

        for (Map.Entry<OperationType, Integer> entry : weights.entrySet()) {
            running += entry.getValue();
            if (pick < running) {
                return entry.getKey();
            }
        }

        return OperationType.GET;
    }

    private static String randomKey(Random random, int keySpaceSize) {
        return "bench-key-" + random.nextInt(keySpaceSize);
    }

    private static String randomValue() {
        return "value-" + UUID.randomUUID();
    }

    private static void printConfig(BenchmarkConfig config) {
        System.out.println("=== ProtoGemCouch Benchmark Config ===");
        System.out.println("Host: " + config.getHost() + ":" + config.getPort());
        System.out.println("Region: " + config.getRegionName());
        System.out.println("Profile: " + config.getProfileName());
        System.out.println("Concurrency: " + config.getConcurrency());
        System.out.println("Warmup: " + config.getWarmupDuration());
        System.out.println("Measured duration: " + config.getDuration());
        System.out.println("Keyspace: " + config.getKeySpaceSize());
        System.out.println("Seed before run: " + config.isSeedBeforeRun());
        System.out.println("Seed count: " + config.getSeedCount());
        System.out.println("Progress interval: " + config.getProgressInterval());
        System.out.println();
    }

    private static void printProgress(BenchmarkResult result) {
        System.out.printf(
                "[progress] phase=%s elapsed=%s total_ops=%d successes=%d errors=%d ops/sec=%.2f%n",
                result.getPhase(),
                Duration.ofMillis(result.getDurationMillis()),
                result.totalOperations(),
                result.totalSuccesses(),
                result.totalErrors(),
                result.opsPerSecond()
        );
    }

    /**
     * One machine-readable line for the automated perf-regression gate (scripts/perf-gate.sh) to
     * parse — total ops, error count, throughput, and the worst per-operation p99 (ms). Kept stable
     * and grep-friendly; do not reformat without updating the gate parser.
     */
    private static void printMachineSummary(BenchmarkResult result) {
        double maxP99Millis = 0.0;
        for (LatencyStats s : result.getPerOperation().values()) {
            if (s.getTotalCount() > 0) {
                maxP99Millis = Math.max(maxP99Millis, nanosToMillis(s.percentile(99)));
            }
        }
        System.out.printf(
                "PERF_RESULT ops_per_sec=%.2f total=%d errors=%d max_p99_ms=%.3f%n",
                result.opsPerSecond(), result.totalOperations(), result.totalErrors(), maxP99Millis);
    }

    private static void printSummary(BenchmarkConfig config, BenchmarkResult result) {
        System.out.println("=== ProtoGemCouch Benchmark Summary ===");
        System.out.println("Phase: " + result.getPhase());
        System.out.println("Host: " + config.getHost() + ":" + config.getPort());
        System.out.println("Region: " + config.getRegionName());
        System.out.println("Profile: " + config.getProfileName());
        System.out.println("Concurrency: " + config.getConcurrency());
        System.out.println("Duration: " + Duration.ofMillis(result.getDurationMillis()));
        System.out.println("Total operations: " + result.totalOperations());
        System.out.println("Successes: " + result.totalSuccesses());
        System.out.println("Errors: " + result.totalErrors());
        System.out.printf("Ops/sec: %.2f%n", result.opsPerSecond());
        System.out.println();

        for (Map.Entry<OperationType, LatencyStats> entry : result.getPerOperation().entrySet()) {
            OperationType op = entry.getKey();
            LatencyStats s = entry.getValue();

            if (s.getTotalCount() == 0) {
                continue;
            }

            System.out.println("Operation: " + op);
            System.out.println("  successes: " + s.getSuccessCount());
            System.out.println("  errors: " + s.getErrorCount());
            System.out.printf("  avg ms: %.3f%n", nanosToMillis(s.averageNanos()));
            System.out.printf("  p50 ms: %.3f%n", nanosToMillis(s.percentile(50)));
            System.out.printf("  p95 ms: %.3f%n", nanosToMillis(s.percentile(95)));
            System.out.printf("  p99 ms: %.3f%n", nanosToMillis(s.percentile(99)));
            System.out.println();
        }
    }

    private static double nanosToMillis(double nanos) {
        return nanos / 1_000_000.0;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}