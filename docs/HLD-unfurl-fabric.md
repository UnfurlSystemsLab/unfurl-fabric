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

1. Catalog scan reads component artifacts and produces content-pinned catalog entries. A claim is always the one read from its artifact (DCP HLD-C §3.10): Studio upload admission takes the jar bytes, never a detached claim document, pins the SHA-256 it computes from those bytes, and persists the manifest text it read so restart, snapshot, compile, and deployment resolution use that exact claim and its `catalog.component_shape_profile`. Compilation is the only step that narrows or binds a claim; Fabric never rebuilds a claim from visual metadata, and a display-only catalog entry cannot be compiled.
2. Needs loading parses the required capabilities and constraints. Every capability in the needs is mandatory: one that
   no allowed catalog entry satisfies fails planning and compilation with its reason (not in the catalog, offered only at
   other versions, without the required offer details, or only by trust-rejected entries) and the fix, and an invalid
   candidate reports each distinct conflict, including a selected component's unmet claim dependency with the component
   named (`NoMatchExplanation`; HLD-needs-driven-identity §3). Identity is one such capability: a composition has
   authentication and authorization exactly when its needs require `identity-context` (HLD-needs-driven-identity).
3. Matching creates composition candidates and validates dependency bindings. Dependencies marked `owner=host`, `owner=fabric`, `owner=customer-controlled`, or a concrete customer owner such as `owner=customer-idp` are external runtime/profile bindings, not peer catalog gates. The binding must still be preserved in the candidate audit and deployment handoff so production packaging can require the real customer system.
   Optional dependencies (a claim's `dependencies.optional`, DCP HLD-C v0.2 §3.4) never gate a candidate: a composition
   without a provider for one is valid, and the component runs without it. When a selected component does provide one, it
   is an ordinary edge: it appears in the candidate audit's dependency bindings and in `neededBy` (step 7) exactly as a
   need would. Fabric doesn't add a provider because a claim lists it as optional, just as it doesn't for needs; the
   operator's needs decide. For example, Flow lists Foundry's `agent.run` and `tool.call` as optional, because Flow
   depends on Foundry and never the reverse: needs with `workflow.execute` alone select Flow without Foundry (a
   `flow-only` assembly), needs that also ask for `agent.run` bind Flow to Foundry and record Flow in the `agent.run`
   child contract's `neededBy`, and needs with `agent.run` alone select Foundry without Flow (a `foundry-only`
   assembly), since Foundry declares no dependency on Flow. An unprovided optional dependency is reported in the plan
   output as informational, never as a conflict.
   Optional dependencies are not substrate requirements: a `substrate=true` qualifier is honoured only under `needs`.
   Refusal alignment: a concern one selected component refuses becomes required (some selected component must offer a capability of that exact name) when the needs list it under `refusalExpectations`, or when another selected component's peer dependency (a need, not an optional dependency) names it as its capability, that is the part of the dependency URI before `@` and `?`. Matching is exact, never a substring, so a dependency such as `oidc.identity-provider@v1` does not make Foundry's refused `identity` concern required. Externally owned dependencies never make a concern required, because the customer system they name is bound outside the catalog.
4. Selection either chooses the single valid candidate, applies explicit `--select`, or requires `--auto-select-best` when candidates are ambiguous.
5. Deployment resolution selects supported runtime shapes and records rejected shapes. Product runtimes such as Flow and Foundry must resolve from their catalog `component_shape_profile`; substrate libraries and adapters may resolve in-process when they are bundled inside those product runtimes.
   [proposed] A component whose catalog block declares `hosted_activation` is admitted at runtime by a selected service
   through DCP's in-process component host. Resolution then also places it on a host (policy-named, or inferred only
   when unambiguous), compile writes that host as the consumer party of its child contracts and freezes its companion
   artifacts, and emit delivers an admitted-component overlay. Design: `HLD-in-process-component-hosting.md`.
6. Substrate profile derivation emits the required runtime profile without secret values.
7. Compilation writes a light unsigned root DCP contract, the substrate profile, and support/diagnostic compiler
   envelopes. Each child contract's metadata records `neededBy`: the sorted claim URIs of the other selected components
   whose claims depend on its capability, through a need or an optional dependency, so later steps (for example identity audience coverage,
   HLD-needs-driven-identity §5) read that fact from the signed contract. The full `CompiledContract` remains useful for Fabric explain/replay tooling, but it is not the default
   deployment handoff.
8. Signing freezes the root DCP contract as the primary signed handoff artifact. When downstream Fabric tools still need
   selection audit, binding plan, or child-contract closure, Studio also emits a signed compiled-envelope support
   artifact. [proposed] Studio and the CLI (`sign --dcp-runtime-bundle`) share one writer for the DCP runtime bundle of
   individually frozen child contracts (`HLD-in-process-component-hosting.md` §7).
9. Verification checks signature, catalog drift, and trust keys.

## Studio Flow

Fabric Studio uses `StudioServer`, `StudioTenantHandler`, `StudioAuthoringHandler`, and `ResolveDeploymentHandler`.

The Studio API is intentionally lightweight and currently built on `com.sun.net.httpserver.HttpServer`, not Spring MVC. Route contracts are represented by Java records in `com.unfurl.fabric.studio` and mirrored in the TypeScript client under `unfurl-ui/packages/fabric-validation-client`.

Studio state is tenant and assembly scoped. Draft sessions support intent history, collaborator heartbeat, compile, layout persistence, dynamic DCP projection, and server-sent event updates. Event transport can be in-memory, Redis, or Kafka depending on `StudioMicroserviceConfig`.

Studio also owns a tenant-isolated file registry read model for operator-visible inputs and generated artifacts. The
registry is shaped as two database tables even when the local development server persists it through `StudioStateStore`:
`studio_files` stores immutable file versions (`file_id`, `tenant_id`, `logical_file_id`, `version`, `file_name`,
`file_title`, `file_path`, `file_type`, `media_type`, `sha256`, `created_at`) and `studio_session_files` links those
files to a `session_id` or correlation id for history. Catalogs are first-class file versions, so a new draft can bind
to a selected tenant catalog file instead of relying on whatever catalog response the browser last cached. Sessions use
the selected catalog file id and resolved catalog hash as provenance, and historical sessions are shown by tenant with a
display name derived from the draft/catalog title unless the caller supplies a title.

Dynamic DCP has two read-model modes. Catalog mode is available when no draft session is supplied and is used only for catalog browsing or preview guidance. Draft-session mode is authoritative for Studio composition inspection: it replays the accepted session intent log to derive the same catalog-entry inventory that compile uses, grounds those entries in the tenant catalog, and projects only that draft inventory.

## Architectural Invariants

Reusable composite sources, immutable signed releases, and environment-specific binding revisions are separate storage
domains. Fabric exposes a repository port with a local filesystem adapter; publication retains existing DCP contracts
and compiler output rather than inventing executable release contracts. See
`HLD-reusable-composite-dcp-contracts.md` and `LLD-composite-artifact-storage.md` for identity mapping, storage paths,
signing, validation, and CLI lifecycle. The source publisher and deployment owner may use different signing keys.

- Contracts are deterministic for the same inputs.
- Contract validity comes from catalog claims, needs, trust policy, substrate support, and deployment policy.
- Signed root DCP contracts are the handoff artifact; support and diagnostic artifacts must not change validity.
- Catalog entries and claims are content pinned.
- Studio file rows are tenant isolated and immutable by version; session-file links must include the same tenant id as
  the referenced file.
- Ambiguous selection requires an explicit operator or CLI choice.
- Studio endpoints are development-oriented unless surrounded by an authenticated deployment boundary.

