# Continuous Queries (CQ) — scoping

Status: **scoping** (capture done; no shim implementation yet). CQ is a *query-predicated*
subscription: a client registers an OQL query + a `CqListener`, and the server pushes a CQ event
whenever a mutation makes an entry match (or stop matching) the query. It builds directly on the two
subsystems already in place — the **subscription feed** (server→client push) and the **OQL engine**
(`OqlQuery` matching). Note that plain `CacheListener` event delivery already works via
register-interest (see `docs/SUBSCRIPTIONS.md`); CQ adds the query filter + a `CqListener`.

Findings below are from a real capture against Geode 1.15 (`tools/CqCapture`, mutations from
`tools/PutOnce`), where a `CqListener.onEvent op=CREATE key=cqk value=hello` fired.

## Client dependency (new)

The client-side CQ engine lives in the separate **`geode-cq`** module, not `geode-core`. The CQ
interfaces (`CqQuery`, `CqListener`, `CqAttributesFactory`, `QueryService.newCq`) are in `geode-core`
and compile fine, but at runtime `QueryService.getCqService()` returns `MissingCqService` ("CqService
is not available") unless `geode-cq` is on the classpath. **The shim itself does not need `geode-cq`**
(it speaks the wire protocol and matches with its own `OqlQuery`); only the CQ test client / capture
tool does. So a CQ integration test needs `geode-cq` as a **test-scoped** dependency (now cached in
`~/.m2`; CI fetches it online).

## Wire protocol

- Opcodes (`MessageType`): `EXECUTECQ=42`, `EXECUTECQ_WITH_IR=43` (with initial results), `STOPCQ=44`,
  `CLOSECQ=45`, `CLOSECLIENTCQS=46`, `CQDATAERROR=47`, `GETCQSTATS=48`, `MONITORCQ=49`,
  `CQ_EXCEPTION=50`, `GETDURABLECQS=105`. CQ requests travel on the **mode-107 control connection**.
- **EXECUTECQ (42)** request parts: `[0]` CQ name (e.g. `myCq`), `[1]` OQL query string, then
  state/durable/dataPolicy flag parts. The reply is a chunked ack (and for `EXECUTECQ_WITH_IR` the
  initial matching result set, like a query response).
- **CQ event** (pushed down the mode-101 feed): a `LOCAL_CREATE/UPDATE/DESTROY` notification with
  **extra parts** — captured as a 12-part `LOCAL_CREATE` (vs the 9-part plain event): the standard
  `[region, key, bool, value, callback, versionTag, bool, bool]`, then a **CQ section**
  `[numCqs, cqName, cqOp]` per matched CQ, then the `EventID`. So one notification can carry several
  CQ matches; the client routes each to the named CQ's `CqListener` with the op.

## What a first cut requires

1. **CQ registry** — per client, the registered CQs: `cqName -> (region, compiled OqlQuery)`. Reuse
   `OqlQuery.parse` for the predicate; reuse the client-identity + feed plumbing from subscriptions.
2. **EXECUTECQ / EXECUTECQ_WITH_IR handlers** — parse name + query, compile, register; reply with the
   chunked ack (and, for _WITH_IR, the initial matching set via the existing query/GII machinery).
3. **CQ event evaluation** — on each PUT/REMOVE, for every registered CQ on that region, evaluate
   `OqlQuery.matches(value)`; on a match, push a CQ event (the `LOCAL_*` builder extended with the
   `[numCqs, cqName, cqOp]` section) down that client's feed. (Self-suppression + per-client routing
   already exist.)
4. **CQ event framing** — extend `GemResponseWriter` event builders to include the CQ section, byte
   matched to the capture; map mutation type → CQ op.
5. **STOPCQ / CLOSECQ** — deregister + ack.

## Hard parts / scope notes

- **Event-builder generalization**: the CQ section adds parts to the existing LOCAL_* messages — the
  builders must support both the plain (register-interest) and CQ-augmented forms.
- **Old-value / op semantics**: CQ create vs update vs destroy mirrors the entry op; "no longer
  matches" (a former match that a new value fails) is a CQ DESTROY — tracking that needs the prior
  value/match state (a refinement; first cut can do match-on-current-value create/update).
- **Inherits the subscription limitations**: single shim instance, etc. (`docs/SUBSCRIPTIONS.md`).
- `executeWithInitialResults`, durable CQs, CQ stats/monitoring are later phases.

## Recommended phased plan

