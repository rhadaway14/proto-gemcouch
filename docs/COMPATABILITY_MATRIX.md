# ProtoGemCouch Compatibility Matrix

This is the shim's **compatibility contract**: the Geode client surface it supports, the value types
it round-trips, and its explicit non-goals. Per-feature details and byte encodings follow; the
narrative roadmap lives in `docs/ROADMAP.md` and the release history in `CHANGELOG.md`.

## Compatibility contract (as of 0.2.0)

**Client / runtime.** Apache Geode (and GemFire-compatible) **Java clients on the 1.15.x line**,
validated against **1.15.1**. The shim runs on **JDK 17**. Clients connect with the standard Geode
client/server protocol over the shim's Geode port (TLS / mutual TLS optional).

**Supported client surface** (all validated end-to-end against a real Geode client):
```text
core entry ops:        get, put, remove, containsKey, containsValueForKey, getEntry
bulk ops:              getAll, putAll (partial-failure aware)
region metadata:       size, keySet (cross-process, contention-free keyset)
region ops:            invalidate, clear, destroyRegion
atomic ops:            putIfAbsent, replace(k,v), replace(k,old,new), remove(k,v)
OQL:                   SELECT (*|field|field,…) FROM /region [alias] [WHERE …] [ORDER BY …],
                       parameterized ($1..$N), struct projections + ORDER BY, paged results,
                       field access over map values and PDX object fields
transactions:          begin → put/get/remove → commit / rollback
subscriptions:         register-interest + server→client events (CacheListener fires)
continuous queries:    register + events (create/update/destroy, stops-matching), PDX-field
                       predicates, executeWithInitialResults
entry TTL:             default + per-region, time-to-live and idle modes, keyset eviction
```

**Explicit non-goals / not supported.** A real Geode client receives a clean, structured error (not a
crash or hang) for these:
```text
server-side function EXECUTION (the shim has no user Function classes; calls are rejected cleanly)
server-side region creation with custom attributes (destroyRegion IS supported; a client PROXY region is created locally and sends no server create, so dynamic create is a no-op)
single-hop / partitioned-region bucket routing (N/A — single backend; GET_CLIENT_PARTITION_ATTRIBUTES is a documented graceful no-op, the client falls back to direct routing)
field-level querying of custom DataSerializable values (the objects round-trip opaquely via the
  0x2d payload — the client gets its class back; the fields are not queryable, since DataSerializable
  carries no schema, unlike PDX)
full PDX registry discovery — bulk type/enum registry sync (PDX round-trip, field querying, and
  schema evolution across versions ARE supported)
some nested complex types inside HashMap<String,Object> stay opaque (still round-trip, not queryable):
  Serializable POJOs, PDX, typed object arrays, java.time values (generic Object[]/ArrayList/nested
  Map/UUID/BigInteger/BigDecimal/enum nested in a map ARE now structured; top-level works; see below)
OQL joins
the Geode application-level security handshake (use transport TLS / mutual TLS instead)
```

## Validated profile

```text
core CRUD-style operations
bulk PUT_ALL / GET_ALL operations
server-side metadata-style operations
typed scalar values
primitive arrays
wrapper / utility arrays
standalone utility values
Serializable POJOs
Object[]
ArrayList<Object>
PDX / PdxInstance round-tripping + object-field querying
large key-set and bulk collection boundary handling
```

The latest serialization hardening work added explicit coverage for collection count boundaries that previously caused client deserialization failures:

```text
keySetOnServer with more than 127 keys
keySetOnServer with more than 252 keys
getAll with more than 127 keys
getAll with more than 252 keys
putAll followed by getAll with more than 127 entries
putAll followed by getAll with more than 252 entries
```

## Verification

Every supported item above is validated end-to-end against a real Geode 1.15.1 client. As of 0.2.0:

```text
~500 unit / shape / golden-wire tests (mvn -o test)
full Docker-backed integration suite green (mvn verify): 20+ classes covering CRUD, atomic ops,
  OQL (incl. query/PDX-field/parameterized), transactions, subscriptions, continuous queries,
  server-side-function rejection, large-value limits, TTL (time-to-live + idle), multi-replica,
  chaos (backend outage + shim restart), TLS / mutual TLS / TLS-policy, audit logging, and 135
  serialization value-shape cases
```

Verification commands:

