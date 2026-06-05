# OQL query support — design notes

Status: **in progress.** First-cut scope is `SELECT * FROM /region` (return all values in a region).
The request side and the query parser are done; the chunked query-response writer is the remaining
core piece.

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
