# OQL query support — design notes

Status: **SELECT * / single-field projection / WHERE (AND+OR) done**, all end-to-end against a real
Geode client (`ProtoGemCouchQueryIntegrationTest`). Supported:
`SELECT (* | <field>) FROM /region [alias] [WHERE <conditions>]` where conditions are
`<field> <op> <literal>` (ops `= <> != < <= > >=`; string/number/boolean/null literals) combined
with `AND`/`OR` (AND binds tighter; no parentheses), evaluated in-shim against the top-level fields
of map-typed values; the response is filtered (and projected) accordingly. The chunked
query-response format below was captured from a real Geode 1.15 server with `GeodeQueryCapture` and
is implemented in `GemResponseWriter.buildQueryResponse` + `QueryHandler` (opcode 34); the OQL text
is parsed by `OqlQuery`. Single-field projections reuse the same response shape with the projected
field values.

**Multi-field (struct) projections** (`SELECT e.f1, e.f2 FROM /region e`) return Geode `Struct`
rows. The response uses a `StructType` CollectionType (ResultsCollectionType → Struct with N
`field$i` columns, each an `ObjectType`) and a nested `Object[]` (DSCODE `0x34`) result: an outer
array of structs, each an `Object[]` of its field values. `GemResponseWriter.buildQueryStructResponse`
generates both for any field count; the bytes were diffed byte-for-byte against the real Geode
server (`GeodeQueryCapture`, M=2 and M=3 captures).

**ORDER BY** (`ORDER BY field [ASC|DESC]`, multi-key) sorts the matched values in-shim. The
non-distinct CollectionType does not preserve client-side order, so ordered SELECT */single-field
responses use Geode's **`...internal.Ordered`** CollectionType + an Object[] (`0x34`) result (also
captured + byte-matched). **ORDER BY also works with struct (multi-field) projections**: the
StructType is wrapped in the same `Ordered` CollectionType (only the wrapper class differs from the
unordered struct response — `Ordered` vs `CumulativeNonDistinctResults`; the Struct part and the
`field$i`/ObjectType tail are identical), captured + byte-matched and validated end-to-end by
`structProjectionWithOrderByPreservesRowOrder` (asc + desc).

**PDX field access**: WHERE / projection / ORDER BY can read fields of stored PDX instances. The
shim keeps each registered `PdxType` (from opcode 93) by id; a PDX instance is framed
`5d <len> <typeId> <fieldData>`, and `PdxFieldAccessor` looks up the `PdxType` by the embedded id and
uses Geode's own `PdxReaderImpl` to read scalar fields by name (so the PDX binary/offset handling is
exactly correct). Validated by `PdxFieldAccessorTest` (real captured PdxType + instance bytes) and a
real-client PDX query test. Note: POJO (Java-serialized) field access is not feasible server-side
(needs the domain classes); PDX is Geode's queryable serialization.

The same PDX-aware resolver (`PdxAwareFieldResolver`, shared via the handler factory) also backs
**continuous-query predicate matching**, so a CQ like `WHERE r.status = 'active'` matches PDX objects
exactly as a one-shot query does (see `docs/CONTINUOUS_QUERIES.md`). For a *second* client to decode a
PDX value it did not itself write (e.g. a pushed CQ/subscription event value), the shim also serves the
**reverse PDX lookup** — GET_PDX_TYPE_BY_ID (opcode 92) returns the kept `PdxType`, stamped with its
assigned id, as a serialized object part. Only scalar fields are queryable; OBJECT/array PDX fields are
not.

**Joins are deferred (out of scope for now).** Cross-region joins are uncommon and discouraged in
GemFire (poor performance), and a Couchbase-backed shim would have to load both whole regions into
memory and cross-product them — acceptable only for tiny regions. The ROI does not justify the
required multi-source FROM parser, alias-aware field resolution (`a.fk = b.id`), and nested-loop
execution. Revisit only if a concrete need arises.

**Parameterized queries** (`query.execute($1, $2, …)`, opcode `QUERY_WITH_PARAMETERS = 80`): the
request carries the OQL string in part[0], an `int` bind-parameter count in part[1], and each bind
value as a Geode-serialized object in part[2..]. `QueryHandler` decodes the values (reusing the
standard value decoder), and `OqlQuery.bindParameters` substitutes `$N` with the corresponding value
rendered as an OQL literal (numbers/booleans bare, strings single-quoted, `null` as `null`) before
parsing — so binding reuses the full literal/predicate path and the response is the same chunked
result as a plain `QUERY`. Validated by `OqlQueryTest` + `ProtoGemCouchQueryIntegrationTest`
(`parameterizedQueryBindsValues`). Edge note: a string bind value containing a single quote is
doubled per OQL convention but not un-doubled on parse (rare; documented).

