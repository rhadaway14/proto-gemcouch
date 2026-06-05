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
captured + byte-matched).

**PDX field access**: WHERE / projection / ORDER BY can read fields of stored PDX instances. The
shim keeps each registered `PdxType` (from opcode 93) by id; a PDX instance is framed
`5d <len> <typeId> <fieldData>`, and `PdxFieldAccessor` looks up the `PdxType` by the embedded id and
uses Geode's own `PdxReaderImpl` to read scalar fields by name (so the PDX binary/offset handling is
exactly correct). Validated by `PdxFieldAccessorTest` (real captured PdxType + instance bytes) and a
real-client PDX query test. Note: POJO (Java-serialized) field access is not feasible server-side
(needs the domain classes); PDX is Geode's queryable serialization. (ORDER BY on struct projections,
joins remain TODO.)

Captured `SELECT *` response (2 rows v1,v2), annotated:
```
HEADER(12): 00000001(msgType=RESPONSE) 00000002(numParts=2) ffffffff(txId)
CHUNK:      0000007b(chunkLen) 01(lastChunk)
  PART A:   0000005e(len=94) 01(isObject)  CollectionType: ResultsCollectionType<Object> (fixed)
  PART B:   00000013(len=19) 01(isObject)  result list:
            01 19 00000000   02(count)   00 5700027631(v1)   00 5700027632(v2)
```
Empty result uses a different part B (`34 00 2b…java.lang.Object`); both forms are emitted.

## Protocol shape (reverse-engineered from the Geode 1.15 client)

### Request — easy
- Opcode **`QUERY = 34`** (`QUERY_WITH_PARAMETERS = 80` for parameterized queries; not in scope).
- `QueryOp` builds a 1-part message: **part[0] = the OQL string** (a plain string part).

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
