# ProtoGemCouch Compatibility Matrix

This is the shim's **compatibility contract**: the Geode client surface it supports, the value types
it round-trips, and its explicit non-goals. Per-feature details and byte encodings follow; the
narrative roadmap lives in `docs/ROADMAP.md` and the release history in `CHANGELOG.md`.

## Compatibility contract (as of 1.2.0)

**Client / runtime.** Apache Geode (and GemFire-compatible) **Java clients on the 1.15.x line**,
validated against **1.15.1**. The shim runs on **JDK 17**. Clients connect with the standard Geode
client/server protocol over the shim's Geode port (TLS / mutual TLS optional).

**1.2.0 introduces no new client-facing wire forms.** The 1.2.0 features are server-side or reuse
existing forms, so the validated client range and protocol ordinals below are **unchanged**: multi-replica
**durable subscriptions** use the standard, version-negotiated Geode durable-client protocol
(`durable-client-id` + `readyForEvents`) and replay the **same** interest/CQ event wire forms already
emitted in 1.1.0; **keyset-metadata sharding** and **hot TLS reload** are entirely server-internal; the
nested **`java.time`** change affects only server-side queryability, not the bytes a client sends. The
durable event forms are validated end-to-end against a real 1.15.1 client (the durable-subscription
integration + k8s failover suites), and the cross-version core matrix re-runs on every `v*` release tag.

**Protocol version negotiation.** On connect, the shim parses the client's protocol version ordinal
from the handshake and accepts only supported versions — the default is the wire-validated **1.15.x**
line (ordinal **150**). A client advertising an unsupported version is **refused cleanly** with a Geode
`REPLY_REFUSED` handshake (the client raises `ServerRefusedConnectionException`) rather than being
served a 1.15.x-shaped session it can't decode; rejections increment
`protogemcouch_handshake_version_rejected_total` and emit a `handshake_version_rejected` audit event.
Operators who have validated additional wire-compatible Geode versions in their own environment can
widen the allowlist via `SUPPORTED_VERSION_ORDINALS` (comma-separated ordinals). (Capture a new
client's ordinal with `com.protogemcouch.tools.HandshakeCapture`.)

**Cross-version interop (CI-validated).** A CI matrix (`.github/workflows/cross-version-matrix.yml`) runs
**real Geode `1.13.0` / `1.14.0` / `1.15.1` clients** against a shim with `SUPPORTED_VERSION_ORDINALS`
widened to `120,125,150`, exercising the core wire surface (CRUD, bulk, `size`/`containsKey`, and OQL over
both map and PDX values) via the standalone `cross-version-client/` harness — which depends only on the
public Geode client API, so each version compiles cleanly without recompiling the shim (whose internal-API
use is version-sensitive). A green matrix confirms the shim's 1.15.x wire forms are interoperable with
1.13.x/1.14.x clients; the **default** policy still accepts 1.15.x only, so widening is an opt-in operators
make after seeing this validation. **Re-validated green for 1.2.0** (all three client versions, 2026-06-24).

**Geode client protocol version → ordinal** (authoritative, from `KnownVersion`; the value the shim
reads from the handshake). The protocol ordinal is per **minor** release — every patch within a minor
(e.g. all of 1.15.x) shares it, so cross-patch interop is guaranteed and validated implicitly by the
whole 1.15.x integration suite.

| Geode version | ordinal | | Geode version | ordinal |
| --- | --- | --- | --- | --- |
| 8.1 | 35 | | 1.10.0 | 105 |
| 9.0 | 45 | | 1.11.0 | 110 |
| 1.1.x | 50–55 | | 1.12.0 / 1.12.1 | 115 / 116 |
| 1.2.0 | 65 | | 1.13.0 / 1.13.2 | 120 / 121 |
| 1.3.0–1.9.0 | 70–100 | | 1.14.0 | 125 |
| | | | **1.15.x (validated)** | **150** |

Ordinals ≤ 127 are sent as a single handshake byte; larger ordinals (1.15.x = 150) use a `0xFF` token
+ 2-byte short. The accept/refuse decision is matrix-tested across the entire table
(`HandshakeVersionMatrixTest`), and refusal of a lower-version client is validated end-to-end against
the live shim by replaying a real handshake with the ordinal swapped to 1.14
(`ProtoGemCouchVersionNegotiationIntegrationTest`). **Caveat for widening:** the shim does not do
versioned serialization — it emits the 1.15.x wire forms — so adding a lower minor to the allowlist is
only safe where that version's wire forms match; validate with `scripts/cross-version-matrix.sh` (which
runs the core suite under a chosen client `geode.version`) before relying on it.

