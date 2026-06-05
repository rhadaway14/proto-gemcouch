# Client transactions

The shim supports Geode client transactions (`CacheTransactionManager.begin()` /
`commit()` / `rollback()`) as a bounded first cut: enough for the canonical
"begin → write a few keys → commit (or rollback)" flow, validated against a real Geode 1.15 client.

## Wire protocol

- `begin()` is **client-side only** — no message is sent. The client allocates a transaction id.
- Every operation issued inside the transaction is sent **eagerly** with that transaction id in the
  message header (`txId >= 0`; non-transactional ops use `-1`). The server is expected to hold these
  in a per-transaction state rather than applying them immediately.
- `commit()` sends **COMMIT (opcode 85)**. The reply is a `RESPONSE` whose single object part is a
  serialized `org.apache.geode.internal.cache.TXCommitMessage` (DSFID 110); the client reads it via
  `CommitOp.processObjResponse`.
- `rollback()` sends **ROLLBACK (opcode 87)**. The reply is a plain `REPLY` ack
  (`RollbackOp.processAck` accepts any `REPLY`-typed message).
- An empty transaction (no ops) sends nothing on commit — the client short-circuits it.

## How the shim implements it

- `TransactionRegistry` holds a `TxState` buffer per `(connection, txId)`. A Geode client pins a
  transaction to one server connection, so that pair uniquely identifies a transaction.
- `TxState` records ordered PUT/REMOVE ops keyed by document id (last write per key wins, first-seen
  order preserved for a deterministic apply order).
- `PutHandler` / `RemoveHandler` buffer the op when `txId >= 0` and reply with the normal
  put/remove reply. The read handlers consult the buffer first (**read-your-writes**) and otherwise
  fall through to committed storage: `GetHandler` and `ContainsHandler` answer per-key from the
  buffer (a buffered PUT is present/returned, a buffered REMOVE reads as absent), while
  `GetAllHandler` overlays the buffer on the fetched values and `SizeOnServerHandler` /
  `KeySetOnServerHandler` overlay the buffer's net adds/removes on the committed key set
  (`TxState.regionOverlay`).
- `CommitHandler` applies the buffered ops to the repository in order, then returns a **zero-region
  `TXCommitMessage`**. With no region content changes there is nothing for a proxy-region client to
  apply locally, so a fixed, valid skeleton (carrying the shim's stable committing-member identity)
  is accepted for any commit; only `TXId.uniqId` is patched per commit to the transaction id.
- `RollbackHandler` drops the buffer and returns a `REPLY` ack.

### The `TXCommitMessage` template

The COMMIT reply object is the brittle part — it is a complex internal Geode object tied to cluster
topology. Rather than hand-roll it, `GemResponseWriter` embeds a **captured, round-trip-validated**
zero-region `TXCommitMessage` skeleton. `tools/TxCommitProbe` is the dev tool that produced and
validates it: it captures a real commit, deserializes the `TXCommitMessage` via Geode's own
`DataSerializer`, empties its `regions` list, re-serializes it (`toData`), and confirms the result
deserializes again. Run `CHECK_BUILT=1 …TxCommitProbe` to assert the shim's own
`buildCommitResponse` bytes deserialize as a real client reads them. If the template ever needs
regenerating (e.g. a Geode version bump), run `TxCommitProbe`, copy the `0-region message` hex into
`GemResponseWriter.TX_COMMIT_MESSAGE_TEMPLATE`, and keep `TX_COMMIT_UNIQ_ID_OFFSET` at 6.

## Supported

- `begin` / `commit` / `rollback` over a single connection.
- Transactional `put` and `remove` (buffered, applied on commit, discarded on rollback).
- **Read-your-writes** within a transaction across `get`, `containsKey`, `getAll`, `size`, and
  `keySet` — all see the transaction's own buffered writes/removes overlaid on committed state.

## Known limitations (documented gaps)

- **Commit is best-effort sequential**, not cross-key atomic. A mid-apply failure leaves earlier ops
  applied and returns a Geode `EXCEPTION` reply. True atomicity would use Couchbase multi-document
  ACID transactions.
- **In-transaction compare ops** (`putIfAbsent`, `replace(k,v)`, `replace(k,old,new)`,
  `remove(k,v)`) buffer as a plain put/remove; their compare semantics are not evaluated within the
  transaction.
- **JTA `TX_SYNCHRONIZATION` (opcode 90)** and transaction failover are not handled.
- The committing-member identity in the commit reply is a fixed shim identity, not the client's.