```powershell
mvn -o test                      # unit + shape + golden-wire
mvn verify                       # full Docker-backed integration suite (real Geode client)
```

## Supported Operations

| Operation | Status | Notes |
|---|---:|---|
| connect / handshake | Supported | Java Geode client connects to the shim. |
| region access | Supported | Validated against the configured region, commonly `helloWorld`. |
| `put` | Supported | Supports all currently validated value families. |
| `get` | Supported | Returns Geode-compatible typed values. |
| `putAll` | Supported | Supports batch typed values and large-entry boundary coverage. |
| `getAll` | Supported | Uses a VersionedObjectList-compatible response payload with unsigned variable-length counts. |
| `remove` | Supported | Removes mapped Couchbase document. |
| `getEntry` | Supported | Returns an `EntrySnapshot`-compatible response. |
| `invalidate` / `clear` | Supported | `invalidate` keeps the key, drops the value; `clear` empties the region. |
| `destroyRegion` | Supported | `Region.destroyRegion()` (opcode 11) removes all of the region's entries + keyset metadata; the schemaless shim re-materializes the region on the next write. |
| `containsKey` / `containsKeyOnServer` | Supported | Repository-backed existence check. |
| `containsValueForKey` | Supported | Repository-backed value-present check. |
| `sizeOnServer` | Supported | Region document count. |
| `keySetOnServer` | Supported | Returns region keys using Geode list/array length encoding. |
| atomic ops | Supported | `putIfAbsent`, `replace(k,v)`, `replace(k,old,new)`, `remove(k,v)` — CAS-backed, Geode-accurate returns. |
| OQL query | Supported | `SELECT`/`WHERE`/`ORDER BY`, parameterized, struct projections, paged; map + PDX field access. Unsupported shapes return a clean server error. |
| transactions | Supported | `begin → put/get/remove → commit`/`rollback` (commit returns a `TXCommitMessage`). |
| register-interest / subscriptions | Supported | Server→client event feed; a `CacheListener` fires for create/update/destroy/invalidate. |
| continuous queries | Supported | Register + events (create/update/destroy, stops-matching), PDX-field predicates, `executeWithInitialResults`. |
| server-side functions | Rejected cleanly | The shim cannot run user `Function` code; `EXECUTE_FUNCTION` / `GET_FUNCTION_ATTRIBUTES` return a clean `ServerOperationException`. |
| PDX type lookup | Supported | Forward (`GET_PDX_ID_FOR_TYPE`) and reverse (`GET_PDX_TYPE_BY_ID`) — a second client can decode PDX it did not write. |
| PDX schema evolution | Supported | Multiple versions of one class name (fields added/removed) coexist as distinct types; each instance round-trips with its own fields and OQL resolves fields per version. Bulk registry discovery remains scoped. |
| unknown opcode logging | Supported | Logs unknown frame details without crashing the process. |

## Supported Value Types

