# unfurl-fabric

Use `unfurl-fabric` when you need to turn component claims and operator needs
into a governed, signed, deployable Unfurl composition. Fabric is the
design-time control plane: it admits catalogs, helps authors build drafts,
matches needs to components, resolves deployment shapes, exports contract
artifacts, and verifies drift before a runtime such as Flow or Foundry executes
anything.

`unfurl-fabric` is the design-time compiler for Unfurl. It scans component catalogs, matches operator needs, resolves deployment shapes, derives the substrate profile, compiles an unsigned contract, signs it, and verifies signed output.

It also hosts the **Fabric Studio API** (`StudioServer`), including the conversational
`POST /studio/authoring/converse` endpoint. Authoring AI is **not** run here: Fabric delegates
to `unfurl-foundry` over DCP `agent.run` (via `DcpAuthoringClient`, configured from
`UNFURL_FOUNDRY_DCP_ENDPOINT`), falling back to a deterministic bridge when no endpoint is set.
See `unfurl-ui/docs/REPO-ai-contract-authoring-build-spec.md`.

## Build

```bash
mvn test
```

## Run Fabric on its own

The launch scripts run Fabric without the UI, Flow or Foundry. They build
`target/unfurl-fabric-studio-server.jar` first if it is missing (or with `--build`), check for
Java 21 or later, and run in the foreground, so Ctrl-C stops them.

| Shell | Script |
|---|---|
| PowerShell | `.\launch.ps1` |
| cmd.exe | `launch.bat` (runs `launch.ps1`) |
| bash (Linux, macOS, Git Bash) | `./launch.sh` |

Start the Fabric Studio server, by default on `http://127.0.0.1:7878`:

```powershell
.\launch.ps1
.\launch.ps1 --port 7979 --state-path "$env:TEMP\fabric\studio-state.json"
Invoke-RestMethod http://127.0.0.1:7878/health
```

Run a Fabric CLI command instead of the server:

```powershell
.\launch.ps1 cli plan --catalog .\catalog --needs .\needs.yaml
```

Server options are passed through unchanged: `--bind`, `--port`, `--state-path`, `--asset-root`,
`--event-bus`, `--redis-url`, `--kafka-bootstrap-servers`, `--kafka-topic`. The scripts refuse to
start when the port is already in use.

| Variable | Effect |
|---|---|
| `UNFURL_STUDIO_BIND`, `UNFURL_STUDIO_PORT` | Defaults for `--bind` and `--port`, the same names the Dockerfile uses |
| `UNFURL_STUDIO_STATE_PATH`, `UNFURL_STUDIO_ASSET_ROOT` | Read by the server itself |
| `UNFURL_FOUNDRY_DCP_ENDPOINT` | Foundry endpoint for authoring; without it Studio uses its deterministic bridge |
| `JAVA_OPTS` | Extra JVM options, split on spaces |
| `FABRIC_STUDIO_JAR` | Run this jar instead of building one |

Binding to anything other than loopback exposes the Studio API with no authentication; keep it on
`127.0.0.1` unless an authenticated boundary is in front of it.

### Stop it

Press Ctrl-C in the window running the launch script, or, from anywhere, run the matching shutdown
script: `.\shutdown.ps1`, `shutdown.bat` or `./shutdown.sh`.

```powershell
.\shutdown.ps1                 # the Studio server on UNFURL_STUDIO_PORT, else port 7878
.\shutdown.ps1 --port 7979
.\shutdown.ps1 --all           # every Fabric Studio server on this machine
```

It stops a process only when that process is a Fabric Studio server, so an unrelated program on the
port is left alone, and it reports when nothing is running. On Linux and macOS the server gets
`SIGTERM` and a clean shutdown, then `SIGKILL` if it is still running after `--timeout` seconds
(default 10). On Windows it is stopped forcefully, which is safe because Studio writes its state file
atomically on every change (`docs/RELIABILITY.md`). From `cmd.exe` in another folder, call
`shutdown.bat` by its path: a bare `shutdown` there is Windows' own command.

## Docs

- `docs/examples/foundry-assemblies/README.md` - step-by-step guide to the catalog, assemblies, compile, and export for Foundry and its substrate combinations.
- `docs/HLD-unfurl-fabric.md` - module architecture and invariants.
- `docs/LLD-fabric-studio-api.md` - Studio HTTP API routes, records, and runtime configuration.
- `docs/REPO-unfurl-fabric-build-spec.md` - build, test, packaging, and CLI notes.
- `docs/REPO-recursive-dcp-projection-build-spec.md` - Fabric adapter for DCP-owned recursive projections.
- `docs/SECURITY.md` - trust boundaries, Studio exposure, and contract integrity notes.
- `docs/RELIABILITY.md` - determinism, Studio state, eventing, and recovery notes.

## Deployment Shape Options

Fabric accepts an optional deployment policy on compile, dry-run, and deployment-resolution commands:

```bash
fabric compile \
  --catalog ./catalog \
  --needs ./needs.yaml \
  --deployment-policy ./deployment-policy.yaml \
  --out ./contract.yaml \
  --substrate-profile-out ./contract.substrate-profile.yaml
```

If `--deployment-policy` is omitted, Fabric uses the MVP default: prefer `IN_PROCESS_LIBRARY` and reject nothing. That keeps local development simple; production operators should supply an explicit policy.

Supported shapes:

| Shape | Meaning |
|---|---|
| `IN_PROCESS_LIBRARY` | Component runs inside the host JVM. |
| `MODULAR_MONOLITH_MODULE` | Component stays in one process but has explicit module boundaries. |
| `STANDALONE_JAVA_APP` | Component runs in a separate JVM. |
| `SPRING_BOOT_SERVICE` | Component runs as a Spring Boot HTTP service. |
| `REMOTE_MICROSERVICE` | Component binds to a remote service endpoint. |
| `CONTAINERIZED_SERVICE` | Component runs as a platform-managed container. |
| `MANAGED_EXTERNAL_ADAPTER` | Component delegates to a customer-managed external system. |

Example `deployment-policy.yaml`:

```yaml
preferredShapes:
  - CONTAINERIZED_SERVICE
  - SPRING_BOOT_SERVICE
  - IN_PROCESS_LIBRARY
disallowedShapes:
  - REMOTE_MICROSERVICE
requireIsolationForCapabilityPatterns:
  - ai.*
runtime:
  javaVersion: "21"
  springBoot: true
  kubernetes: true
  serviceMesh: false
  maxServices: 5
```

## Deployment Commands

Preview without writing files:

```bash
fabric dry-run --catalog ./catalog --needs ./needs.yaml --deployment-policy ./deployment-policy.yaml
```

Resolve only the deployment shapes for the selected candidate:

```bash
fabric resolve-deployment --catalog ./catalog --needs ./needs.yaml --deployment-policy ./deployment-policy.yaml
```

Explain the deployment plan embedded in a signed contract:

```bash
fabric explain-deployment --contract ./contract.signed.yaml
```

Compare deployment shape changes between two signed contracts:

```bash
fabric diff --left ./before.signed.yaml --right ./after.signed.yaml
```

The resolved `BindingPlan` is embedded inline in `CompiledContract` and covered by the signature. Fabric never asks the UI or operator layout state to define validity; deployment shape is resolved from catalog claims, needs, trust policy, substrate support, and deployment policy.
