package com.protogemcouch.couchbase;

import com.couchbase.client.java.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips the durable-registry codec ({@link CouchbaseRepository#encodeDurableRecord} /
 * {@link CouchbaseRepository#decodeDurableRecord}) across a real JSON text boundary
 * ({@code encode -> JsonObject.fromJson(toString()) -> decode}), so the persisted record survives
 * exactly what Couchbase does to it. No live backend needed.
 */
class DurableRecordCodecTest {

    private static DurableRecord roundTrip(DurableRecord record) {
        JsonObject encoded = CouchbaseRepository.encodeDurableRecord(record);
        return CouchbaseRepository.decodeDurableRecord(JsonObject.fromJson(encoded.toString()));
    }

    @Test
    void roundTripsAllInterestKindsAndCqs() {
        DurableRecord record = new DurableRecord("dur-1", 600, true,
                List.of(
                        DurableRecord.InterestSpec.allKeys("/r1"),
                        DurableRecord.InterestSpec.keys("/r2", List.of("a", "b", "c")),
                        DurableRecord.InterestSpec.regex("/r3", "k.*")),
                List.of(
                        new DurableRecord.CqSpec("cq1", "/r1", "SELECT * FROM /r1 WHERE x = 1"),
                        new DurableRecord.CqSpec("cq2", "/r3", "SELECT * FROM /r3")));

        assertEquals(record, roundTrip(record));
    }

    @Test
    void roundTripsEmptyInterestsAndCqs() {
        DurableRecord record = new DurableRecord("dur-empty", 300, false, List.of(), List.of());
        assertEquals(record, roundTrip(record));
    }

    @Test
    void encodesTypeMarkerForDiscovery() {
        JsonObject encoded = CouchbaseRepository.encodeDurableRecord(
                new DurableRecord("dur-2", 120, false, List.of(), List.of()));
        assertEquals("durableRegistry", encoded.getString("type"));
        assertEquals("dur-2", encoded.getString("durableId"));
        // The metadata codec never writes a queue (that is appended/drained separately on the live doc).
        assertTrue(encoded.getArray("queue") == null, "codec must not write the event queue");
    }

    @Test
    void nullCollectionsNormalizeToEmpty() {
        DurableRecord record = new DurableRecord("dur-3", 60, false, null, null);
        assertEquals(List.of(), record.interests());
        assertEquals(List.of(), record.cqs());
        assertEquals(record, roundTrip(record));
    }
}
