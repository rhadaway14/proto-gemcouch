# ProtoGemCouch Serialization Notes

## Purpose

This document captures the key wire-encoding lessons from the current `GemResponseWriter` hardening work.

The most important rule is:

```text
Do not confuse Geode array/list length encoding with VersionedObjectList count encoding.
```

They look similar at the Java API level because both involve collections, but they are different wire formats.

---

## Geode array/list length encoding

Used by manually encoded `ArrayList` / list-style payloads, including `keySetOnServer`.

Encoding:

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

Validated by:

```text
keySetOnServerShouldHandleMoreThan127Keys
keySetOnServerShouldHandleMoreThan252Keys
buildKeySetChunkedResponse_with150Keys_usesInlineGeodeArrayLength
buildKeySetChunkedResponse_with253Keys_usesExtendedShortGeodeArrayLength
```

---

## VersionedObjectList count encoding

Used by `GET_ALL` responses. The Geode client deserializes this through `VersionedObjectList.fromData(...)`.

This does not use normal array/list length encoding. It uses unsigned variable-length integer encoding.

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

Validated by:

```text
getAllShouldHandleMoreThan127Keys
getAllShouldHandleMoreThan252Keys
putAllShouldHandleMoreThan127Entries
putAllShouldHandleMoreThan252Entries
buildGetAllChunkedResponse_with150Keys_usesUnsignedVlVersionedObjectListCount
buildGetAllChunkedResponse_with253Keys_usesUnsignedVlVersionedObjectListCount
buildGetAllChunkedResponse_doesNotUseGeodeArrayLengthEncodingForVersionedObjectListCount
```

---

## GET_ALL payload shape

The current manually encoded `GET_ALL` response payload uses a VersionedObjectList-compatible structure:

```text
01 07      DataSerializableFixedID object wrapper for VersionedObjectList
03         VersionedObjectList flags: has keys + has objects
<count>    unsigned variable-length integer key count
<keys>     Geode string-encoded keys
<count>    unsigned variable-length integer object count
<objects>  object marker + Geode object payload per key
```

Object markers:

```text
0x01 = present object
0x03 = key not at server / absent
```

---

## Regression symptom this prevents

Using `writeGeodeArrayLength(...)` inside a `VersionedObjectList` payload causes the Geode client to lose byte alignment and fail during deserialization.

Observed failure shape:

```text
VersionedObjectList.fromData(...)
Unknown header byte 0
```

Typical client-visible result:

```text
ServerConnectivityException
Could not create an instance of org.apache.geode.internal.cache.tier.sockets.VersionedObjectList
```

---


## Current verification baseline

```text
ProtoGemCouchCrudIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

ProtoGemCouchPdxRegistryDiscoveryIntegrationTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 3

ProtoGemCouchSerializationIntegrationTest
Tests run: 135, Failures: 0, Errors: 0, Skipped: 0

Total:
Tests run: 145, Failures: 0, Errors: 0, Skipped: 3

BUILD SUCCESS
```