**Supported client surface** (all validated end-to-end against a real Geode client):
```text
core entry ops:        get, put, remove, containsKey, containsValueForKey, getEntry
bulk ops:              getAll, putAll (partial-failure aware)
region metadata:       size, keySet (cross-process, contention-free keyset)
region ops:            invalidate, clear, destroyRegion
atomic ops:            putIfAbsent, replace(k,v), replace(k,old,new), remove(k,v)
OQL:                   SELECT (*|field|field,…) FROM /region [alias] [WHERE …] [ORDER BY …] [LIMIT n],
                       parameterized ($1..$N), struct projections + ORDER BY, paged results,
                       field access over map values and PDX object fields, incl. nested object
                       paths (r.address.zip), scalar arrays (r.tags[0], 'x' IN r.tags), and
                       object-arrays of nested PDX (r.addresses[0].zip, element-equality IN) for
                       maps + PDX
transactions:          begin → put/get/remove → commit / rollback
subscriptions:         register-interest + server→client events (CacheListener fires); durable
                       clients (durable-client-id + readyForEvents) with multi-replica HA —
                       events missed while away replay from Couchbase on reconnect to ANY replica
continuous queries:    register + events (create/update/destroy, stops-matching), PDX-field
                       predicates, executeWithInitialResults; durable CQs replay cross-replica
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
a couple of nested complex types inside HashMap<String,Object> stay opaque (still round-trip, not
  queryable): Serializable POJOs and nested PDX values (both need the user's classes). Generic Object[],
  TYPED object arrays, any List, any Set, nested String-keyed Maps, UUID/BigInteger/BigDecimal/enum, and
  java.time nested in a map ARE structured + queryable; top-level works; see below)
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

Every supported item above is validated end-to-end against a real Geode 1.15.1 client. As of 1.0.0:

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
| OQL query | Supported | `SELECT`/`WHERE`/`ORDER BY`/`LIMIT`, parameterized, struct projections, paged; map + PDX field access incl. nested object paths (`r.address.zip`), scalar arrays (`r.tags[0]`, `'x' IN r.tags`), and object-arrays of nested PDX (`r.addresses[0].zip`, element-equality `IN`). Optional N1QL pushdown (`OQL_PUSHDOWN`). Unsupported shapes return a clean server error. |
| transactions | Supported | `begin → put/get/remove → commit`/`rollback` (commit returns a `TXCommitMessage`). |
| register-interest / subscriptions | Supported | Server→client event feed; a `CacheListener` fires for create/update/destroy/invalidate. |
| continuous queries | Supported | Register + events (create/update/destroy, stops-matching), PDX-field predicates, `executeWithInitialResults`. |
| server-side functions | Rejected cleanly | The shim cannot run user `Function` code; `EXECUTE_FUNCTION` / `GET_FUNCTION_ATTRIBUTES` return a clean `ServerOperationException`. |
| PDX type lookup | Supported | Forward (`GET_PDX_ID_FOR_TYPE`) and reverse (`GET_PDX_TYPE_BY_ID`) — a second client can decode PDX it did not write. With `PDX_PERSISTENCE` on, the reverse lookup resolves any id on any replica (loaded from Couchbase). |
| PDX registry discovery | Supported | Bulk type/enum sync (`GET_PDX_TYPES`, `GET_PDX_ENUMS`) returns the whole registry as a `Map<Integer,…>`; `GET_PDX_ENUM_BY_ID` is the reverse enum lookup. With `PDX_PERSISTENCE` on, the bulk reply includes the whole **persisted cluster-wide** registry, so a fresh replica serves it all. |
| PDX schema evolution | Supported | Multiple versions of one class name (fields added/removed) coexist as distinct types; each instance round-trips with its own fields and OQL resolves fields per version — across replicas with `PDX_PERSISTENCE` on. |
| PDX registry durability / multi-replica | Optional (`PDX_PERSISTENCE`) | Off by default (in-memory per instance). On: type/enum ids are allocated from a cluster-wide durable Couchbase registry, so ids are consistent across replicas and survive a restart (no cross-replica mis-decode). |
| client-side cache callbacks | Supported | A client's `CacheLoader` (fills a get-miss), `CacheWriter` (veto/allow before the op is sent), and `CacheListener` (fires on server-pushed events) run in the client JVM and compose with the shim. |
| server-side cache callbacks | Non-goal | Server-registered `CacheLoader`/`CacheWriter`/`CacheListener` and server-side expiration/eviction *events* run user code / event synthesis the stateless shim does not host (TTL is applied via Couchbase expiry). |
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
java.time.Instant, java.time.LocalDate, java.time.LocalDateTime
byte[], boolean[], char[], short[], int[], long[], float[], double[], String[]
java.util.UUID, java.math.BigInteger, java.math.BigDecimal
enum constants  (any enum whose class is on the shim classpath)
Object[]                       (a generic Object[]; recursively, with supported elements)
typed object arrays            (Integer[], Long[], UUID[], BigInteger[], Instant[], Date[], enum[], …
                                — reconstructed type-exact, so Arrays.equals holds) [1.3.0-M3]
List                           (ArrayList, LinkedList, Vector, … — recursively; reconstructs as ArrayList) [1.3.0-M3]
Set                            (HashSet, LinkedHashSet, TreeSet, … — recursively; reconstructs as LinkedHashSet) [1.3.0-M3]
HashMap / LinkedHashMap<String,Object>   (nested maps, recursively)
```

