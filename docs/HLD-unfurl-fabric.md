# HLD: Unfurl Fabric

`unfurl-fabric` is the design-time compiler and Studio API host for Unfurl. Its job is to turn component claims, operator needs, trust rules, and deployment constraints into a deterministic composition contract that can be signed, verified, explained, and emitted for deployment.

## Responsibilities

- Scan component artifacts into a catalog of DCP-backed claims.
- Match declared needs against catalog offers and dependencies.
- Score and select valid composition candidates.
- Resolve deployment shapes from component claims, substrate support, trust policy, and deployment policy.
- Derive the substrate profile required by the chosen contract.
- Compile, sign, verify, diff, explain, and emit contracts.
- Serve Fabric Studio HTTP endpoints for catalog browsing, authoring, shared draft sessions, layout state, dynamic DCP projection, and deployment resolution.

## Non-Responsibilities

- Running production component workloads.
- Owning the browser UI; Studio UI lives in `unfurl-ui`.
- Hosting the AI model runtime for authoring. Fabric delegates authoring to Foundry over DCP when `UNFURL_FOUNDRY_DCP_ENDPOINT` is configured.
- Treating operator layout as validity. Layout state is presentation state only.

## Main Subsystems

| Package | Purpose |
|---|---|
| `com.unfurl.fabric.catalog` | Scans component artifacts and parses catalog manifests. |
| `com.unfurl.fabric.needs` | Reads operator needs and capability requirements. |
| `com.unfurl.fabric.matcher` | Builds and scores composition candidates. |
| `com.unfurl.fabric.compile` | Produces compiled contracts and decision audit metadata. |
| `com.unfurl.fabric.substrate` | Derives substrate requirements and excludes secret-like material. |
| `com.unfurl.fabric.trust` | Applies trust policy and rejection classification. |
| `com.unfurl.fabric.verify` | Checks signed contracts and catalog drift. |
| `com.unfurl.fabric.cli` | Provides command-line operations for local and CI workflows. |
| `com.unfurl.fabric.studio` | Hosts Studio state, catalog visuals, session collaboration, and HTTP API records. |

## Core Flow

1. Catalog scan reads component artifacts and produces content-pinned catalog entries. Studio upload admission follows the same manifest model: when an uploaded artifact contains `catalog.component_shape_profile`, the visual read model persists that profile metadata so restart, snapshot, compile, and deployment resolution do not degrade product runtimes to visual fallback shapes.
2. Needs loading parses the required capabilities and constraints.
3. Matching creates composition candidates and validates dependency bindings. Dependencies marked `owner=host`, `owner=fabric`, `owner=customer-controlled`, or a concrete customer owner such as `owner=customer-idp` are external runtime/profile bindings, not peer catalog gates. The binding must still be preserved in the candidate audit and deployment handoff so production packaging can require the real customer system.
4. Selection either chooses the single valid candidate, applies explicit `--select`, or requires `--auto-select-best` when candidates are ambiguous.
5. Deployment resolution selects supported runtime shapes and records rejected shapes. Product runtimes such as Flow and Foundry must resolve from their catalog `component_shape_profile`; substrate libraries and adapters may resolve in-process when they are bundled inside those product runtimes.
6. Substrate profile derivation emits the required runtime profile without secret values.
7. Compilation writes a light unsigned root DCP contract, the substrate profile, and support/diagnostic compiler
   envelopes. The full `CompiledContract` remains useful for Fabric explain/replay tooling, but it is not the default
   deployment handoff.
8. Signing freezes the root DCP contract as the primary signed handoff artifact. When downstream Fabric tools still need
   selection audit, binding plan, or child-contract closure, Studio also emits a signed compiled-envelope support
   artifact.
9. Verification checks signature, catalog drift, and trust keys.

## Studio Flow

Fabric Studio uses `StudioServer`, `StudioTenantHandler`, `StudioAuthoringHandler`, and `ResolveDeploymentHandler`.

The Studio API is intentionally lightweight and currently built on `com.sun.net.httpserver.HttpServer`, not Spring MVC. Route contracts are represented by Java records in `com.unfurl.fabric.studio` and mirrored in the TypeScript client under `unfurl-ui/packages/fabric-validation-client`.

Studio state is tenant and assembly scoped. Draft sessions support intent history, collaborator heartbeat, compile, layout persistence, dynamic DCP projection, and server-sent event updates. Event transport can be in-memory, Redis, or Kafka depending on `StudioMicroserviceConfig`.

Dynamic DCP has two read-model modes. Catalog mode is available when no draft session is supplied and is used only for catalog browsing or preview guidance. Draft-session mode is authoritative for Studio composition inspection: it replays the accepted session intent log to derive the same catalog-entry inventory that compile uses, grounds those entries in the tenant catalog, and projects only that draft inventory.

## Architectural Invariants

- Contracts are deterministic for the same inputs.
- Contract validity comes from catalog claims, needs, trust policy, substrate support, and deployment policy.
- Signed root DCP contracts are the handoff artifact; support and diagnostic artifacts must not change validity.
- Catalog entries and claims are content pinned.
- Ambiguous selection requires an explicit operator or CLI choice.
- Studio endpoints are development-oriented unless surrounded by an authenticated deployment boundary.

