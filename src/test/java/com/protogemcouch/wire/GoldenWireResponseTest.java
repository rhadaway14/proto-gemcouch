package com.protogemcouch.wire;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.ops.HandlerRegistryFactory;
import com.protogemcouch.ops.OpcodeRegistry;
import com.protogemcouch.serialization.StoredValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Golden-wire regression library, per opcode.
 *
 * <p>For every reply frame the shim produces, this test locks the exact bytes to a committed hex
 * fixture under {@code src/test/resources/golden-wire/}. Any unintended change to a reply's wire
 * encoding — a marker byte, a length field, a part ordering — fails the build with a byte diff, so the
 * large, real-client-validated protocol surface can't silently drift.
 *
 * <p>The companion {@link #everyHandledOpcodeHasAGoldenReplyOrAnExplicitExemption()} test ties the
 * fixture set to {@link OpcodeRegistry}: every opcode the shim handles must have a locked reply here
 * (or be an explicit no-reply opcode), so a newly registered opcode can't ship without a golden lock.
 *
 * <p>Per-value-type GET encodings (Integer/Long/Date/arrays/PDX/…) are byte-asserted by the dedicated
 * {@code *ShapeTest} suite; this library locks the GET frame with a representative String/null value
 * and focuses on the one-reply-per-opcode operation frames.
 *
 * <p>Regenerate after an intended encoding change: {@code mvn -o test -Dtest=GoldenWireResponseTest
 * -Dgolden.update=true} (review the hex diff before committing).
 */
class GoldenWireResponseTest {

    private static final int TX_ID = -1;

    private static final Path GOLDEN_DIR = Path.of("src", "test", "resources", "golden-wire");

    /** Fixture name -> the reply bytes it locks. One entry per distinct reply frame. */
    private static Map<String, byte[]> goldenFixtures() {
        StoredValue priorValue = StoredValue.stringValue("prior-value");
        List<StoredValue> twoRows = List.of(StoredValue.stringValue("row-1"), StoredValue.stringValue("row-2"));

        Map<String, byte[]> f = new LinkedHashMap<>();
        // --- core CRUD ---
        f.put("get-string-response.hex", GemResponseWriter.buildGetResponse(TX_ID, "golden-value"));
        f.put("get-null-response.hex", GemResponseWriter.buildNullGetResponse(TX_ID));
        f.put("put-response.hex", GemResponseWriter.buildPutResponse(TX_ID));
        f.put("put-response-with-old-value.hex", GemResponseWriter.buildPutResponseWithOldValue(TX_ID, priorValue));
        f.put("remove-response.hex", GemResponseWriter.buildRemoveResponse(TX_ID));
        f.put("remove-entry-not-found-response.hex", GemResponseWriter.buildRemoveResponseWithEntryNotFound(TX_ID, true));
        f.put("remove-entry-present-response.hex", GemResponseWriter.buildRemoveResponseWithEntryNotFound(TX_ID, false));
        f.put("contains-true-response.hex", GemResponseWriter.buildContainsResponse(TX_ID, true));
        f.put("contains-false-response.hex", GemResponseWriter.buildContainsResponse(TX_ID, false));
        f.put("size-response.hex", GemResponseWriter.buildSizeResponse(TX_ID, 42));
        f.put("simple-ack-response.hex", GemResponseWriter.buildSimpleAck(TX_ID));
        // --- bulk / key-metadata ---
        f.put("keyset-response.hex", GemResponseWriter.buildKeySetChunkedResponse(TX_ID, List.of("alpha", "beta")));
        f.put("getall-response.hex", GemResponseWriter.buildGetAllChunkedResponse(
                TX_ID, List.of("k1", "k2"), orderedMap("k1", "v1", "k2", "v2")));
        f.put("putall-response.hex", GemResponseWriter.buildPutAllChunkedResponse(TX_ID));
        f.put("putall-error-response.hex", GemResponseWriter.buildPutAllErrorResponse(TX_ID, "keys [k9] failed"));
        // --- transactions ---
        f.put("commit-response.hex", GemResponseWriter.buildCommitResponse(TX_ID));
        f.put("rollback-response.hex", GemResponseWriter.buildRollbackResponse(TX_ID));
        f.put("exception-response.hex", GemResponseWriter.buildExceptionResponse(TX_ID, "golden exception"));
        // --- queries (OQL) ---
        f.put("query-response.hex", GemResponseWriter.buildQueryResponse(TX_ID, twoRows));
        f.put("query-empty-response.hex", GemResponseWriter.buildQueryResponse(TX_ID, List.of()));
        f.put("query-ordered-response.hex", GemResponseWriter.buildOrderedQueryResponse(TX_ID, twoRows));
        f.put("query-struct-response.hex", GemResponseWriter.buildQueryStructResponse(
                TX_ID, 2, List.of(twoRows)));
        f.put("query-error-response.hex", GemResponseWriter.buildQueryErrorResponse(TX_ID, "bad query"));
        // --- continuous queries ---
        f.put("executecq-reply.hex", GemResponseWriter.buildExecuteCqReply());
        f.put("executecq-with-ir-reply.hex", GemResponseWriter.buildExecuteCqWithIrReply(
                TX_ID, List.of(new AbstractMap.SimpleEntry<>("k1", StoredValue.stringValue("v1")))));
        f.put("cq-ack-response.hex", GemResponseWriter.buildCqAck(TX_ID));
        // --- functions (graceful rejection) ---
        f.put("function-error-reply.hex", GemResponseWriter.buildFunctionErrorReply(
                TX_ID, "The function is not registered for function id goldenFn"));
        // --- subscriptions / register-interest ---
        f.put("register-interest-reply.hex", GemResponseWriter.buildRegisterInterestReply());
        f.put("register-interest-keys-values-reply.hex", GemResponseWriter.buildRegisterInterestKeysValuesReply(
                List.of("k1"), orderedMap("k1", "v1")));
        f.put("unregister-interest-reply.hex", GemResponseWriter.buildUnregisterInterestReply(TX_ID));
        // --- PDX registry ---
        f.put("pdx-type-id-response.hex", GemResponseWriter.buildPdxTypeIdResponse(TX_ID, 7));
        f.put("pdx-type-by-id-response.hex", GemResponseWriter.buildPdxTypeByIdResponse(
                TX_ID, new byte[] {0x01, 0x02, 0x03, 0x04}));
        return f;
    }

    /**
     * Canonical reply fixture for each handled opcode. Some opcodes share a reply frame (e.g. clear /
     * destroyRegion / invalidate all ack via the REMOVE reply; the function opcodes all reject via the
     * same error frame) — that reuse is intentional and the shared bytes are still locked once.
     */
    private static Map<Integer, String> opcodeToReplyFixture() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(MessageTypes.GET, "get-string-response.hex");
        m.put(MessageTypes.PING, "simple-ack-response.hex");
        m.put(MessageTypes.PUT, "put-response.hex");
        m.put(MessageTypes.REMOVE, "remove-response.hex");
        m.put(MessageTypes.DESTROY_REGION, "remove-response.hex");
        m.put(MessageTypes.CLEAR_REGION, "remove-response.hex");
        m.put(MessageTypes.INVALIDATE, "remove-response.hex");
        m.put(MessageTypes.CONTROL, "simple-ack-response.hex");
        m.put(MessageTypes.CLIENT_READY, "simple-ack-response.hex");
        m.put(MessageTypes.CONTAINS_KEY, "contains-true-response.hex");
        m.put(MessageTypes.SIZE, "size-response.hex");
        m.put(MessageTypes.KEY_SET, "keyset-response.hex");
        m.put(MessageTypes.GET_ALL_70, "getall-response.hex");
        m.put(MessageTypes.PUT_ALL, "putall-response.hex");
        m.put(MessageTypes.GET_ENTRY, "get-null-response.hex");
        m.put(MessageTypes.QUERY, "query-response.hex");
        m.put(MessageTypes.QUERY_WITH_PARAMETERS, "query-response.hex");
        m.put(MessageTypes.COMMIT, "commit-response.hex");
        m.put(MessageTypes.ROLLBACK, "rollback-response.hex");
        m.put(MessageTypes.REGISTER_INTEREST, "register-interest-reply.hex");
        m.put(MessageTypes.REGISTER_INTEREST_LIST, "register-interest-reply.hex");
        m.put(MessageTypes.UNREGISTER_INTEREST, "unregister-interest-reply.hex");
        m.put(MessageTypes.UNREGISTER_INTEREST_LIST, "unregister-interest-reply.hex");
        m.put(MessageTypes.EXECUTECQ, "executecq-reply.hex");
        m.put(MessageTypes.EXECUTECQ_WITH_IR, "executecq-with-ir-reply.hex");
        m.put(MessageTypes.STOPCQ, "cq-ack-response.hex");
        m.put(MessageTypes.CLOSECQ, "cq-ack-response.hex");
        m.put(MessageTypes.GET_FUNCTION_ATTRIBUTES, "function-error-reply.hex");
        m.put(MessageTypes.EXECUTE_FUNCTION, "function-error-reply.hex");
        m.put(MessageTypes.EXECUTE_REGION_FUNCTION, "function-error-reply.hex");
        m.put(MessageTypes.EXECUTE_REGION_FUNCTION_SINGLE_HOP, "function-error-reply.hex");
        m.put(MessageTypes.GET_PDX_ID_FOR_TYPE, "pdx-type-id-response.hex");
        m.put(MessageTypes.GET_PDX_ID_FOR_ENUM, "pdx-type-id-response.hex");
        m.put(MessageTypes.GET_PDX_TYPE_BY_ID, "pdx-type-by-id-response.hex");
        return m;
    }

    /** Opcodes the shim handles with NO reply by design (drained / documented graceful no-op). */
    private static final Set<Integer> NO_REPLY_OPCODES = Set.of(
            MessageTypes.PERIODIC_ACK,                    // fire-and-forget ack drain
            MessageTypes.GET_CLIENT_PARTITION_ATTRIBUTES  // single-hop probe: documented graceful no-op
    );

    @Test
    void supportedResponseFramesShouldMatchGoldenWireFixtures() {
        goldenFixtures().forEach(GoldenWireResponseTest::assertGoldenFixture);
    }

    @Test
    void everyHandledOpcodeHasAGoldenReplyOrAnExplicitExemption() {
        OpcodeRegistry registry = HandlerRegistryFactory.create(mock(Repository.class));
        Set<Integer> handled = registry.opcodes();
        Map<Integer, String> replies = opcodeToReplyFixture();
        Set<String> fixtures = goldenFixtures().keySet();

        // Every handled opcode is locked to a golden reply, or is an explicit no-reply opcode.
        Set<Integer> uncovered = new TreeSet<>();
        for (int op : handled) {
            if (!replies.containsKey(op) && !NO_REPLY_OPCODES.contains(op)) {
                uncovered.add(op);
            }
        }
        assertTrue(uncovered.isEmpty(),
                "Handled opcodes with no golden reply fixture or no-reply exemption: " + uncovered
                        + " — add a fixture to goldenFixtures() and map it in opcodeToReplyFixture(), "
                        + "or list it in NO_REPLY_OPCODES with a reason.");

        // Every reply mapping points at a real, generated fixture (no dangling names).
        for (Map.Entry<Integer, String> e : replies.entrySet()) {
            assertTrue(fixtures.contains(e.getValue()),
                    "opcode " + e.getKey() + " maps to missing fixture " + e.getValue());
        }

        // The mapping/exemptions don't reference opcodes the shim doesn't actually handle (stale entries).
        for (int op : replies.keySet()) {
            assertTrue(handled.contains(op), "opcodeToReplyFixture has stale opcode " + op + " (not registered)");
        }
        for (int op : NO_REPLY_OPCODES) {
            assertTrue(handled.contains(op), "NO_REPLY_OPCODES has stale opcode " + op + " (not registered)");
        }
    }

    @Test
    void containsBooleanResponsesShouldUseGeodeBooleanObjectEncoding() {
        String trueHex = toHex(GemResponseWriter.buildContainsResponse(TX_ID, true));
        String falseHex = toHex(GemResponseWriter.buildContainsResponse(TX_ID, false));

        // Geode Boolean object encoding: 35 01 = TRUE, 35 00 = FALSE. Guards against regressing back to
        // a raw int/byte[] response (which caused a client ClassCastException earlier).
        assertTrue(trueHex.contains("3501"), "CONTAINS true should contain Boolean TRUE 35 01. Actual: " + trueHex);
        assertTrue(falseHex.contains("3500"), "CONTAINS false should contain Boolean FALSE 35 00. Actual: " + falseHex);
    }

    @Test
    void getStringResponseShouldContainGeodeStringEncoding() {
        String hex = toHex(GemResponseWriter.buildGetResponse(TX_ID, "golden-value"));
        // Geode String object encoding: 57 marker + two-byte UTF-8 length + UTF-8 bytes.
        assertTrue(hex.contains("57"), "GET String response should contain Geode String marker 0x57. Actual: " + hex);
        assertTrue(hex.contains(toHex("golden-value".getBytes(StandardCharsets.UTF_8))),
                "GET String response should contain the UTF-8 payload. Actual: " + hex);
    }

    // ------------------------------------------------------------------ helpers

    private static LinkedHashMap<String, Object> orderedMap(String... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void assertGoldenFixture(String fixtureName, byte[] actualBytes) {
        assertNotNull(actualBytes, "Actual bytes should not be null for " + fixtureName);
        assertTrue(actualBytes.length > 0, "Actual bytes should not be empty for " + fixtureName);

        String actualHex = toHex(actualBytes);
        Path fixturePath = GOLDEN_DIR.resolve(fixtureName);

        if (shouldUpdateGoldenFiles()) {
            writeFixture(fixturePath, actualHex);
            return;
        }

        assertTrue(Files.exists(fixturePath),
                "Missing golden-wire fixture: " + fixturePath + System.lineSeparator()
                        + "Generate fixtures once with: mvn -o test -Dtest=GoldenWireResponseTest -Dgolden.update=true");

        assertEquals(normalizeHex(readFixture(fixturePath)), normalizeHex(actualHex),
                "Golden-wire fixture mismatch for " + fixtureName
                        + " — if this change is intentional, regenerate with -Dgolden.update=true and review the diff.");
    }

    private static boolean shouldUpdateGoldenFiles() {
        String propertyValue = System.getProperty("golden.update");
        if (propertyValue != null && Boolean.parseBoolean(propertyValue)) {
            return true;
        }
        String envValue = System.getenv("GOLDEN_UPDATE");
        return envValue != null && Boolean.parseBoolean(envValue);
    }

    private static void writeFixture(Path fixturePath, String hex) {
        try {
            Files.createDirectories(fixturePath.getParent());
            Files.writeString(fixturePath, wrapHex(hex, 64) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write golden fixture " + fixturePath, e);
        }
    }

    private static String readFixture(Path fixturePath) {
        try {
            return Files.readString(fixturePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read golden fixture " + fixturePath, e);
        }
    }

    private static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static String normalizeHex(String hex) {
        return hex == null ? "" : hex.replaceAll("\\s+", "").trim().toLowerCase();
    }

    private static String wrapHex(String hex, int width) {
        String normalized = normalizeHex(hex);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < normalized.length(); i += width) {
            out.append(normalized, i, Math.min(i + width, normalized.length())).append(System.lineSeparator());
        }
        return out.toString().trim();
    }
}
