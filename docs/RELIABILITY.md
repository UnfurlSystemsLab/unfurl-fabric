# Reliability Notes: Unfurl Fabric

Fabric reliability centers on deterministic compilation, explicit ambiguity handling, durable Studio state where needed, and clear verification before deployment.

## Determinism

The same catalog, needs, trust policy, deployment policy, and selected candidate should produce stable contract decisions and substrate profile output.

Important invariants:

- Candidate ids are content-pinned and stable.
- Ambiguous candidate sets do not compile silently.
- Deployment shape resolution is captured in the compiled contract.
- Substrate profile output excludes volatile or secret values.

## Failure Modes

| Area | Expected Behavior |
|---|---|
| Catalog scan | Invalid or unreadable artifacts are skipped or reported with diagnostics. |
| Matching | Missing capabilities produce unmet requirement diagnostics. |
| Ambiguous planning | CLI requires `--select` or `--auto-select-best`. |
| Deployment resolution | Unsupported shapes are rejected with reasons. |
| Studio JSON parsing | Malformed request bodies return `400`. |
| Unknown Studio route | Returns `404`. |
| Unsupported method | Health and deployment handlers return method errors; tenant handler routes by method and route. |
| Event bus readiness | `/ready` returns `503` when event bus health is not `UP`. |

## Studio State

Studio state is stored through `StudioStateStore`. The event bus can run in:

```text
in-memory
redis
kafka
```

Use in-memory only for local/dev or single-process tests. Use Redis or Kafka when session events must survive beyond one server process or support multiple Studio server instances.

`StudioStateStore` writes the state file on every change, not at shutdown, and writes it atomically: the new state
goes to a temporary file beside it, which then replaces the state file in one move. A process stopped at any moment
therefore leaves either the previous or the new state, never a truncated file. That is what makes a forced stop safe.

## Stopping Studio

`shutdown.ps1`, `shutdown.sh` and `shutdown.bat` stop a Studio server started by the launch scripts. They find the
process listening on the port (`--port`, else `UNFURL_STUDIO_PORT`, else `7878`), or every Studio server with `--all`,
and stop it only when its command line runs the Studio server jar, so an unrelated process on the port is never
stopped. Nothing running is not an error.

- Linux and macOS: `SIGTERM`, so the shutdown hook closes the HTTP server, executor and event bus; `SIGKILL` only if it
  is still running after the timeout (`--timeout`, default 10 seconds).
- Windows: a console Java process cannot be asked to exit, so it is stopped forcefully. The atomic state write above
  means no state is lost; event-bus connections are dropped and Redis or Kafka clients reconnect on the next start.

## Session Events

Session event streams are server-sent events. Live streams run for a bounded window and send keep-alives when idle. Clients should reconnect and treat the stream as an update channel, not the source of truth. The session resource remains the source of truth.

## Recovery

Recommended recovery pattern:

1. Reload catalog and assembly state from the Studio API.
2. Reconnect to session events.
3. Re-run compile or deployment resolution from persisted draft state.
4. Verify signed contracts before deployment.

## Testing Focus

Keep tests around:

- deterministic compile and substrate profile output;
- no-secret substrate profile behavior;
- ambiguous candidate gates;
- deployment shape rejection and fallback behavior;
- Studio session event behavior;
- record round trips for Studio API request/response types;
- compatibility between Java Studio records and the TypeScript validation client.