Captured `SELECT *` response (2 rows v1,v2), annotated:
```
HEADER(12): 00000001(msgType=RESPONSE) 00000002(numParts=2) ffffffff(txId)
CHUNK:      0000007b(chunkLen) 01(lastChunk)
  PART A:   0000005e(len=94) 01(isObject)  CollectionType: ResultsCollectionType<Object> (fixed)
  PART B:   00000013(len=19) 01(isObject)  result list:
            01 19 00000000   02(count)   00 5700027631(v1)   00 5700027632(v2)
```
Empty result uses a different part B (`34 00 2b…java.lang.Object`); both forms are emitted.

**Result paging (multi-chunk streaming).** A large `SELECT *` / single-field result is streamed as
multiple chunks instead of one, matching the real server: the header keeps `numberOfParts=2`, and
**each chunk repeats part0=CollectionType + part1=that batch's result list**, with the `lastChunk`
flag set only on the final chunk. The client's `QueryOp` reads `[part0, part1]` per chunk and
accumulates the rows across chunks into one `SelectResults`. The shim batches by row count
(`GemResponseWriter.QUERY_PAGE_SIZE`, default 100, overridable via `PGC_QUERY_PAGE_SIZE`); results at
or below the page size emit a single chunk byte-identical to before. The server's own batching is by
byte size (~800B/chunk, captured at 50/150/250 rows via `GeodeQueryCapture` — 1/2/3 chunks); the
shim's row-count batching is wire-compatible since the client only depends on the per-chunk
`[CollectionType, batch]` layout and the `lastChunk` flag. The **ORDER BY (`Object[]`) and struct
responses page the same way** (each chunk repeats its CollectionType/StructType + that batch's
`Object[]`; captured at 150 rows → 2 chunks each). Locked by `QueryResponsePagingTest` (SELECT */
ordered/struct) and validated end-to-end by `largeResultSetIsStreamedAcrossChunksAndFullyAssembled`
and `largeOrderedAndStructResultsArePagedAndAssembledInOrder`.

## Query pushdown (N1QL) — opt-in, `OQL_PUSHDOWN`

By default every OQL query is a **full-region scan**: the shim loads all of the region's values and
filters them in memory (`QueryHandler` → `repository.keySet` + `getAll` → `OqlQuery.matches`). That is
correct but O(region size) regardless of how selective the `WHERE` is (the full-surface soak measured
~474 ms full-scan queries — the biggest performance item in 1.1.0).

With **`OQL_PUSHDOWN=true`** (default off), eligible queries instead pre-filter at Couchbase via N1QL,
so the shim only fetches candidate documents. The design keeps results **identical to the scan**:

- **Eligibility (conservative).** Pushdown applies only when the `WHERE` is a single `AND`-group of
  **string-equality** conditions on simple top-level fields (`field = '…'`). Anything else — `OR`,
  ranges, `<>`/`!=`, numeric/boolean/null literals, dotted/nested fields, or no `WHERE` — is **not**
  pushed and runs the normal scan. (`OqlQuery.pushdownStringEqualities()`.) Projection and `ORDER BY`
  never block pushdown; they are applied in-shim to the candidates exactly as before.
- **The matcher stays authoritative.** N1QL only chooses *candidate* documents; the shim re-applies
  `OqlQuery.matches` (the same PDX-aware resolver as the scan), then projects/sorts/pages as usual. So
  the candidate set only has to be a **superset** of the true matches.
- **Superset, by construction.** The generated predicate (`CouchbaseRepository.queryPushdownByStringEquality`)
  is region-scoped (`META().id LIKE "region::%"`) and, per condition, compares the field's stored
  scalar by its string form under both JSON encodings (object-map `value.<f>.value` and string-map
  `value.<f>`), with a numeric branch OR-ed in for numeric literals. Crucially it also OR-s in **every
  non-map document** (`type NOT IN ["stringObjectHashMap","stringHashMap"]`) — PDX/serialized/scalar
  values whose fields N1QL cannot read are always kept as candidates and filtered by the shim, so a
  true PDX match is never dropped.
