package com.protogemcouch.integration;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.cache.query.Struct;
import org.apache.geode.pdx.PdxInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end validation of first-cut OQL ({@code SELECT * FROM /region}) against a real Geode client
 * and the live shim + Couchbase: the shim produces the chunked query response and the client
 * assembles it into a {@code SelectResults}.
 */
@Tag("integration")
class ProtoGemCouchQueryIntegrationTest {

    private static final String HOST = envOrDefault("IT_SHIM_HOST", "127.0.0.1");
    private static final int SHIM_PORT = intEnv("IT_SHIM_PORT", 40405);
    private static final int HEALTH_PORT = intEnv("IT_HEALTH_PORT", 8081);

    private ClientCache cache;
    private Region<String, Object> region;
    private String regionName;

    @BeforeEach
    void setUp() {
        waitForReady("http://" + HOST + ":" + HEALTH_PORT + "/ready", Duration.ofSeconds(90));
        cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(false)
                .setPdxReadSerialized(true) // SELECT * on PDX returns PdxInstances (no domain classes needed)
                .addPoolServer(HOST, SHIM_PORT)
                .create();
        regionName = "q" + UUID.randomUUID().toString().replace("-", "");
        region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(regionName);
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void selectStarReturnsAllValues() throws Exception {
        region.put("k1", "v1");
        region.put("k2", "v2");
        region.put("k3", "v3");

        SelectResults<?> results = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName).execute();

        assertEquals(3, results.size(), "all rows returned");
        assertTrue(new HashSet<>(results).containsAll(Set.of("v1", "v2", "v3")), "values match");
    }

    @Test
    void selectStarOnEmptyRegionReturnsNoRows() throws Exception {
        SelectResults<?> results = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName).execute();

