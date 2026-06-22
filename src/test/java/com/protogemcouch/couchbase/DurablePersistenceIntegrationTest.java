package com.protogemcouch.couchbase;

import com.protogemcouch.config.ServerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage of the 1.2.0-M1 Slice 1 durable-queue persistence primitive against a real
 * Couchbase: save/load the registry record, append/drain the event queue, the bound (oldest dropped on
 * overflow), the save-does-not-clobber-queue invariant, and drop. Exercises the live sub-document
 * append/CAS-drain paths that the codec unit test cannot.
 *
 * <p>Runs in the Docker-backed {@code mvn verify} suite (Couchbase from {@code docker-compose.yml});
 * skipped via an assumption when Couchbase is not reachable. Persistence is enabled through the
 * package-private test seam ({@code DURABLE_PERSISTENCE} is off by default), which is why this test
 * lives in the {@code couchbase} package.
 */
@Tag("integration")
class DurablePersistenceIntegrationTest {

    private static final int MAX_QUEUE = 5;

    private static CouchbaseRepository repository;
    private static final List<String> createdDurableIds = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void connect() {
        String connStr = envOrDefault("CB_CONNSTR", "couchbase://127.0.0.1");
        String host = hostOf(connStr);
        Assumptions.assumeTrue(isPortOpen(host, 11210, 1500),
                () -> "Durable persistence IT requires a reachable Couchbase KV port at " + host + ":11210");

        ServerConfig config = new ServerConfig(
                connStr,
                envOrDefault("CB_USERNAME", "Administrator"),
                envOrDefault("CB_PASSWORD", "password"),
                envOrDefault("CB_BUCKET", "test"),
                envOrDefault("CB_SCOPE", "_default"),
                envOrDefault("CB_COLLECTION", "_default"),
                40405, 8081);

        repository = new CouchbaseRepository(config);
        repository.connect();
        repository.enableDurablePersistenceForTesting(MAX_QUEUE);
    }