- **Read-your-writes.** The query runs with **`REQUEST_PLUS`** scan consistency, matching the KV scan
  path; without it N1QL's default (`not_bounded`) could miss a just-written document.
- **Graceful fallback.** Any backend problem (no usable index, query service down, error) returns
  "no pushdown" and the handler scans — pushdown can only change performance, never correctness. The
  `handler_query_ok` log carries `pushdown=true|false`; `repository_query_pushdown_ok` reports the
  candidate count.

**Operator step — secondary index (recommended).** Pushdown executes N1QL, which needs an index. The
shim does **not** create indexes. For each region/field you query, create a targeted GSI, e.g.:

```sql
CREATE INDEX idx_orders_status
  ON `your-bucket`(TO_STRING(`value`.`status`.`value`))
  WHERE META().id LIKE "orders::%";
```

Without a matching index N1QL falls back to a primary-index scan (or, with no primary index, the shim
falls back to the in-shim scan) — still correct, just not faster. The dev/test cluster creates a
`#primary` index (see `scripts/init-couchbase.sh`) purely so the pushdown path executes there;
production should prefer targeted GSIs and avoid a primary index.

**Caveats (current slice).** Only string-equality is pushed (numeric/range/`OR`/PDX-field predicates
still scan — later M2/M3 work). Pushdown reads via the Query service do **not** refresh entry-idle TTL
(the KV scan path does, via get-and-touch); relevant only when both `CB_TTL_MODE=idle` and pushdown are
enabled. Validated by `ProtoGemCouchQueryPushdownIntegrationTest` (string-equality, AND, range/`OR`
fallback, PDX superset, mixed region, projection) and `OqlQueryPushdownTest` (eligibility).

## Protocol shape (reverse-engineered from the Geode 1.15 client)

### Request — easy
- Opcode **`QUERY = 34`**, or **`QUERY_WITH_PARAMETERS = 80`** for parameterized queries (supported).
- `QueryOp` builds a 1-part message: **part[0] = the OQL string** (a plain string part). The
  parameterized form adds **part[1] = int bind-parameter count** and **part[2..] = each bind value**
  as a Geode-serialized object.

### Response — a *chunked* message (the hard part)
`QueryOp.processResponse` reads a **`ChunkedMessage`**, not a normal single REPLY, and assembles the
result into a `SelectResults`. The shim has no chunked-message writer yet — this is the new piece.

Wire format (from `ChunkedMessage`):
- **Header (12 bytes), sent once:** `messageType(int)`, `numberOfParts(int)`, `transactionId(int)`.
- **Each chunk:** `chunkLength(int)`, `lastChunk-flag(byte)` (bit `0x01` = last chunk), then the
  chunk's parts (each part = `length(int)`, `isObject(byte)`, `payload`).

Chunk contents (from `QueryOp`'s chunk handler):
- **First chunk, part[0] = a `CollectionType`** object (the result element type / collection type).
  If part[0] deserializes to a `Throwable`, the client raises `ServerOperationException`.
- **Subsequent chunks, part[0] = the result elements** (a batch object the client adds to the
  `SelectResults` — an `ObjectPartList`-style collection).

## Remaining work (the core)

1. **Chunked-message writer** in the wire layer: 12-byte header + per-chunk framing.
2. **`CollectionType` serialization** — an internal Geode `DataSerializableFixedID` object (same class
   of byte-level work as `EntrySnapshot`); for `SELECT *` the element type is the generic object type.
3. **Result serialization** — encode each region value (reusing the existing value-object encoders)
   into the result chunk in the form the client expects.
4. **`QueryHandler`** — parse via `OqlQuery`, gather the region's values (`keySet` + `getAll`), and
   write the chunked response. Register opcode 34 only once the response is correct (until then a
   query would hit the unknown-opcode handler).

### How to nail the byte format reliably
Rather than reconstruct the `CollectionType` / result-batch bytes from bytecode (fragile, as with
`EntrySnapshot`), **capture them from a real Geode server**: run a minimal embedded cache server,
seed a region, execute `SELECT * FROM /region` from a raw socket, and dump the response bytes. That
yields an exact template (header + chunk framing + `CollectionType` + result encoding) to replicate,
then validate against the real client with an integration test.

## Done so far
- `MessageTypes.QUERY` / `QUERY_DATA_ERROR` constants.
- `OqlQuery` parser for `SELECT * FROM /region` (rejects projections/`WHERE`/`DISTINCT`/joins as
  unsupported, so they can return a clean query error), with unit tests.
