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

