package com.protogemcouch.wire;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the CollectionType wrapper chosen for struct (multi-field) projection responses: an ORDER BY
 * struct projection must use the "Ordered" CollectionType (so the client preserves row order), while
 * an unordered one uses CumulativeNonDistinctResults.
 */
class QueryStructResponseShapeTest {

    private static final String ORDERED = "org.apache.geode.cache.query.internal.Ordered";
    private static final String NON_DISTINCT = "CumulativeNonDistinctResults";

    private static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static List<List<StoredValue>> rows() {
        return List.of(
                List.of(StoredValue.integerValue(10), StoredValue.stringValue("a")),
                List.of(StoredValue.integerValue(20), StoredValue.stringValue("b")));
    }

    @Test
    void unorderedStructUsesCumulativeNonDistinctResults() {
        byte[] msg = GemResponseWriter.buildQueryStructResponse(1, 2, rows(), false);
        assertTrue(contains(msg, NON_DISTINCT), "unordered struct uses CumulativeNonDistinctResults");
        assertFalse(contains(msg, ORDERED), "unordered struct is not wrapped in Ordered");
    }

    @Test
    void orderedStructUsesOrderedCollectionType() {
        byte[] msg = GemResponseWriter.buildQueryStructResponse(1, 2, rows(), true);
        assertTrue(contains(msg, ORDERED), "ORDER BY struct uses the Ordered CollectionType");
        assertFalse(contains(msg, NON_DISTINCT), "ordered struct is not CumulativeNonDistinctResults");
    }

    @Test
    void defaultOverloadIsUnordered() {
        byte[] msg = GemResponseWriter.buildQueryStructResponse(1, 2, rows());
        assertTrue(contains(msg, NON_DISTINCT), "the no-flag overload stays unordered");
    }
}
