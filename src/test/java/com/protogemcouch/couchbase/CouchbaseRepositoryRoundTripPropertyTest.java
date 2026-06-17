package com.protogemcouch.couchbase;

import com.couchbase.client.java.json.JsonObject;
import com.protogemcouch.serialization.StoredValue;
import com.protogemcouch.testsupport.RandomValueGraphs;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property-style round-trip coverage of the persistence codec: thousands of randomly generated
 * {@code HashMap<String,Object>} value graphs (the full structured supported matrix, nested) must
 * survive the Couchbase persistence boundary exactly.
 *
 * <p>The test drives {@code encodeStoredValue} → JSON text → {@code JsonObject.fromJson} →
 * {@code decodeStoredValue}, which faithfully reproduces what Couchbase does (it stores the
 * {@code JsonObject} as JSON and re-parses it on read), so it exercises the real JSON type coercions
 * (e.g. Integer/Long/Double narrowing) rather than a same-object shortcut. Each iteration uses its own
 * seed, printed on failure for exact reproduction.
 */
class CouchbaseRepositoryRoundTripPropertyTest {

    private static final long BASE_SEED = 0xC0FFEEL;
    private static final int ITERATIONS = 2000;

    @Test
    void structuredMapValuesSurviveJsonPersistenceRoundTripExactly() {
        for (int i = 0; i < ITERATIONS; i++) {
            long seed = BASE_SEED + i;
            LinkedHashMap<String, Object> map = RandomValueGraphs.randomMap(new Random(seed));
            StoredValue original = StoredValue.stringObjectHashMapValue(map);

            JsonObject encoded = CouchbaseRepository.encodeStoredValue(original);
            StoredValue roundTripped =
                    CouchbaseRepository.decodeStoredValue(JsonObject.fromJson(encoded.toString()));

            assertEquals(original, roundTripped,
                    "JSON persistence round-trip mismatch at iteration " + i + " (seed=" + seed + ")");
        }
    }
}
