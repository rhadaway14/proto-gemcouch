package com.protogemcouch.couchbase;

import java.util.List;

/**
 * The persisted registry record for one durable subscription client (1.2.0-M1) — its retained
 * interest, continuous queries, requested timeout, and away flag. It is the metadata half of the
 * Couchbase-backed durable doc ({@code __protogemcouch::durable::<id>}); the disconnect-time event
 * queue is the other half, manipulated separately via the {@link Repository} enqueue/drain methods so
 * a high-volume append never has to rewrite this record.
 *
 * <p>This is a Repository-layer value type, deliberately decoupled from {@code SubscriptionRegistry}'s
 * in-memory {@code Interest}/{@code Cq} types: Slice 2 maps between them when it wires persistence into
 * the registry. The {@code InterestSpec}/{@code CqSpec} shapes capture exactly what is needed to
 * rebuild interest + CQ registration on a reconnect to any replica.
 */
public record DurableRecord(
        String durableId,
        int timeoutSeconds,
        boolean away,
        List<InterestSpec> interests,
        List<CqSpec> cqs) {

    public DurableRecord {
        interests = interests == null ? List.of() : List.copyOf(interests);
        cqs = cqs == null ? List.of() : List.copyOf(cqs);
    }

    /**
     * A region-scoped interest registration: all keys, a fixed key set, or a regex — the persistable
     * mirror of {@code com.protogemcouch.subscription.Interest}.
     */
    public record InterestSpec(String region, Kind kind, List<String> keys, String regex) {

        public enum Kind { ALL_KEYS, KEYS, REGEX }

        public InterestSpec {
            keys = keys == null ? List.of() : List.copyOf(keys);
        }

        public static InterestSpec allKeys(String region) {
            return new InterestSpec(region, Kind.ALL_KEYS, List.of(), null);
        }

        public static InterestSpec keys(String region, List<String> keys) {
            return new InterestSpec(region, Kind.KEYS, keys, null);
        }

        public static InterestSpec regex(String region, String regex) {
            return new InterestSpec(region, Kind.REGEX, List.of(), regex);
        }
    }

    /** A registered continuous query: its name, region path, and the OQL text to recompile on replay. */
    public record CqSpec(String cqName, String region, String query) {
    }
}
