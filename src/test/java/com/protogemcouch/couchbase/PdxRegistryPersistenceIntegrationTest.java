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
import java.util.AbstractMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration coverage of the 1.3.0-M2 Slice 1 PDX-registry persistence primitive against a real
 * Couchbase: allocate-or-get a cluster-wide id by fingerprint (idempotent, distinct per type),
 * reverse-load the serialized type by id, durability across a "restart" (a fresh repository instance
 * resolves an id it never allocated), cross-instance consistency + the concurrent-allocation race
 * (two instances allocating the same fingerprint converge on one id), bulk load, and the
 * off-by-default no-op. Exercises the live atomic-counter / insert-if-absent paths a unit test cannot.
 *
 * <p>Runs in the Docker-backed {@code mvn verify} suite (Couchbase from {@code docker-compose.yml});
 * skipped via an assumption when Couchbase is not reachable. Persistence is enabled through the
 * package-private test seam ({@code PDX_PERSISTENCE} is off by default), which is why this test lives in
 * the {@code couchbase} package.
 */
@Tag("integration")
class PdxRegistryPersistenceIntegrationTest {

    private static ServerConfig config;
    private static CouchbaseRepository repository;
    // (fingerprint, id) pairs to clean up after each test so the shared bucket does not accumulate.
    private static final List<Map.Entry<String, Integer>> createdTypes = new CopyOnWriteArrayList<>();
    private static final List<Map.Entry<String, Integer>> createdEnums = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void connect() {
        String connStr = envOrDefault("CB_CONNSTR", "couchbase://127.0.0.1");
        String host = hostOf(connStr);
        Assumptions.assumeTrue(isPortOpen(host, 11210, 1500),
                () -> "PDX persistence IT requires a reachable Couchbase KV port at " + host + ":11210");

        config = new ServerConfig(
                connStr,
                envOrDefault("CB_USERNAME", "Administrator"),
                envOrDefault("CB_PASSWORD", "password"),
                envOrDefault("CB_BUCKET", "test"),
                envOrDefault("CB_SCOPE", "_default"),
                envOrDefault("CB_COLLECTION", "_default"),
                40405, 8081);

        repository = newPersistingRepository();
    }

    private static CouchbaseRepository newPersistingRepository() {
        CouchbaseRepository r = new CouchbaseRepository(config);
        r.connect();
        r.enablePdxPersistenceForTesting();
        return r;
    }

    @AfterEach
    void cleanUp() {
        for (Map.Entry<String, Integer> e : createdTypes) {
            try {
                repository.dropPdxTypeForTesting(e.getKey(), e.getValue());
            } catch (Exception ignored) {
                // best-effort
            }
        }
        for (Map.Entry<String, Integer> e : createdEnums) {
            try {
                repository.dropPdxEnumForTesting(e.getKey(), e.getValue());
            } catch (Exception ignored) {
                // best-effort
            }
        }
        createdTypes.clear();
        createdEnums.clear();
    }

    @AfterAll
    static void disconnect() {
        if (repository != null) {
            repository.disconnect();
        }
    }

    private static String fp() {
        return "fp-" + UUID.randomUUID();
    }

    private int allocType(String fp, byte[] bytes) {
        OptionalInt id = repository.allocatePdxTypeId(fp, bytes);
        assertTrue(id.isPresent(), "allocation must yield an id when persistence is on");
        createdTypes.add(new AbstractMap.SimpleEntry<>(fp, id.getAsInt()));
        return id.getAsInt();
    }

    @Test
    void allocateIsIdempotentPerFingerprintAndDistinctAcrossTypes() {
        String fpA = fp();
        String fpB = fp();
        byte[] typeA = bytes("PdxTypeA-layout");
        byte[] typeB = bytes("PdxTypeB-layout");

        int idA = allocType(fpA, typeA);
        int idA2 = allocType(fpA, typeA); // same fingerprint -> same id (idempotent)
        int idB = allocType(fpB, typeB);

        assertEquals(idA, idA2, "the same type fingerprint resolves to a stable id");
        assertNotEquals(idA, idB, "distinct types get distinct ids");
        assertTrue(idA > 0 && idB > 0, "ids are positive");
    }