        assertEquals(0, results.size(), "empty region yields no rows");
    }

    @Test
    void selectStarWithWhereFiltersByField() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 100)));
        region.put("b", new HashMap<>(Map.of("status", "closed", "amount", 50)));
        region.put("c", new HashMap<>(Map.of("status", "active", "amount", 10)));

        SelectResults<?> active = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " WHERE status = 'active'").execute();
        assertEquals(2, active.size(), "two active rows match");

        SelectResults<?> activeBig = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " WHERE status = 'active' AND amount > 50").execute();
        assertEquals(1, activeBig.size(), "ANDed conditions narrow to one row");
    }

    @Test
    void whereWithOrMatchesEitherGroup() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active")));
        region.put("b", new HashMap<>(Map.of("status", "closed")));
        region.put("c", new HashMap<>(Map.of("status", "vip")));

        SelectResults<?> results = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " WHERE status = 'active' OR status = 'vip'").execute();
        assertEquals(2, results.size(), "OR matches active and vip");
    }

    @Test
    void whereMatchesTopLevelFieldEvenWhenMapCarriesNestedComplexValues() throws Exception {
        // A map carrying nested complex values (Object[], nested Map, ArrayList<Object>) used to
        // collapse to opaque Java-serialized bytes, which made even its scalar fields unqueryable.
        // It now stays structured, so its top-level scalar fields are still matched by WHERE and the
        // nested content survives the query round-trip.
        HashMap<String, Object> active = new HashMap<>();
        active.put("status", "active");
        active.put("amount", 100);
        active.put("tags", new Object[] {"a", 1, Boolean.TRUE});
        active.put("nested", new HashMap<>(Map.of("city", "Austin", "zip", 78701)));
        active.put("scores", new ArrayList<>(List.of(3, 4, 5)));

        HashMap<String, Object> closed = new HashMap<>();
        closed.put("status", "closed");
        closed.put("amount", 50);
        closed.put("tags", new Object[] {"x"});

        region.put("a", active);
        region.put("b", closed);

        SelectResults<?> matched = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " WHERE status = 'active'").execute();
        assertEquals(1, matched.size(), "the nested-bearing map is still matched by its top-level field");

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) matched.iterator().next();
        assertEquals("active", row.get("status"));
        assertEquals(100, row.get("amount"));
        assertTrue(row.get("tags") instanceof Object[], "nested Object[] survived");
        assertEquals(3, ((Object[]) row.get("tags")).length);
        assertTrue(row.get("nested") instanceof Map, "nested Map survived");
        assertEquals("Austin", ((Map<?, ?>) row.get("nested")).get("city"));
        assertEquals(List.of(3, 4, 5), row.get("scores"));
    }

    @Test
    void singleFieldProjectionReturnsFieldValues() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 100)));
        region.put("b", new HashMap<>(Map.of("status", "closed", "amount", 50)));

        SelectResults<?> statuses = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.status FROM /" + regionName + " e").execute();
        assertEquals(2, statuses.size());
        assertTrue(new HashSet<>(statuses).containsAll(Set.of("active", "closed")),
                "projection returns the field values");

        SelectResults<?> bigAmount = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.amount FROM /" + regionName + " e WHERE status = 'active'").execute();
        assertEquals(1, bigAmount.size());
        assertTrue(new HashSet<>(bigAmount).contains(100), "projection + WHERE returns the matching field value");
    }

    @Test
    void multiFieldStructProjection() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 100)));
        region.put("b", new HashMap<>(Map.of("status", "closed", "amount", 50)));

        SelectResults<?> all = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.status, e.amount FROM /" + regionName + " e").execute();
        assertEquals(2, all.size());
        Set<String> pairs = new HashSet<>();
        for (Object o : all) {
            Object[] fields = ((Struct) o).getFieldValues();
            pairs.add(fields[0] + ":" + fields[1]);
        }
        assertTrue(pairs.containsAll(Set.of("active:100", "closed:50")), "structs carry both fields: " + pairs);

        SelectResults<?> filtered = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.status, e.amount FROM /" + regionName + " e WHERE amount > 60").execute();
        assertEquals(1, filtered.size(), "struct projection honors WHERE");
        assertEquals("active", ((Struct) filtered.iterator().next()).getFieldValues()[0]);
    }

    @Test
    void orderByReturnsSortedResults() throws Exception {
        region.put("a", new HashMap<>(Map.of("amount", 30)));
        region.put("b", new HashMap<>(Map.of("amount", 10)));
        region.put("c", new HashMap<>(Map.of("amount", 20)));

        SelectResults<?> asc = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.amount FROM /" + regionName + " e ORDER BY amount").execute();
        assertEquals(List.of(10, 20, 30), new ArrayList<>(asc), "ascending order preserved");

        SelectResults<?> desc = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.amount FROM /" + regionName + " e ORDER BY amount DESC").execute();
        assertEquals(List.of(30, 20, 10), new ArrayList<>(desc), "descending order preserved");
    }

    @Test
    void structProjectionWithOrderByPreservesRowOrder() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "x", "amount", 30)));
        region.put("b", new HashMap<>(Map.of("status", "y", "amount", 10)));
        region.put("c", new HashMap<>(Map.of("status", "z", "amount", 20)));

        SelectResults<?> asc = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.amount, e.status FROM /" + regionName + " e ORDER BY e.amount").execute();
        List<Object> amountsInOrder = new ArrayList<>();
        for (Object o : asc) {
            amountsInOrder.add(((Struct) o).getFieldValues()[0]); // field 0 = amount
        }
        assertEquals(List.of(10, 20, 30), amountsInOrder,
                "struct projection rows are returned in ORDER BY order: " + amountsInOrder);

        SelectResults<?> desc = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.amount, e.status FROM /" + regionName + " e ORDER BY e.amount DESC").execute();
        List<Object> descAmounts = new ArrayList<>();
        for (Object o : desc) {
            descAmounts.add(((Struct) o).getFieldValues()[0]);
        }
        assertEquals(List.of(30, 20, 10), descAmounts, "descending struct order preserved: " + descAmounts);
    }

    @Test
    void queryPdxByFieldAndProject() throws Exception {
        region.put("a", pdxOrder("active", 100));
        region.put("b", pdxOrder("closed", 50));
        region.put("c", pdxOrder("active", 10));

        SelectResults<?> active = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " WHERE status = 'active'").execute();
        assertEquals(2, active.size(), "WHERE on a PDX object field");

        SelectResults<?> amounts = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.amount FROM /" + regionName + " e WHERE status = 'active' AND amount > 50")
                .execute();
        assertEquals(1, amounts.size(), "PDX field filter + projection");
        assertTrue(new HashSet<>(amounts).contains(100), "projected PDX field value");
    }

    private PdxInstance pdxOrder(String status, int amount) {
        return cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", status)
                .writeInt("amount", amount)
                .create();
    }

    @Test
    void nestedMapFieldQuery() throws Exception {
        region.put("a", new HashMap<>(Map.of(
                "status", "active", "address", new HashMap<>(Map.of("zip", "78701", "city", "Austin")))));
        region.put("b", new HashMap<>(Map.of(
                "status", "active", "address", new HashMap<>(Map.of("zip", "10001", "city", "NYC")))));

        SelectResults<?> austin = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " r WHERE r.address.zip = '78701'").execute();
        assertEquals(1, austin.size(), "WHERE on a nested map field selects the right row");

        SelectResults<?> cities = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.address.city FROM /" + regionName + " e WHERE status = 'active'").execute();
        assertEquals(Set.of("Austin", "NYC"), new HashSet<>(cities), "nested-field projection");
    }

    @Test
    void nestedPdxFieldQuery() throws Exception {
        region.put("a", pdxOrderWithAddress("active", "78701"));
        region.put("b", pdxOrderWithAddress("active", "10001"));
        region.put("c", pdxOrderWithAddress("closed", "78701"));

        SelectResults<?> austinActive = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName
                        + " r WHERE r.address.zip = '78701' AND r.status = 'active'").execute();
        assertEquals(1, austinActive.size(), "WHERE on a nested PDX object field selects the right row");
    }

    @Test
    void pdxScalarArrayIndexAndContainmentQuery() throws Exception {
        region.put("a", cache.createPdxInstanceFactory("demo.Tagged")
                .writeString("status", "active")
                .writeStringArray("tags", new String[] {"gold", "silver"})
                .create());
        region.put("b", cache.createPdxInstanceFactory("demo.Tagged")
                .writeString("status", "active")
                .writeStringArray("tags", new String[] {"bronze"})
                .create());

        SelectResults<?> firstIsGold = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " r WHERE r.tags[0] = 'gold'").execute();
        assertEquals(1, firstIsGold.size(), "indexed access into a PDX string array");

        SelectResults<?> hasSilver = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " r WHERE 'silver' IN r.tags").execute();
        assertEquals(1, hasSilver.size(), "IN containment on a PDX string array");
    }

    @Test
    void pdxObjectArrayIndexedFieldQuery() throws Exception {
        region.put("a", pdxWithAddresses("active", "78701", "73301"));
        region.put("b", pdxWithAddresses("active", "10001"));
        region.put("c", pdxWithAddresses("closed", "78701"));

        SelectResults<?> firstZip = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName
                        + " r WHERE r.addresses[0].zip = '78701' AND r.status = 'active'").execute();
        assertEquals(1, firstZip.size(),
                "indexed access into a PDX object-array field, then a nested field on the element");

        SelectResults<?> secondZip = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT r.addresses[1].zip FROM /" + regionName
                        + " r WHERE r.addresses[0].zip = '78701' AND r.status = 'active'").execute();
        assertEquals(Set.of("73301"), new HashSet<>(secondZip),
                "projection of a later object-array element's nested field");
    }

    @Test
    void pdxObjectArrayInContainmentAndIndexEdgeCases() throws Exception {
        // Object-array of scalar strings: IN does element-equality containment.
        region.put("a", cache.createPdxInstanceFactory("demo.Contacts")
                .writeString("status", "active")
                .writeObjectArray("contacts", new Object[] {"alice@x.com", "bob@x.com"})
                .create());
        region.put("b", cache.createPdxInstanceFactory("demo.Contacts")
                .writeString("status", "active")
                .writeObjectArray("contacts", new Object[] {"carol@x.com"})
                .create());

        SelectResults<?> hasAlice = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " r WHERE 'alice@x.com' IN r.contacts").execute();
        assertEquals(1, hasAlice.size(), "IN containment over a scalar-string object-array");

        SelectResults<?> hasNobody = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " r WHERE 'nobody@x.com' IN r.contacts").execute();
        assertEquals(0, hasNobody.size(), "IN over an object-array with no matching element yields nothing");

        // An out-of-range index resolves cleanly to no match (never an error).
        region.put("c", pdxWithAddresses("active", "78701"));
        SelectResults<?> outOfRange = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " r WHERE r.addresses[9].zip = '78701'").execute();
        assertEquals(0, outOfRange.size(), "an out-of-range object-array index matches nothing, without error");

        // IN over an object-array of nested PDX objects is well-defined (element-equality): a scalar
        // literal never equals an object element, so it matches nothing — use indexed access for objects.
        SelectResults<?> scalarInObjects = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " r WHERE '78701' IN r.addresses").execute();
        assertEquals(0, scalarInObjects.size(),
                "a scalar literal does not match nested-PDX object elements (documented boundary)");
    }

    private PdxInstance pdxWithAddresses(String status, String... zips) {
        PdxInstance[] addresses = new PdxInstance[zips.length];
        for (int i = 0; i < zips.length; i++) {
            addresses[i] = cache.createPdxInstanceFactory("demo.Address")
                    .writeString("zip", zips[i])
                    .create();
        }
        return cache.createPdxInstanceFactory("demo.Customer")
                .writeString("status", status)
                .writeObjectArray("addresses", addresses)
                .create();
    }

    private PdxInstance pdxOrderWithAddress(String status, String zip) {
        PdxInstance address = cache.createPdxInstanceFactory("demo.Address")
                .writeString("zip", zip)
                .create();
        return cache.createPdxInstanceFactory("demo.Order")
                .writeString("status", status)
                .writeObject("address", address)
                .create();
    }

    @Test
    void nestedSetAndNonArrayListCollectionQueryAndRoundTrip() throws Exception {
        // A map value holding a JDK Set (queryable via IN as of 1.3.0-M3) and a LinkedList (the
        // non-ArrayList path) — both structured/queryable, round-tripping equals-level.
        region.put("a", new HashMap<>(Map.of(
                "status", "active", "tags", new HashSet<>(List.of("gold", "silver")))));
        region.put("b", new HashMap<>(Map.of(
                "status", "active", "tags", new HashSet<>(List.of("bronze")))));

        SelectResults<?> hasGold = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " r WHERE 'gold' IN r.tags").execute();
        assertEquals(1, hasGold.size(), "IN containment over a nested Set");

        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) region.get("a");
        assertTrue(a.get("tags") instanceof Set, "the Set round-trips as a Set");
        assertEquals(Set.of("gold", "silver"), a.get("tags"), "Set round-trips equals-level");

        java.util.LinkedList<Object> history = new java.util.LinkedList<>(List.of(1, 2, 3));
        region.put("c", new HashMap<>(Map.of("status", "active", "history", history)));
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) region.get("c");
        assertTrue(c.get("history") instanceof List, "the LinkedList round-trips as a List");
        assertEquals(List.of(1, 2, 3), c.get("history"), "non-ArrayList list round-trips equals-level");

        SelectResults<?> secondIsTwo = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " r WHERE r.history[1] = 2").execute();
        assertEquals(1, secondIsTwo.size(), "indexed access into a nested non-ArrayList list");
    }

    @Test
    void nestedTypedObjectArrayQueryAndRoundTrip() throws Exception {
        // A map value holding a typed object array (Integer[]) — queryable (indexed + IN) as of 1.3.0-M3,
        // and it round-trips as an Integer[] (not a generic Object[]).
        region.put("a", new HashMap<>(Map.of("status", "active", "scores", new Integer[] {10, 20})));
        region.put("b", new HashMap<>(Map.of("status", "active", "scores", new Integer[] {30})));

        SelectResults<?> firstIsTen = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " r WHERE r.scores[0] = 10").execute();
        assertEquals(1, firstIsTen.size(), "indexed access into a nested typed object array");

        SelectResults<?> hasTwenty = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " r WHERE 20 IN r.scores").execute();
        assertEquals(1, hasTwenty.size(), "IN containment over a nested typed object array");

        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) region.get("a");
        assertTrue(a.get("scores") instanceof Integer[], "the array round-trips as Integer[], not Object[]");
        assertArrayEquals(new Integer[] {10, 20}, (Integer[]) a.get("scores"), "exact element + type fidelity");
    }

    @Test
    void unsupportedQueryRaisesAnError() {
        region.put("k1", "v1");
        // DISTINCT is outside the supported subset and must surface a server error rather than
        // silently mishandling.
        assertThrows(Exception.class, () -> cache.getQueryService()
                .newQuery("SELECT DISTINCT * FROM /" + regionName).execute(),
                "an unsupported query surfaces a server error rather than wrong results");
    }

    @Test
    void parameterizedQueryBindsValues() throws Exception {
        region.put("a", new HashMap<>(Map.of("status", "active", "amount", 100)));
        region.put("b", new HashMap<>(Map.of("status", "closed", "amount", 50)));
        region.put("c", new HashMap<>(Map.of("status", "active", "amount", 10)));

        // String + Integer bind parameters ($1, $2) across AND.
        SelectResults<?> filtered = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName + " e WHERE e.status = $1 AND e.amount > $2")
                .execute("active", 50);
        assertEquals(1, filtered.size(), "only active with amount > 50");

        // A single string bind parameter, with projection.
        SelectResults<?> amounts = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.amount FROM /" + regionName + " e WHERE e.status = $1")
                .execute("active");
        assertEquals(2, amounts.size());
        assertTrue(new HashSet<>(amounts).containsAll(Set.of(100, 10)), "bound projection values: " + amounts);
    }

    @Test
    void largeResultSetIsStreamedAcrossChunksAndFullyAssembled() throws Exception {
        // 250 rows exceeds the shim's default 100-row page size, so the response streams as multiple
        // chunks; the client must assemble every row back into one SelectResults.
        Map<String, Object> batch = new HashMap<>();
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 250; i++) {
            batch.put("k" + i, "val" + i);
            expected.add("val" + i);
        }
        region.putAll(batch);

        SelectResults<?> results = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT * FROM /" + regionName).execute();

        assertEquals(250, results.size(), "all paged rows are assembled");
        assertEquals(expected, new HashSet<>(results), "every value survives the multi-chunk streaming");
    }

    @Test
    void largeOrderedAndStructResultsArePagedAndAssembledInOrder() throws Exception {
        // 250 rows exceeds the 100-row page size, so the ORDER BY (Object[]) and struct responses both
        // stream as multiple chunks; the client must assemble every row and preserve the sort order.
        Map<String, Object> batch = new HashMap<>();
        for (int i = 0; i < 250; i++) {
            batch.put("k" + i, new HashMap<>(Map.of("status", "s" + i, "amount", i)));
        }
        region.putAll(batch);

        SelectResults<?> ordered = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.amount FROM /" + regionName + " e ORDER BY e.amount").execute();
        List<Object> amounts = new ArrayList<>(ordered);
        assertEquals(250, amounts.size(), "all paged ORDER BY rows assembled");
        List<Object> expected = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            expected.add(i);
        }
        assertEquals(expected, amounts, "ascending order preserved across chunks");

        SelectResults<?> structs = (SelectResults<?>) cache.getQueryService()
                .newQuery("SELECT e.amount, e.status FROM /" + regionName + " e ORDER BY e.amount").execute();
        assertEquals(250, structs.size(), "all paged struct rows assembled");
        List<Object> structAmounts = new ArrayList<>();
        for (Object o : structs) {
            structAmounts.add(((Struct) o).getFieldValues()[0]);
        }
        assertEquals(expected, structAmounts, "struct rows ordered across chunks");
    }

    private static void waitForReady(String url, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(1500);
                connection.setRequestMethod("GET");
                try {
                    if (connection.getResponseCode() == 200) {
                        return;
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {
                // retry
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for shim readiness");
            }
        }
        fail("shim did not become ready before timeout: " + url);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
