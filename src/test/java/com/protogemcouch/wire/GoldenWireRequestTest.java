package com.protogemcouch.wire;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.ops.HandlerRegistryFactory;
import com.protogemcouch.ops.OpcodeRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Golden-wire regression library, request (decode) side.
 *
 * <p>The companion to {@link GoldenWireResponseTest}: for each opcode it locks a <b>real Geode 1.15
 * client request frame</b> (captured by {@code tools.RequestWireCapture}) to a committed hex fixture
 * under {@code src/test/resources/golden-wire-requests/}. Each fixture is fed through the actual
 * {@link GemFrameDecoder} and must decode to the expected opcode with a self-consistent part list, so a
 * regression in the request parser (a header field, a part offset, the framing) is caught against the
 * exact bytes a real client sends.
 *
 * <p>{@link #everyHandledOpcodeHasARequestFixtureOrAnExplicitExemption()} ties the fixture set to
 * {@link OpcodeRegistry}: every opcode the shim handles must have a locked request fixture or be an
 * explicit exemption (with a reason), so a newly handled opcode can't ship without locking its request
 * shape or consciously exempting it.
 */
class GoldenWireRequestTest {

    private static final Path DIR = Path.of("src", "test", "resources", "golden-wire-requests");

    /** Request fixture file → the opcode it carries (captured from a real Geode 1.15 client). */
    private static Map<String, Integer> requestFixtures() {
        Map<String, Integer> f = new LinkedHashMap<>();
        f.put("get.hex", MessageTypes.GET);
        f.put("put.hex", MessageTypes.PUT);
        f.put("remove.hex", MessageTypes.REMOVE);
        f.put("contains-key.hex", MessageTypes.CONTAINS_KEY);
        f.put("size.hex", MessageTypes.SIZE);
        f.put("key-set.hex", MessageTypes.KEY_SET);
        f.put("put-all.hex", MessageTypes.PUT_ALL);
        f.put("get-all.hex", MessageTypes.GET_ALL_70);
        f.put("query.hex", MessageTypes.QUERY);
        f.put("query-with-parameters.hex", MessageTypes.QUERY_WITH_PARAMETERS);
        f.put("invalidate.hex", MessageTypes.INVALIDATE);
        f.put("clear-region.hex", MessageTypes.CLEAR_REGION);
        f.put("destroy-region.hex", MessageTypes.DESTROY_REGION);
        f.put("register-interest.hex", MessageTypes.REGISTER_INTEREST);
        f.put("register-interest-list.hex", MessageTypes.REGISTER_INTEREST_LIST);
        f.put("unregister-interest.hex", MessageTypes.UNREGISTER_INTEREST);
        f.put("get-entry.hex", MessageTypes.GET_ENTRY);
        f.put("commit.hex", MessageTypes.COMMIT);
        f.put("rollback.hex", MessageTypes.ROLLBACK);
        f.put("get-function-attributes.hex", MessageTypes.GET_FUNCTION_ATTRIBUTES);
        return f;
    }

    /**
     * Handled opcodes whose request frame is intentionally not locked here, each for a concrete reason.
     * The decode path for these is still covered by the integration suites and the reply-side golden
     * library; capturing their request bytes is either non-deterministic or not what a plain client
     * sends through a capture proxy.
     */
    private static Set<Integer> requestExemptOpcodes() {
        return Set.of(
                MessageTypes.PING,                                  // header-only keepalive
                MessageTypes.CONTROL,                               // control-connection frame
                // The client fetches GET_FUNCTION_ATTRIBUTES (locked) first; the shim rejects it, so the
                // execute-function opcodes are never put on the wire.
                MessageTypes.EXECUTE_FUNCTION,
                MessageTypes.EXECUTE_REGION_FUNCTION,
                MessageTypes.EXECUTE_REGION_FUNCTION_SINGLE_HOP,
                MessageTypes.TX_FAILOVER,                           // single-hop-dependent, ack-only
                MessageTypes.GET_CLIENT_PARTITION_ATTRIBUTES,       // single-hop probe; documented no-op
                // CQ requests ride the subscription connection, which a naive capture proxy desyncs;
                // reply-side golden-locked + ProtoGemCouchCqIntegrationTest cover them.
                MessageTypes.EXECUTECQ,
                MessageTypes.EXECUTECQ_WITH_IR,
                MessageTypes.STOPCQ,
                MessageTypes.CLOSECQ,
                MessageTypes.PERIODIC_ACK,                          // timing-driven subscription ack
                MessageTypes.CLIENT_READY,                          // durable readyForEvents flow
                MessageTypes.UNREGISTER_INTEREST_LIST,              // same family as UNREGISTER_INTEREST
                // PDX registry requests are exercised by the dedicated PDX capture tools + integration.
                MessageTypes.GET_PDX_ID_FOR_TYPE,
                MessageTypes.GET_PDX_ID_FOR_ENUM,
                MessageTypes.GET_PDX_TYPE_BY_ID,
                MessageTypes.GET_PDX_TYPES,
                MessageTypes.GET_PDX_ENUMS,
                MessageTypes.GET_PDX_ENUM_BY_ID);
    }

    @Test
    void requestFixturesDecodeToTheExpectedOpcodeWithAConsistentPartList() {
        requestFixtures().forEach((file, expectedOpcode) -> {
            byte[] bytes = readHexFixture(DIR.resolve(file));
            GemFrame frame = decode(bytes);
            assertNotNull(frame, "fixture did not decode to a frame: " + file);
            assertEquals(expectedOpcode.intValue(), frame.getMessageType(),
                    "fixture " + file + " must carry opcode " + expectedOpcode);
            assertEquals(frame.getNumberOfParts(), frame.getParts().size(),
                    "fixture " + file + " parsed part count must match its declared numberOfParts");
            // The decoder consumes exactly HEADER (17) + payloadLength; confirm the fixture is one frame.
            assertEquals(17 + frame.getPayloadLength(), bytes.length,
                    "fixture " + file + " must be exactly one framed message");
        });
    }

    @Test
    void everyHandledOpcodeHasARequestFixtureOrAnExplicitExemption() {
        OpcodeRegistry registry = HandlerRegistryFactory.create(mock(Repository.class));
        Set<Integer> handled = registry.opcodes();
        Set<Integer> locked = new TreeSet<>(requestFixtures().values());
        Set<Integer> exempt = requestExemptOpcodes();

        Set<Integer> uncovered = new TreeSet<>();
        for (int op : handled) {
            if (!locked.contains(op) && !exempt.contains(op)) {
                uncovered.add(op);
            }
        }
        assertTrue(uncovered.isEmpty(),
                "Handled opcodes with no locked request fixture and no exemption: " + uncovered
                        + " — capture the request with tools.RequestWireCapture and add it to "
                        + "requestFixtures(), or add it to requestExemptOpcodes() with a reason.");

        // No exemption (or fixture) references an opcode the shim doesn't actually handle (stale entries).
        for (int op : exempt) {
            assertTrue(handled.contains(op), "requestExemptOpcodes has stale opcode " + op + " (not handled)");
        }
        for (int op : locked) {
            assertTrue(handled.contains(op), "a request fixture maps to unhandled opcode " + op);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static GemFrame decode(byte[] bytes) {
        EmbeddedChannel channel = new EmbeddedChannel(new GemFrameDecoder());
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(bytes));
            return channel.readInbound();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static byte[] readHexFixture(Path path) {
        try {
            String hex = Files.readString(path, StandardCharsets.UTF_8).replaceAll("\\s+", "");
            return HexFormat.of().parseHex(hex);
        } catch (IOException e) {
            throw new IllegalStateException("Missing request fixture " + path
                    + " — regenerate with tools.RequestWireCapture against a running shim.", e);
        }
    }
}