- **P1 (bounded first cut) — DONE:** CQ registry (`SubscriptionRegistry`, per client
  `cqName -> region + compiled OqlQuery`) + `ExecuteCqHandler` (EXECUTECQ/_WITH_IR: parse name +
  query, compile with `OqlQuery`, register, reply the chunked "cq created successfully." ack) +
  `PutHandler` calls `publishCqEvent` which, for each feed whose client has a CQ on the region that
  `OqlQuery.matches` the new value, pushes a CQ event (`GemResponseWriter.buildCqEvent`: a LOCAL_*
  with the `[numCqElems, cqName, cqOp]` section) — self-suppressed. `CloseCqHandler` (STOPCQ/CLOSECQ)
  deregisters + acks. Gate `ProtoGemCouchCqIntegrationTest.cqListenerFiresOnlyForPredicateMatchingMutation`
  (a real client's CqListener fires for a `WHERE r.amount > 10` match and not for a non-match, from a
  separate client). **CQ DESTROY events** are also delivered: on REMOVE, `RemoveHandler` reads the
  prior value (only when a CQ exists on the region) and `publishCqDestroy` pushes a CQ DESTROY
  (`buildCqDestroy`: LOCAL_DESTROY + CQ section) to clients whose CQ predicate matched that prior
  value — so create/update/destroy CQ events are complete (gate
  `cqListenerFiresDestroyWhenMatchingEntryIsRemoved`). **Remaining P2:** EXECUTECQ_WITH_IR initial
  result set, "stops-matching" on update → CQ DESTROY (prior-match tracking), multiple CQs per event,
  PDX-field CQ predicates (uses the map resolver today).

## CQ P2 progress

- **stops-matching — DONE.** `publishCqEvent` uses the entry's prior value: new-matches+prior-not →
  CQ CREATE, new+prior match → CQ UPDATE, new-doesn't-match+prior-matched → CQ DESTROY (the entry
  leaves the result set). `PutHandler` reads the prior value when a CQ exists on the region. Gate
  `cqListenerFiresDestroyWhenUpdatedValueStopsMatching`.
- **PDX-field CQ predicates — DONE.** CQ matching now uses the same PDX-aware field resolver as the
  QUERY path (`PdxAwareFieldResolver`, installed via `SubscriptionRegistry.setCqFieldResolver`), so a
  predicate like `WHERE r.amount > 10` matches PDX objects, not just maps. Because the listening client
  is a *different* client than the writer, it fetches the unknown `PdxType` from the shim via the new
  **reverse lookup** GET_PDX_TYPE_BY_ID (opcode 92, `GetPdxTypeByIdHandler`) before it can deliver the
  pushed PDX event value — without that the event never reaches the `CqListener`. Gate
  `cqListenerFiresForPredicateMatchingPdxObject`.
- **executeWithInitialResults — DONE.** Re-captured against Geode 1.15.1 (`tools/CqCapture WITH_IR=1`):
  the earlier "three messages" reading was wrong. The client op `CreateCQWithIROpImpl` **extends
  `QueryOpImpl`**, so it parses the reply *exactly like a query response* — a single chunked `RESPONSE`
  (no leading/trailing REPLY): part[0] = a CollectionType (`java.util.Collection` wrapping a `Struct`
  with fields `key`,`value`, each `java.lang.Object`); part[1] = an ObjectPartList of the matching
  entries, each a nested `Struct` = `ObjectPartList[key, value]` (header `01 19 00000000` + count, each
  field `00` + `encodeStoredValueForGetAll`). The empty set uses the query empty-result form
  (`34 00 …`). `GemResponseWriter.buildExecuteCqWithIrReply` builds it (the {key,value} CollectionType
  is a fixed captured constant), reusing the query ObjectPartList/value encoders; `ExecuteCqHandler`
  snapshots the region's current matching entries (via the PDX-aware resolver) for opcode 43. Byte-for-
  byte golden test `CqWithIrResponseShapeTest` (empty + 2 entries) + integration gate
  `executeWithInitialResultsReturnsCurrentMatchingEntries`.
- **P2:** EXECUTECQ_WITH_IR initial result set (done), "stops-matching" → CQ DESTROY (prior-match
  tracking, done), PDX-field CQ predicates (done), multiple CQs per event, CQ stats.
- **P3:** durable CQs, monitoring, and the cross-replica story.

`tools/CqCapture` reproduces the capture (run with `geode-cq` on the classpath).