    @Test
    void loadByIdReturnsTheSerializedType() {
        String fp = fp();
        byte[] type = bytes("PdxType-serialized-bytes");
        int id = allocType(fp, type);

        assertArrayEquals(type, repository.loadPdxType(id), "reverse lookup returns the persisted bytes");
        assertNull(repository.loadPdxType(987654321), "an unknown id resolves to null");
    }

    @Test
    void aFreshRepositoryResolvesAnIdItNeverAllocated() {
        // Simulates a shim restart / a different replica: a second repository instance against the same
        // Couchbase must see the same fingerprint->id mapping and resolve the type by id.
        String fp = fp();
        byte[] type = bytes("durable-across-restart");
        int id = allocType(fp, type);

        CouchbaseRepository fresh = newPersistingRepository();
        try {
            OptionalInt rediscovered = fresh.allocatePdxTypeId(fp, type);
            assertTrue(rediscovered.isPresent());
            assertEquals(id, rediscovered.getAsInt(), "a fresh instance sees the same id for the same type");
            assertArrayEquals(type, fresh.loadPdxType(id), "a fresh instance resolves the type it never allocated");
        } finally {
            fresh.disconnect();
        }
    }

    @Test
    void concurrentAllocationOfTheSameFingerprintConvergesOnOneId() throws Exception {
        String fp = fp();
        byte[] type = bytes("racy-type");
        CouchbaseRepository other = newPersistingRepository();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> repository.allocatePdxTypeId(fp, type).getAsInt());
            Future<Integer> b = pool.submit(() -> other.allocatePdxTypeId(fp, type).getAsInt());
            int idA = a.get();
            int idB = b.get();
            createdTypes.add(new AbstractMap.SimpleEntry<>(fp, idA));
            assertEquals(idA, idB, "two instances allocating the same fingerprint converge on one id");
            assertArrayEquals(type, repository.loadPdxType(idA), "the converged id resolves to the type");
        } finally {
            pool.shutdownNow();
            other.disconnect();
        }
    }

    @Test
    void loadAllIncludesAllocatedTypes() {
        String fp1 = fp();
        String fp2 = fp();
        int id1 = allocType(fp1, bytes("bulk-type-1"));
        int id2 = allocType(fp2, bytes("bulk-type-2"));

        Map<Integer, byte[]> all = repository.loadAllPdxTypes();
        assertTrue(all.containsKey(id1) && all.containsKey(id2), "bulk load includes the allocated types");
        assertArrayEquals(bytes("bulk-type-1"), all.get(id1));
        assertArrayEquals(bytes("bulk-type-2"), all.get(id2));
    }

    @Test
    void enumAllocationMirrorsTypeAllocation() {
        String fp = fp();
        byte[] enumInfo = bytes("EnumInfo-bytes");
        OptionalInt id = repository.allocatePdxEnumId(fp, enumInfo);
        assertTrue(id.isPresent());
        createdEnums.add(new AbstractMap.SimpleEntry<>(fp, id.getAsInt()));

        assertEquals(id.getAsInt(), repository.allocatePdxEnumId(fp, enumInfo).getAsInt(), "enum id is stable");
        assertArrayEquals(enumInfo, repository.loadPdxEnum(id.getAsInt()), "enum reverse lookup");
        assertTrue(repository.loadAllPdxEnums().containsKey(id.getAsInt()), "enum bulk load");
    }

    @Test
    void offByDefaultIsANoOp() {
        CouchbaseRepository plain = new CouchbaseRepository(config);
        plain.connect(); // PDX_PERSISTENCE not enabled
        try {
            assertTrue(plain.allocatePdxTypeId(fp(), bytes("x")).isEmpty(),
                    "allocation is a no-op (empty) when persistence is off");
            assertNull(plain.loadPdxType(1), "load is a no-op (null) when persistence is off");
            assertTrue(plain.loadAllPdxTypes().isEmpty(), "bulk load is empty when persistence is off");
        } finally {
            plain.disconnect();
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String hostOf(String connStr) {
        String s = connStr.replaceFirst("^couchbases?://", "");
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
}
