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

- **P1 (bounded first cut):** CQ registry + EXECUTECQ (no IR) + push CQ CREATE/UPDATE/DESTROY for
  mutations matching the CQ's OQL predicate, validated against a real client's `CqListener` (needs the
  `geode-cq` test dependency). STOPCQ/CLOSECQ ack.
- **P2:** EXECUTECQ_WITH_IR initial result set, "stops-matching" → CQ DESTROY (prior-match tracking),
  multiple CQs per event, CQ stats.
- **P3:** durable CQs, monitoring, and the cross-replica story.

`tools/CqCapture` reproduces the capture (run with `geode-cq` on the classpath).
