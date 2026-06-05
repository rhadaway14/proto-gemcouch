package com.protogemcouch.wire;

import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the paged (multi-chunk) framing of {@code SELECT *} query responses: a result set larger than
 * the page size streams as multiple chunks (each carrying part0=CollectionType + part1=batch), with
 * the {@code lastChunk} flag set only on the final chunk; small results stay a single chunk. The
 * default page size is 100 rows (see {@code GemResponseWriter.QUERY_PAGE_SIZE}).
 */
class QueryResponsePagingTest {

    private static int i32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    /** Parse the chunk framing into the per-chunk lastChunk flags. */
    private static List<Integer> chunkFlags(byte[] msg) {
        assertEquals(MessageTypes.RESPONSE, i32(msg, 0), "messageType = RESPONSE");
        assertEquals(2, i32(msg, 4), "numberOfParts = 2");
        List<Integer> flags = new ArrayList<>();
        int o = 12;
        while (o + 5 <= msg.length) {
            int chunkLen = i32(msg, o);
            flags.add(msg[o + 4] & 0xff);
            o += 5 + chunkLen;
        }
        assertEquals(msg.length, o, "chunks cover the whole message exactly");
        return flags;
    }

    private static List<StoredValue> values(int n) {
        List<StoredValue> v = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            v.add(StoredValue.stringValue("v" + i));
        }
        return v;
    }

    @Test
    void smallResultIsASingleLastChunk() {
        assertEquals(List.of(1), chunkFlags(GemResponseWriter.buildQueryResponse(7, values(10))));
    }

    @Test
    void emptyResultIsASingleLastChunk() {
        assertEquals(List.of(1), chunkFlags(GemResponseWriter.buildQueryResponse(7, List.of())));
    }

    @Test
    void resultEqualToPageSizeStaysOneChunk() {
        assertEquals(List.of(1), chunkFlags(GemResponseWriter.buildQueryResponse(7, values(100))));
    }

    @Test
    void largeResultStreamsAsMultipleChunksWithLastFlagOnlyOnTheFinalChunk() {
        // 250 rows at the default page size of 100 -> 3 chunks: [0, 0, 1].
        List<Integer> flags = chunkFlags(GemResponseWriter.buildQueryResponse(7, values(250)));
        assertEquals(3, flags.size(), "250 rows / 100 per page = 3 chunks");
        assertEquals(0, flags.get(0));
        assertEquals(0, flags.get(1));
        assertEquals(1, flags.get(2), "only the final chunk is flagged last");
        assertTrue(flags.subList(0, flags.size() - 1).stream().allMatch(f -> f == 0),
                "all non-final chunks have lastChunk=0");
    }

    @Test
    void orderedResultPagesAcrossChunks() {
        assertEquals(List.of(1), chunkFlags(GemResponseWriter.buildOrderedQueryResponse(7, values(10))),
                "small ordered result is one chunk");
        assertEquals(List.of(0, 0, 1), chunkFlags(GemResponseWriter.buildOrderedQueryResponse(7, values(250))),
                "large ORDER BY result streams as 3 chunks");
    }

    @Test
    void structResultPagesAcrossChunks() {
        assertEquals(List.of(1),
                chunkFlags(GemResponseWriter.buildQueryStructResponse(7, 2, structRows(10), true)),
                "small struct result is one chunk");
        assertEquals(List.of(0, 0, 1),
                chunkFlags(GemResponseWriter.buildQueryStructResponse(7, 2, structRows(250), true)),
                "large ORDER BY struct result streams as 3 chunks");
    }

    private static List<List<StoredValue>> structRows(int n) {
        List<List<StoredValue>> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(List.of(StoredValue.integerValue(i), StoredValue.stringValue("s" + i)));
        }
        return rows;
    }
}