| Java / Geode Type | Marker / Shape | Runtime | Unit Tests | Integration Tests |
|---|---|---:|---:|---:|
| `String` | `0x57` | Yes | Yes | Yes |
| `Boolean` | `0x35` | Yes | Yes | Yes |
| `Character` | `0x36` | Yes | Yes | Yes |
| `Byte` | `0x37` | Yes | Yes | Yes |
| `Short` | `0x38` | Yes | Yes | Yes |
| `Integer` | `0x39` | Yes | Yes | Yes |
| `Long` | `0x3a` | Yes | Yes | Yes |
| `Float` | `0x3b` | Yes | Yes | Yes |
| `Double` | `0x3c` | Yes | Yes | Yes |
| `java.util.Date` | `0x3d` | Yes | Yes | Yes |
| `byte[]` | `0x2e` or raw bytes | Yes | Yes | Yes |
| `boolean[]` | `0x1a` | Yes | Yes | Yes |
| `char[]` | `0x1b` | Yes | Yes | Yes |
| `short[]` | `0x2f` | Yes | Yes | Yes |
| `int[]` | `0x30` | Yes | Yes | Yes |
| `long[]` | `0x31` | Yes | Yes | Yes |
| `float[]` | `0x32` | Yes | Yes | Yes |
| `double[]` | `0x33` | Yes | Yes | Yes |
| `String[]` | `0x40` | Yes | Yes | Yes |
| `Integer[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Long[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Boolean[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Double[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `UUID[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `BigInteger[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `BigDecimal[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Enum[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `Instant[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `LocalDate[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `LocalDateTime[]` | `0x34` object-array envelope | Yes | Yes | Yes |
| `ArrayList<String>` | `0x41` string-only list | Yes | Yes | Yes |
| `HashMap<String,String>` | `0x43` empty or `0x2c aced...` | Yes | Yes | Yes |
| `HashMap<String,Object>` | `0x43` empty or `0x2c aced...` | Yes | Yes | Yes |
| Serializable POJO | `0x2c aced...` | Yes | Yes | Yes |
| `Object[]` | `0x34 ... <component-type> ...` | Yes | Yes | Yes |
| `ArrayList<Object>` | `0x41 ... mixed elements ...` | Yes | Yes | Yes |
| `UUID` | `0x62` opaque standalone utility | Yes | Yes | Yes |
| `BigInteger` | `0x5f` opaque standalone utility | Yes | Yes | Yes |
| `BigDecimal` | `0x60` opaque standalone utility | Yes | Yes | Yes |
| `Enum` | `0x65` opaque standalone utility | Yes | Yes | Yes |
| `java.time.Instant` | `0x2c` Java serialized | Yes | Yes | Yes |
| `java.time.LocalDate` | `0x2c` Java serialized | Yes | Yes | Yes |
| `java.time.LocalDateTime` | `0x2c` Java serialized | Yes | Yes | Yes |
| PDX / `PdxInstance` | `0x5d` opaque PDX payload | Yes | Yes | Yes |
| custom `DataSerializable` | `0x2d` opaque DataSerializable payload | Yes | Yes | Yes |

## Collection Response Encoding Compatibility

ProtoGemCouch has two separate collection count encodings. These must not be mixed.

### Geode array/list length encoding

Used by manually encoded list-style payloads such as `keySetOnServer`.

```text
0..252   -> one byte containing the count
0xfe     -> two following bytes contain the count
0xfd     -> four following bytes contain the count
0xff     -> null array/list marker
```

Examples:

```text
150 -> 96
253 -> fe 00 fd
```

Implementation helper:

```java
writeGeodeArrayLength(...)
```

### VersionedObjectList count encoding

Used by `GET_ALL` responses. Geode deserializes this through `VersionedObjectList.fromData(...)`.

This uses unsigned variable-length integer encoding, not the normal array/list length encoding.

Examples:

```text
127 -> 7f
128 -> 80 01
150 -> 96 01
253 -> fd 01
```

Implementation helper:

```java
writeVersionedObjectListCount(...)
```

## Primitive Array Support

Primitive arrays use dedicated Geode DataSerializer markers:

```text
boolean[]  -> 0x1a
char[]     -> 0x1b
byte[]     -> 0x2e
short[]    -> 0x2f
int[]      -> 0x30
long[]     -> 0x31
float[]    -> 0x32
double[]   -> 0x33
```

Primitive arrays are decoded structurally and stored in Couchbase as typed JSON array envelopes.

## Wrapper and Utility Array Support

Wrapper and utility arrays use Geode's `0x34` object-array style envelope.

Examples:

```text
Integer[]        -> 0x34 ... java.lang.Integer ...
UUID[]           -> 0x34 ... java.util.UUID ...
BigDecimal[]     -> 0x34 ... java.math.BigDecimal ...
Instant[]        -> 0x34 ... java.time.Instant ...
LocalDate[]      -> 0x34 ... java.time.LocalDate ...
LocalDateTime[]  -> 0x34 ... java.time.LocalDateTime ...
```

Design decision:

```text
The shim preserves the entire 0x34 payload opaquely, regardless of component type.
Returning the original Geode payload lets the Geode client deserialize the exact original array type.
```

## Standalone Utility Value Support

Standalone utility values use a mix of dedicated Geode markers and Java serialization.

```text
BigInteger              -> 0x5f
BigDecimal              -> 0x60
UUID                    -> 0x62
Enum                    -> 0x65
java.time.Instant       -> 0x2c Java serialized
java.time.LocalDate     -> 0x2c Java serialized
java.time.LocalDateTime -> 0x2c Java serialized
```

## PDX / PdxInstance Support

PDX payloads are preserved opaquely and returned with their original Geode PDX marker.

```text
PdxInstance -> 0x5d <payload...>
```

Validated PDX paths include:

```text
put/get with simple PdxInstance
put/get with primitive and String array PDX fields
put/get with Object[] PDX fields
put/get with ArrayList<Object> PDX fields
put/get with nested Map PDX fields
put/get with UUID, BigInteger, BigDecimal, Instant, LocalDate, LocalDateTime, and Enum fields
putAll/getAll with PDX values
mixed primitive and PDX values in putAll/getAll
remove with PDX values
containsKeyOnServer with PDX-backed keys
keySetOnServer with PDX-backed keys
```

Current PDX scope remains a compatibility profile. Schema evolution across versions (fields
added/removed, queried per instance version) is supported and validated; full PDX *registry
discovery* (bulk type/enum sync) and all native Geode PDX server semantics are not yet claimed as
general-purpose replacements.

## Structured Map Nested Value Support

A structured `HashMap<String,Object>` envelope is decoded into queryable JSON (its top-level scalar
fields are reachable by OQL `WHERE` / projection / `ORDER BY`). The supported value set is now
**recursive** — a map keeps that structured form as long as every value (at every depth) is in the
set below. If any value falls outside it, the whole map falls back to the **opaque** Java-serialized
form, which still round-trips exactly but is not queryable.

Currently supported nested values in structured `HashMap<String,Object>` envelopes:

```text
null
String, Boolean, Character, Byte, Short, Integer, Long, Float, Double
java.util.Date
byte[], boolean[], char[], short[], int[], long[], float[], double[], String[]
java.util.UUID, java.math.BigInteger, java.math.BigDecimal
enum constants  (any enum whose class is on the shim classpath)
Object[]                       (a generic Object[]; recursively, with supported elements)
ArrayList                      (recursively, with supported elements — not just ArrayList<String>)
HashMap / LinkedHashMap<String,Object>   (nested maps, recursively)
```

Not supported inside structured map envelopes (a map containing any of these stays opaque — it
round-trips exactly, but is not queryable):

```text
Serializable POJO (customer domain classes — the shim has no class to deserialize/query)
PDX / PdxInstance
typed object arrays: Integer[], Long[], UUID[], BigDecimal[], Instant[], ...
  (kept type-exact on the opaque path; only a generic Object[] is promoted to the structured form)
java.time values (Instant, LocalDate, LocalDateTime, ...) and other standalone utility values
non-ArrayList List implementations (LinkedList, Arrays.asList, ...)
```

**Round-trip fidelity is equals-level** (matching the existing top-level behavior, where a client
`HashMap` already comes back as a `LinkedHashMap`): a nested `Object[]` / `ArrayList` / `Map`
reconstructs as `Object[]` / `ArrayList` / `LinkedHashMap` with every element value and scalar runtime
type preserved, so `Arrays.equals` / `List.equals` / `Map.equals` hold; the concrete container class
is normalized.

Top-level `Object[]`, top-level wrapper/utility arrays, top-level `ArrayList<Object>`, top-level
Serializable POJOs, top-level PDX values, and top-level standalone utility values are supported (each
preserved type-exactly).

## Known Limitations

These are the current non-goals (see the contract at the top). Transactions, queries, continuous
queries, interest registration/subscriptions, and entry TTL — listed here in earlier releases — are
now supported and have moved up to the contract.

```text
Nested complex types inside structured Map<String,Object> that stay opaque (round-trip exactly but
  are not queryable): Serializable POJOs, PDX/PdxInstance, typed object arrays (Integer[], UUID[],
  ...), java.time / standalone utility values, non-ArrayList Lists. (Generic Object[], ArrayList,
  nested Map, UUID, BigInteger, BigDecimal, and enum constants nested inside a map ARE now decoded
  structurally; top-level forms of everything ARE supported.)
field-level querying of custom DataSerializable values (they round-trip opaquely; not queryable)
server-side function EXECUTION (calls are rejected cleanly; the shim cannot run user Function code)
server-side region creation with custom attributes (destroyRegion IS supported)
single-hop / partitioned-region bucket routing (N/A — single backend; GET_CLIENT_PARTITION_ATTRIBUTES is a documented graceful no-op, the client falls back to direct routing)
full PDX registry discovery + schema evolution (PDX round-trip + object-field querying ARE supported)
OQL joins
```

## Roadmap

The prioritized backlog (next parity and production-readiness targets) lives in `docs/ROADMAP.md`;
release history is in `CHANGELOG.md`.