Not supported inside structured map envelopes (a map containing one of these stays opaque — it
round-trips exactly, but is not queryable):

```text
Serializable POJO (customer domain classes — the shim has no class to deserialize/query)
PDX / PdxInstance nested as a map value (query a PDX value at the top level instead, where its fields ARE queryable)
a Map with non-String keys (a structured JSON object's keys are strings)
```

**Round-trip fidelity is equals-level** for collections (matching the existing top-level behavior, where
a client `HashMap` already comes back as a `LinkedHashMap`): a nested generic `Object[]` / `List` / `Set`
/ `Map` reconstructs as `Object[]` / `ArrayList` / `LinkedHashSet` / `LinkedHashMap` with every element
value and scalar runtime type preserved, so `Arrays.equals` / `List.equals` / `Set.equals` / `Map.equals`
hold; the concrete container class is normalized. **Typed object arrays** are stronger — they reconstruct
to their exact component type (`Integer[]` stays `Integer[]`).

Top-level `Object[]`, top-level wrapper/utility arrays, top-level `ArrayList<Object>`, top-level
Serializable POJOs, top-level PDX values, and top-level standalone utility values are supported (each
preserved type-exactly).

## Known Limitations

These are the current non-goals (see the contract at the top). Transactions, queries, continuous
queries, interest registration/subscriptions, and entry TTL — listed here in earlier releases — are
now supported and have moved up to the contract.

```text
Nested complex types inside structured Map<String,Object> that stay opaque (round-trip exactly but
  are not queryable): Serializable POJOs and nested PDX/PdxInstance values (both need the user's
  classes). (Scalars + wrappers, Date, UUID/BigInteger/BigDecimal, java.time, enums, primitive +
  String arrays, generic Object[], TYPED object arrays, any List, any Set, and nested String-keyed
  Maps nested inside a map ARE decoded structurally + queryable; top-level forms of everything ARE
  supported.)
field-level querying of custom DataSerializable values (they round-trip opaquely; not queryable)
server-side function EXECUTION (calls are rejected cleanly; the shim cannot run user Function code)
server-side region creation with custom attributes (destroyRegion IS supported)
single-hop / partitioned-region bucket routing (N/A — single backend; GET_CLIENT_PARTITION_ATTRIBUTES is a documented graceful no-op, the client falls back to direct routing)
OQL joins
```

## Roadmap

The prioritized backlog (next parity and production-readiness targets) lives in `docs/ROADMAP.md`;
release history is in `CHANGELOG.md`.