    @AfterEach
    void cleanUp() {
        for (String id : createdDurableIds) {
            try {
                repository.dropDurable(id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdDurableIds.clear();
    }

    @AfterAll
    static void disconnect() {
        if (repository != null) {
            repository.disconnect();
        }
    }

    private static String newDurableId() {
        String id = "ITDUR-" + UUID.randomUUID();
        createdDurableIds.add(id);
        return id;
    }

    @Test
    void savesAndLoadsTheRegistryRecord() {
        String id = newDurableId();
        DurableRecord record = new DurableRecord(id, 600, true,
                List.of(
                        DurableRecord.InterestSpec.allKeys("/orders"),
                        DurableRecord.InterestSpec.keys("/customers", List.of("c1", "c2")),
                        DurableRecord.InterestSpec.regex("/audit", "evt.*")),
                List.of(new DurableRecord.CqSpec("cqHigh", "/orders", "SELECT * FROM /orders WHERE total > 100")));

        repository.saveDurable(record);

        Optional<DurableRecord> loaded = repository.loadDurable(id);
        assertTrue(loaded.isPresent(), "saved durable record should load back");
        assertEquals(record, loaded.get());
    }

    @Test
    void loadMissingDurableReturnsEmpty() {
        assertTrue(repository.loadDurable(newDurableId()).isEmpty());
    }

    @Test
    void enqueueThenDrainReturnsEventsInOrderThenEmpty() {
        String id = newDurableId();
        byte[] e1 = bytes("event-1");
        byte[] e2 = bytes("event-2");
        byte[] e3 = bytes("event-3");

        repository.enqueueDurableEvent(id, e1);
        repository.enqueueDurableEvent(id, e2);
        repository.enqueueDurableEvent(id, e3);

        List<byte[]> drained = repository.drainDurableQueue(id);
        assertEquals(3, drained.size());
        assertArrayEquals(e1, drained.get(0));
        assertArrayEquals(e2, drained.get(1));
        assertArrayEquals(e3, drained.get(2));

        // Drained queue is now empty; a second drain returns nothing.
        assertTrue(repository.drainDurableQueue(id).isEmpty());
    }

    @Test
    void queueIsBoundedDroppingOldestOnOverflow() {
        String id = newDurableId();
        // Append more than MAX_QUEUE; the oldest are dropped, the newest MAX_QUEUE are retained in order.
        int total = MAX_QUEUE + 3;
        for (int i = 0; i < total; i++) {
            repository.enqueueDurableEvent(id, bytes("evt-" + i));
        }

        List<byte[]> drained = repository.drainDurableQueue(id);
        assertEquals(MAX_QUEUE, drained.size(), "queue must be bounded to DURABLE_MAX_QUEUE");
        for (int i = 0; i < MAX_QUEUE; i++) {
            // Retained tail: evt-3 .. evt-7 for total=8, MAX_QUEUE=5.
            assertArrayEquals(bytes("evt-" + (total - MAX_QUEUE + i)), drained.get(i));
        }
    }

    @Test
    void saveDoesNotClobberAnExistingQueue() {
        String id = newDurableId();
        repository.enqueueDurableEvent(id, bytes("queued-before-save"));

        DurableRecord record = new DurableRecord(id, 300, true,
                List.of(DurableRecord.InterestSpec.allKeys("/orders")), List.of());
        repository.saveDurable(record);

        // The record is persisted...
        assertEquals(record, repository.loadDurable(id).orElseThrow());
        // ...and the queued event survived the save.
        List<byte[]> drained = repository.drainDurableQueue(id);
        assertEquals(1, drained.size());
        assertArrayEquals(bytes("queued-before-save"), drained.get(0));
    }

    @Test
    void dropRemovesRecordAndQueue() {
        String id = newDurableId();
        repository.saveDurable(new DurableRecord(id, 300, true,
                List.of(DurableRecord.InterestSpec.allKeys("/orders")), List.of()));
        repository.enqueueDurableEvent(id, bytes("x"));

        repository.dropDurable(id);

        assertTrue(repository.loadDurable(id).isEmpty());
        assertTrue(repository.drainDurableQueue(id).isEmpty());
        // Dropping again is a clean no-op.
        repository.dropDurable(id);
    }

    @Test
    void disabledPersistenceIsANoOp() {
        // A repository without the seam enabled treats every durable method as a no-op.
        ServerConfig config = new ServerConfig(
                envOrDefault("CB_CONNSTR", "couchbase://127.0.0.1"),
                envOrDefault("CB_USERNAME", "Administrator"),
                envOrDefault("CB_PASSWORD", "password"),
                envOrDefault("CB_BUCKET", "test"),
                envOrDefault("CB_SCOPE", "_default"),
                envOrDefault("CB_COLLECTION", "_default"),
                40405, 8081);
        CouchbaseRepository off = new CouchbaseRepository(config);
        off.connect();
        try {
            String id = newDurableId();
            off.saveDurable(new DurableRecord(id, 300, true,
                    List.of(DurableRecord.InterestSpec.allKeys("/orders")), List.of()));
            off.enqueueDurableEvent(id, bytes("x"));
            assertTrue(off.loadDurable(id).isEmpty(), "load is a no-op when persistence is off");
            assertTrue(off.drainDurableQueue(id).isEmpty(), "drain is a no-op when persistence is off");
            // And nothing was actually written: the persistence-enabled repository sees no doc either.
            assertFalse(repository.loadDurable(id).isPresent(), "no-op save must not have persisted a doc");
        } finally {
            off.disconnect();
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String hostOf(String connStr) {
        String s = connStr.replaceFirst("^[a-zA-Z]+://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int comma = s.indexOf(',');
        if (comma >= 0) {
            s = s.substring(0, comma);
        }
        return s.isBlank() ? "127.0.0.1" : s;
    }

    private static boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
