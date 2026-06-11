# Server-side functions — graceful rejection

Status: **graceful protocol support** (validated by `ProtoGemCouchFunctionIntegrationTest`).

Geode lets a client ship a `Function` to the server for in-place execution
(`FunctionService.onServer(..)/onRegion(..)/onMembers(..).execute(id_or_fn)`). The shim is a
stateless protocol translator backed by Couchbase — it has **none of the user's `Function` classes**
and no way to run arbitrary user code, so genuinely *executing* a function is out of scope (it would
require loading and sandboxing user code, which a shim deliberately does not do).

What it does instead: reject function execution **the same way a real server rejects an unregistered
function id**, so a real client fails fast and cleanly (a `ServerOperationException` /
`FunctionException`) rather than hanging on a missing reply or seeing the connection drop.

## What the client actually sends

Captured against a real Geode 1.15 server with `tools/FunctionCapture`
(`FunctionService.onServer(cache).execute("shimUnknownFn").getResult()` for an id the server does not
know):

1. The client sends **`GET_FUNCTION_ATTRIBUTES` (opcode 91)** first — a probe whose single part is the
   function id (raw string) — to learn the function's `hasResult`/`isHA`/`optimizeForWrite` flags
   before executing.
2. For an unknown id the server replies with **`REQUESTDATAERROR` (msgType 3)**, one raw-string part:
   `The function is not registered for function id shimUnknownFn`.
3. The client raises `ServerOperationException` from that error and **never sends `EXECUTE_FUNCTION`**.

So handling opcode 91 alone is enough to make `execute()` fail fast. The error response is a plain
message (no chunking, no serialized object):

```
msgType=3 (REQUESTDATAERROR)  numParts=1  txId=-1  flags=0
  part[0]  isObj=0  "The function is not registered for function id <id>"
```

## Shim implementation

- `MessageTypes`: `GET_FUNCTION_ATTRIBUTES=91`, `EXECUTE_FUNCTION=62`, `EXECUTE_REGION_FUNCTION=59`,
  `EXECUTE_REGION_FUNCTION_SINGLE_HOP=79`, `REQUESTDATAERROR=3`.
- `GemResponseWriter.buildFunctionErrorReply(txId, message)` — a `REQUESTDATAERROR` message with the
  message string as a single raw part.
- `FunctionHandler` — registered for all four function opcodes. It extracts a best-effort function id
  (the first raw, printable-ASCII part — for `GET_FUNCTION_ATTRIBUTES` that is part[0]) and replies
  with `The function is not registered for function id <id>`. The execute opcodes are handled too, for
  clients that skip the attributes probe.

Handling the execute opcodes (not just the probe) also keeps them off the unknown-opcode path, which
would otherwise log the `unknown_opcode` marker the CRUD integration test asserts against.

## Validation

`ProtoGemCouchFunctionIntegrationTest` (real Geode 1.15 client against the shim):

- `onServerFunctionExecutionFailsCleanly` — `onServer(cache).execute(id).getResult()` raises promptly
  and the failure names the rejected function id.
- `onRegionFunctionExecutionFailsCleanly` — `onRegion(region).execute(id).getResult()` raises a clean
  `FunctionException` rather than hanging.

Both complete in ~1s, i.e. the rejection is immediate (the contract that matters is *no hang and no
abrupt connection close*).

## Reproduce the capture

```
GEODE_PORT=40404 PROXY_PORT=40499 FN_ID=shimUnknownFn ON_REGION=0 \
  java -cp target/protogemcouch.jar com.protogemcouch.tools.FunctionCapture
# ON_REGION=1 to capture onRegion instead of onServer
```
