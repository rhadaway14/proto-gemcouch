# ProtoGemCouch Next Steps

## Current Milestone

The current milestone is complete.

```text
primitive-array-family-support-complete
```

The project now has a validated automated test harness covering:

- unit tests
- shape tests
- golden-wire style response tests
- Docker Compose startup
- Couchbase initialization
- shim startup
- real Geode client integration
- Couchbase persistence
- end-to-end compatibility tests
- full primitive-array family round trips

Latest validated results:

```text
ProtoGemCouchCrudIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

ProtoGemCouchSerializationIntegrationTest
Tests run: 81, Failures: 0, Errors: 0, Skipped: 0

Total Docker-backed integration tests: 88 passing
Build: SUCCESS
```

## Completed Milestones

```text
object-array-support-complete
object-array-list-support-complete
int-array-support-complete
primitive-array-family-support-complete
```

## What Is Now Supported

```text
String
Boolean
Character
Byte
Short
Integer
Long
Float
Double
java.util.Date
byte[]
boolean[]
char[]
short[]
int[]
long[]
float[]
double[]
String[]
ArrayList<String>
HashMap<String,String>
HashMap<String,Object>
Serializable POJO
Object[]
ArrayList<Object>
```

Validated operation paths include:

```text
put
get
putAll
getAll
remove
containsKey
sizeOnServer
keySetOnServer
```

## Recommended Next Milestone

```text
wrapper-arrays-and-utility-types
```

Recommended target types:

```text
Integer[]
Long[]
Boolean[]
Double[]
UUID
BigDecimal
BigInteger
Enum
java.time.Instant
java.time.LocalDate
java.time.LocalDateTime
```

## Why This Is the Best Next Step

The primitive-array family is now complete. The next common compatibility gap is application-level Java value types that appear frequently in customer data models but are less complex than PDX or custom DataSerializable objects.

## Suggested Implementation Sequence

For each target family:

```text
Shape test
ValueDecoding support
StoredValue representation
GemResponseWriter GET / GET_ALL support
PutHandler / PutAllHandler support
GetHandler / GetAllHandler support
CouchbaseRepository persistence / hydration
Unit tests
Docker-backed integration tests
Docs update
```

## Proposed Phase 1 - Wrapper Array Shape Discovery

Start with shape tests for:

```text
Integer[]
Long[]
Boolean[]
Double[]
```

Goal:

```text
Identify whether Geode encodes these as Object[] payloads, Java serialized arrays, or dedicated DataSerializer shapes.
```

## Proposed Phase 2 - Common Utility Values

Add shape tests for:

```text
UUID
BigDecimal
BigInteger
Enum
java.time.Instant
java.time.LocalDate
java.time.LocalDateTime
```

Recommended preference:

- Store structurally when the type has a simple, stable JSON representation.
- Preserve opaquely when Java classloading or serialization boundaries become risky.

## Proposed Phase 3 - Production Hardening After Type Expansion

After wrapper arrays and common utility values, return to production hardening:

```text
high-concurrency load testing
soak testing
failure-mode testing
larger protocol capture library
release support contract
observability dashboards
deployment hardening
```

## Immediate Commands

Commit the primitive-array milestone and docs update:

```powershell
git status
git add .
git commit -m "Add primitive array family support"
git tag primitive-array-family-support-complete
```

Then begin the next branch:

```powershell
git checkout -b feature/wrapper-arrays-and-utility-types
```
