package com.protogemcouch.benchmark;

import org.apache.geode.cache.EntryEvent;
import org.apache.geode.cache.InterestResultPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.query.CqAttributesFactory;
import org.apache.geode.cache.query.CqEvent;
import org.apache.geode.cache.query.CqQuery;
import org.apache.geode.cache.util.CacheListenerAdapter;
import org.apache.geode.cache.util.CqListenerAdapter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the shim's eventing subsystem during a full-surface soak. It attaches a counting
 * {@link org.apache.geode.cache.CacheListener} to the (shared) benchmark region, registers interest in
 * all keys, and runs a continuous query — so every write the load generates flows back as a
 * server-pushed interest event and a CQ event. The running counts let the soak gate assert that event
 * delivery kept up (no feed death / no CQ stall) for the whole run.
 *
 * <p>The {@link ClientCache} is a JVM singleton, so this shares the benchmark's cache and region; the
 * pool's subscription feed must already be enabled.
 */
public final class SubscriptionConsumer {

    private final AtomicLong events = new AtomicLong();
    private final AtomicLong cqEvents = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final Region<String, Object> region;
    private volatile CqQuery cq;

    private SubscriptionConsumer(Region<String, Object> region) {
        this.region = region;
    }

    static SubscriptionConsumer attach(ClientCache cache, Region<String, Object> region,
                                       BenchmarkConfig config) throws Exception {
        SubscriptionConsumer consumer = new SubscriptionConsumer(region);

        // Interest in all keys: every write the load makes is pushed back here as a CacheListener event.
        region.getAttributesMutator().addCacheListener(new CacheListenerAdapter<String, Object>() {
            @Override
            public void afterCreate(EntryEvent<String, Object> event) {
                consumer.events.incrementAndGet();
            }

            @Override
            public void afterUpdate(EntryEvent<String, Object> event) {
                consumer.events.incrementAndGet();
            }

            @Override
            public void afterDestroy(EntryEvent<String, Object> event) {
                consumer.events.incrementAndGet();
            }
        });
        region.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);

        // A CQ over the same region: each matching write also arrives as a CQ event.
        CqAttributesFactory caf = new CqAttributesFactory();
        caf.addCqListener(new CqListenerAdapter() {
            @Override
            public void onEvent(CqEvent event) {
                consumer.cqEvents.incrementAndGet();
            }

            @Override
            public void onError(CqEvent event) {
                consumer.errors.incrementAndGet();
            }
        });
        consumer.cq = cache.getQueryService()
                .newCq("soak-cq", "SELECT * FROM /" + config.getRegionName(), caf.create());
        consumer.cq.execute();
        return consumer;
    }

    public long events() {
        return events.get();
    }

    public long cqEvents() {
        return cqEvents.get();
    }

    public long errors() {
        return errors.get();
    }

    public void close() {
        try {
            if (cq != null) {
                cq.close();
            }
        } catch (Exception ignored) {
            // best-effort teardown
        }
        try {
            region.unregisterInterest("ALL_KEYS");
        } catch (Exception ignored) {
            // best-effort teardown
        }
    }
}
