# LLD: Fabric Studio API

Fabric Studio API is served by `com.unfurl.fabric.studio.StudioServer`. It uses the JDK `HttpServer` and JSON records, not generated OpenAPI.

The Flowfoundry export runbook may generate OpenAPI and Swagger UI artifacts for deployed operator/client endpoints
from the signed DCP contract, runtime bindings, service route descriptors, and tool schemas. That documentation phase
does not make Studio's server implementation OpenAPI-generated; Studio remains the source-owned HTTP adapter and the
generated OpenAPI is an export artifact.

Default bind address is `127.0.0.1` and default port is `7878`.

## Configuration

| Setting | Source | Default |
|---|---|---|
| Bind address | `--bind` | `127.0.0.1` |
| Port | `--port` | `7878` |
| State path | `--state-path` or `UNFURL_STUDIO_STATE_PATH` / `-Dunfurl.studio.state.path` | module default |
| Asset root | `--asset-root` or `UNFURL_STUDIO_ASSET_ROOT` / `-Dunfurl.studio.asset.root` | none: the catalog holds only admitted jars |
| Event bus | `--event-bus` or `UNFURL_STUDIO_EVENT_BUS` | `in-memory` |
| Redis URL | `--redis-url` or `UNFURL_STUDIO_REDIS_URL` | `redis://localhost:6379` |
| Kafka bootstrap | `--kafka-bootstrap-servers` or `UNFURL_STUDIO_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| Kafka topic | `--kafka-topic` or `UNFURL_STUDIO_KAFKA_TOPIC` | `unfurl.fabric.studio.sessions` |
| Tenant header enforcement | `UNFURL_STUDIO_REQUIRE_TENANT_HEADER` / `-Dunfurl.studio.requireTenantHeader` | disabled |
| Foundry authoring endpoint | `UNFURL_FOUNDRY_DCP_ENDPOINT` | deterministic fallback |

## Headers

Tenant-scoped routes can enforce these headers when tenant header enforcement is enabled:

| Header | Purpose |
|---|---|
| `X-Unfurl-Tenant` | Tenant id expected to match the route tenant. |
| `X-Unfurl-User` | Optional user id for access decisions and collaboration. |
| `X-Unfurl-Tenant-Memberships` | Optional comma-separated tenant membership list. |

Dev CORS is allowed only for loopback HTTP origins matching `localhost` or `127.0.0.1`.

## Health Endpoints

| Method | Path | Response |
|---|---|---|
| `GET` | `/health` | Service status and event bus health. |
| `GET` | `/live` | Liveness status. |
| `GET` | `/ready` | Readiness status, returning `503` when the event bus is not ready. |

## Tenant Catalog and Assets

| Method | Path | Query | Request | Response |
|---|---|---|---|---|
| `GET` | `/studio/tenants/{tenantId}/catalog` | | | `StudioCatalogVisualsResponse` |
| `GET` | `/studio/tenants/{tenantId}/files` | `fileType`, `sessionId`, `correlationId` optional | | `List<StudioFileRecord>` |
| `GET` | `/studio/tenants/{tenantId}/files/{fileId}/content` | `sha256` optional | | File content when Studio owns the bytes |
| `GET` | `/studio/tenants/{tenantId}/catalog/snapshot` | | | `StudioCatalogSnapshot` |
| `POST` | `/studio/tenants/{tenantId}/catalog/snapshot` | | `StudioCatalogSnapshot` | `StudioCatalogVisualsResponse` |
| `POST` | `/studio/tenants/{tenantId}/catalog/admissions` | | `StudioCatalogAdmissionRequest` | `StudioCatalogAdmissionResponse` |
| `DELETE` | `/studio/tenants/{tenantId}/catalog/{catalogEntryId}` | | | `StudioCatalogRemovalResponse` |
| `GET` | `/studio/tenants/{tenantId}/catalog/admissions/{admissionId}/claims.zip` | `sha256` required | | ZIP claim bundle |
| `GET` | `/studio/tenants/{tenantId}/diagnostic-artifacts/{artifactId}/content` | `sha256` required | | Hash-pinned diagnostic artifact |
| `GET` | `/studio/tenants/{tenantId}/assets/{assetId}` | | | `StudioVisualAsset` |
| `GET` | `/studio/tenants/{tenantId}/assets/{assetId}/content` | `sha256` optional | | Binary asset content |

`GET /catalog` may receive `catalogFileId` as an optional query parameter. Without it, Studio returns the tenant's active
catalog. With it, Studio resolves the catalog from the tenant-isolated file registry and returns the immutable catalog
version associated with that file id. A `catalogFileId` from another tenant is invalid.

**What a catalog entry is.** A tenant catalog entry is a component whose DCP claim Fabric read from its artifact, so every
entry Studio lists can be added to a draft, compiled and resolved. Fabric ships no built-in entries, fixture assets or
demo data; with nothing admitted and no asset root, the catalog is empty and says so.
- **Sources.** An entry comes from an admitted jar (below) or from a jar with `META-INF/unfurl-catalog.yaml` scanned in
  the configured asset root. Nothing else creates one.
- **Package visual manifests decorate, never create.** A `META-INF/unfurl-studio-visuals.json` manifest under the asset
  root only adds presentation to an entry that exists, matched by `catalogEntryId`:
  - its visual manifest, its asset files and their pins, and its `dynamicComposition` nesting references (for example
    `containsCatalogEntryIds`, REPO-recursive-dcp-projection-build-spec);
  - never a claim, a claim hash or an artifact hash: those are the artifact's. Nor what Fabric derives from the claim:
    the connection `ports`, `rawNeeds`, `rawOptionalNeeds` and `componentShapeProfile` stay the claim's;
  - a visual manifest entry that names no catalog entry is skipped, and the scan log names it.
- **No entry without a claim reaches the catalog.**
  - State saved before this rule may hold entries without a manifest, in the active catalog or in an earlier catalog
    version (a `CATALOG` file a draft or proposal can select by `catalogFileId`).
    - Every such entry is dropped when the state loads, and the log names each one with its catalog version.
    - So selecting an earlier version never serves one.
    - A restored version keeps its file id. Its served `catalogHash` is computed from the entries it now holds.
  - A catalog snapshot that carries one is refused, all or nothing, with `400 CATALOG_SNAPSHOT_INVALID` naming every
    such entry.

Catalog admissions accept uploaded component artifacts and update the tenant catalog after DCP claim verification. A
claim is admitted only from the artifact that carries it (DCP HLD-C §3.10):

- Each draft is a `.jar` with its bytes in `artifactBase64`. Studio decodes the archive and reads
  `META-INF/unfurl-catalog.yaml` without loading classes or executing artifact code. A draft that sends `claimYaml`, or
  a `.yaml`/`.yml` file, is refused with `CLAIM_DETACHED`: a claim document without the artifact proves nothing about
  the component that will run.
- Studio computes the artifact's SHA-256 from the decoded bytes. When the draft also sends `sha256`, it must match;
  otherwise the draft is refused with `ARTIFACT_HASH_MISMATCH`.
- The manifest must have both blocks (`claim` and `catalog` with lifecycle, artifact and binding). Missing, malformed or
  non-decodable manifests are refused with DCP-shaped diagnostics; Studio never fills in catalog metadata for a claim.
- Fabric parses the claim, runs `unfurl-dcp` claim validation, and refuses the artifact when validation returns any
  `ERROR` diagnostic. Admission responses preserve DCP diagnostics as structured records with `severity`, `code`,
  `path`, and `message` so Studio can render actionable claim errors next to the artifact. Warnings remain visible on
  otherwise verified claims.
- Request bodies are parsed with a string limit large enough for a product jar in `artifactBase64` (a decoded
  artifact of up to 96 MiB); a larger body is refused as malformed rather than truncated.

The admitted catalog entry keeps the exact manifest text read from the artifact (`catalogManifest`) next to the pinned
claim hash and artifact SHA-256, and Studio state persists it, so the claim survives restarts and catalog snapshots
unchanged. Entries scanned from jars in the asset root keep their manifest text the same way.

[P2d] Admission also keeps the admitted jar's bytes, content-addressed by that SHA-256, in the tenant's artifact store
(the store companions use, `studio-companions/<sha256(tenant id)>/<sha256>.jar`). The entry is grounded with that
stored jar, at admission and when it is rebuilt from its manifest after a restart, exactly as a scanned entry carries
its jar:
- a signed compile bundles the documentation artifacts the jar carries (`DcpRuntimeBundleWriter`, DCP HLD-C2 A.12);
  before Studio kept the bytes, a signed compile of a component that ships documentation artifacts, such as the API
  catalog, failed with `BUNDLE_ARTIFACT_UNAVAILABLE`;
- the emit-plan preview copies a hosted in-process component's jar from there, because the emitter's
  admitted-component overlay needs the pinned jar itself.

A jar admitted before Studio kept the bytes is not there: the entry is grounded without a jar as before, and a step
that needs it fails naming it (`BUNDLE_ARTIFACT_UNAVAILABLE` for a compile, `COMPONENT_ARTIFACT_UNAVAILABLE` for a
preview), so the operator admits the jar again. Studio never rebuilds a jar from its claim.

When an admission verifies one or more artifacts, Studio also emits a hash-pinned `claimBundleArtifact`. The bundle is a
ZIP containing the resolved DCP claim YAML for each verified artifact, an `admission-manifest.yaml` index, and
`diagnostics.json` for the full admission result set. Claims remain separate files inside the bundle; Fabric must not
merge multiple component claims into a synthetic mega-claim.

Catalog removal deletes one catalog entry from the tenant-scoped Studio catalog and returns the updated
`StudioCatalogVisualsResponse`. Removal is a Studio catalog curation operation, not a DCP validity shortcut. The response
includes a diagnostic artifact so support and CI can replay the exact catalog state after the removal.

**A draft that names an entry the catalog no longer has**, after a removal or because the entry was never a real one:
- **Projection.** The session projection draws the draft's other components and adds one warning per missing entry:
  `CATALOG_ENTRY_MISSING: draft component '<id>' is no longer in tenant '<tenant>''s catalog; remove it from the draft
  (REMOVE_COMPONENT) or admit its jar`. Views and overlays carry the same words as a `CATALOG_ENTRY_MISSING`
  diagnostic.
- **Removal is allowed.** `REMOVE_COMPONENT` for that entry is accepted, so the operator can clean the draft.
- **Other steps stay refused.** Deployment resolution and compile refuse the draft with `CATALOG_ENTRY_NOT_FOUND`
  naming the entry, until it is removed or replaced.

Catalog snapshots are tenant-scoped portable JSON state. Saving a catalog returns the exact DCP-backed visual entries
and catalog hash Fabric currently serves to Studio. Loading a catalog replaces only the addressed tenant catalog; the
route tenant remains the isolation boundary even when the JSON was saved from another tenant. The loaded entries remain
subject to the same downstream intent validation and catalog-grounding checks as admitted entries.

Catalog admissions, catalog removal, and catalog snapshot loads also create immutable `CATALOG` rows in the tenant file
registry. The latest row for logical file id `tenant-catalog` is the default catalog version shown by New Draft. Each row
keeps a display `fileTitle` separate from the physical `fileName` so the UI can show readable catalog/session titles
without changing stored artifact names or paths.

The file registry uses these read-model records:

```json
{
  "fileId": "file-...",
  "tenantId": "tenant-local",
  "logicalFileId": "tenant-catalog",
  "version": 3,
  "fileName": "catalog-3d7345.json",
  "fileTitle": "Tenant Catalog v3",
  "filePath": "/studio/tenants/tenant-local/files/file-.../content",
  "fileType": "CATALOG",
  "mediaType": "application/json",
  "sha256": "sha256:...",
  "createdAt": "2026-07-19T..."
}
```

Studio responses that produce or transform operator-visible state may also include `supportArtifacts` and
`diagnosticArtifacts`. Support artifacts use the export endpoint and are required companions for later packaging or
runtime hydration, but are not the primary DCP handoff. Diagnostic artifacts use the tenant-scoped diagnostic-artifact
endpoint and are immutable, hash-pinned snapshots intended for support, CI replay, and step-by-step Flowfoundry
debugging. Catalog admissions, catalog removals, catalog snapshots, needs extraction, dynamic DCP projections, saved
draft summaries, created draft sessions, and compile responses should all expose a downloadable diagnostic artifact when
the response is produced by a tenant-scoped route.

Tenant-scoped handlers must not silently drop HTTP connections for recoverable route failures. Validation errors,
runtime exceptions, and classpath/linkage mismatches at the handler boundary are converted to JSON error responses so
the Vite proxy and Studio UI receive a status code instead of a socket hang-up while the operator rebuilds or restarts
the Studio server.

## Assemblies

| Method | Path | Query | Request | Response |
|---|---|---|---|---|
| `GET` | `/studio/tenants/{tenantId}/assemblies` | | | `StudioAssemblyListResponse` |
| `POST` | `/studio/tenants/{tenantId}/assemblies` | | `StudioCreateAssemblyRequest` | `StudioAssemblySummary` |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/needs/extract` | | `StudioNeedsExtractionRequest` | `StudioNeedsExtractionResponse` |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/dynamic-dcp` | `sessionId` optional | | `StudioDynamicDcpProjection` |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/dynamic-dcp/replacements` | `componentNodeId` | | `StudioReplacementCandidatesResponse` |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/dynamic-dcp/connection-candidates` | `catalogEntryId` | | `StudioConnectionCandidatesResponse` |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/snapshot` | | | `StudioAssemblySnapshot` |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/snapshot` | | `StudioAssemblySnapshot` | `StudioAssemblySnapshot` |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/drafts/save` | | `StudioSaveDraftRequest` | `StudioSaveDraftResponse` |

**Assembly records.** Fabric ships no built-in assembly.
- **How a record comes to exist.** An assembly is recorded when it is created, saved, imported from a snapshot, or a
  draft session starts in it. Only recorded assemblies are listed.
- **An unrecorded assembly** reads as a summary with nothing chosen:
  - its target application is named after the assembly (the `StudioAssemblySummary` rule), and the projection's parent
    node takes that name;
  - its deployment target and deployment shape are empty.
- **No defaults are invented.** A created or saved assembly keeps the shape its request names, and is empty when the
  request names none. Fabric never defaults a shape, and Studio shows an empty one as "not provided by Fabric".
- **Replacement candidates need a component.** They are asked for one component: a missing `componentNodeId` is
  `400 COMPONENT_NODE_REQUIRED`, never a default component.

Needs extraction is a DCP needs-seeding strategy, not proof that a component is already in a draft assembly.
`StudioNeedsExtractionRequest` accepts the backward-compatible `fileNames` list and may also carry inline source
files. Fabric derives `requiredCapabilities` by analyzing inline workflow YAML `nodes[].uses`, by inferring
orchestration needs from recognized source names (`workflow.yaml`/flow files imply `workflow.execute`, agent manifests
imply `agent.run`), and only falls back to the historical `<target-application>.run` starter need when no DCP
capability can be derived. Draft membership remains governed by `StudioIntentRequest` session history.
Needs may constrain DCP offer-interface details with `requiredOfferDetails`; Studio assembly matching uses the same
deterministic subset semantics as the DCP resolver. For example, Flowfoundry can require Foundry's `agent.run` offer to
advertise `execution_modes: [harness]` without introducing a Fabric-specific execution flag.

Dynamic DCP endpoints are read-model endpoints for visual composition and replacement guidance. They do not replace compile-time validation. When `sessionId` is supplied, the projection is draft-session scoped: Fabric loads the addressed `StudioDraftSession`, replays its accepted `ADD_COMPONENT`, `REMOVE_COMPONENT`, and `REPLACE_COMPONENT` intents, grounds every selected catalog entry in the tenant catalog, and projects only that draft inventory. This session mode must match compile's membership replay so Step 10 and Step 12 inspect the same component set. When `sessionId` is absent, the route remains a catalog projection for catalog browsing and replacement previews before a draft exists.

**Containment derived from bindings.** The projection's `CONTAINS` tree nests a component under the component that uses
it, so the drawn hierarchy follows the composition's real dependencies. `StudioDerivedContainment` (Projector) derives
it from the claims' needs and offers Fabric has already matched. It never uses product names.
1. **Declared containment comes first.** A component whose containment is declared keeps that parent. It can be
   declared by an aggregate's `child_claims` (DCP HLD-C2 A.14), or by the containment bridge in claims written before
   them (REPO-recursive-dcp-projection-build-spec). Only a component declared directly under the assembly is considered
   for a derived parent.
2. **Uses.** The projection's `connections` between two draft components are the bindings: the `source` component offers
   what the `target` component needs, so the target *uses* the source.
   - Connections to or from the substrate are not uses between components.
   - A `BLOCKED` connection is not a binding.
3. **Placement.**
   - **One user:** a component used by exactly one other component is nested under it.
   - **Several users:** a component used by several is nested under their **nearest common user**. That is the component
     that reaches every one of those users through uses, and that reaches no other component that also does. When none
     exists, or more than one does, the component stays under the assembly.
   - **Not used:** a component nobody uses stays under the assembly.
4. **Cycles never become containment.** Uses between components of one cycle (one strongly connected group) are ignored
   for nesting, so no component can contain itself. The cycle's members are placed by their other uses.
   - A derived parent is checked against the whole tree, declared and derived together. A parent that is already one of
     the component's descendants is rejected: if A declares child B and B uses A, A is not nested under B. The
     component stays under the assembly.
5. **Each edge says where it came from.** Every edge carries `derivation`: `DECLARED`, or `DERIVED_FROM_BINDINGS` for
   a parent derived this way. A derived edge replaces the component's edge from the assembly. Its node's `parentNodeId`
   and `depth` follow it.
6. **No other edges are invented between components.** A dependency is its connection. The projection once added a
   `REQUIRES` edge from each component to the next in list order. That described no relationship, and it is removed.
7. **Overlays follow the drawn tree.** Resilience & Faults propagates a fault up the `CONTAINS` tree, derived edges
   included. A component's fault reaches the component that uses it.

Example: Flow uses Foundry (`agent.run`), and Flow and Foundry both use the Keycloak adapter (`identity-context`).
- Foundry is nested under Flow.
- Flow reaches Foundry, so Flow is the nearest common user of the Keycloak adapter, which is also nested under Flow.
- A component used by Foundry and by a substrate that Flow does not reach stays under the assembly.

Assembly snapshots are portable Studio workspace JSON. Saving an assembly captures the assembly summary, saved layout,
and draft sessions for that tenant/assembly. Loading an assembly writes those records back through Fabric's Studio state
store after normalizing tenant and assembly ids to the route. It does not deserialize arbitrary contract exports or bypass
DCP validation; subsequent edits, compile, and export still use Fabric's governed intent and validation APIs.

Loading a snapshot is **all or nothing**: the assembly summary, layout and sessions are replaced together or not at all.
- **Validate and normalize first, without touching stored state.**
  - The assembly summary and the sessions are re-scoped to the route's tenant and assembly.
  - The layout follows the layout rules (*Layout state extension*). A violation is a `400 LAYOUT_STATE_INVALID` naming
    the field.
  - Every session needs a non-blank `sessionId`, and no id may appear twice. A violation is a
    `400 ASSEMBLY_SNAPSHOT_INVALID` naming the session index.
  - A snapshot without a layout keeps the assembly's current layout. Reading it for that purpose stores nothing.
- **Then commit as one step.**
  - The commit replaces the summary, the layout and the assembly's sessions. The assembly's existing sessions are
    removed, so a snapshot with no sessions leaves none.
  - Then it persists.
  - It holds the same service lock as session intents, compile and layout writes, so no concurrent operation sees a
    mix of old and new records.
  - If persisting fails, the previous summary, layout and sessions are restored in memory and the error is returned.
    The import is never partly applied.

## Layout

| Method | Path | Request | Response |
|---|---|---|---|
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/layout` | | `StudioLayoutState` |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/layout` | `StudioLayoutStateRequest` | `StudioLayoutState` |

Layout is UI state and must not be used as a contract validity source.

## Sessions

| Method | Path | Request | Response |
|---|---|---|---|
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/sessions` | `StudioCreateDraftCompositionRequest` | `StudioCreateDraftCompositionResponse` |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/sessions/{sessionId}` | | `StudioDraftSession` |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/sessions/{sessionId}/collaborators/heartbeat` | `StudioCollaborator` | `StudioDraftSession` |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/sessions/{sessionId}/intents` | `StudioIntentRequest` | `StudioIntentResponse` |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/sessions/{sessionId}/compile` | `StudioCompileDraftCandidateRequest` | `StudioCompileDraftCandidateResponse` |
| `GET` | `/studio/tenants/{tenantId}/exports/{artifactId}/content` | `sha256` | `StudioExportArtifact` content |
| `GET` | `/studio/tenants/{tenantId}/sessions` | `assemblyId` optional | `List<StudioSessionHistoryItem>` |

`StudioIntentRequest` records governed operator edits in the session intent log. `ADD_COMPONENT` and the new entry of
`REPLACE_COMPONENT` must name an entry of the tenant catalog that carries a claim:
- an unknown entry is `CATALOG_ENTRY_NOT_FOUND`;
- an entry without a claim is `CATALOG_ENTRY_NOT_ADMITTED`. No catalog source holds one any more, active or earlier
  (*What a catalog entry is*), so this check guards the invariant rather than a reachable case.

An authoring proposal refused this way is a `gap` naming the entry and the reason.

So a draft never gains a component that could not compile.

**Aggregate children (DCP HLD-C2 A.14).** An aggregate's claim names its children in `child_claims`, by `claim_uri` and
`required`.
- **Projection.** Each selected child is drawn under its aggregate, as declared containment. The child is the
  component whose claim's `identity.uri` is the `claim_uri`; Studio's projection matches it through each entry's own
  claim, never its catalog id. A required child the draft does not select is named in a projection warning:
  `CHILD_CLAIM_MISSING: <aggregate> requires child <claim_uri>, which this draft does not include`. An absent optional
  child is not a warning, because the aggregate runs without it.
- **Validity.** Resolution and compile refuse a composition that selects an aggregate without one of its required
  children. The refusal is a `NO_MATCH` with a `CHILD_CLAIM` conflict naming the aggregate's coordinates and the
  child, the same gate in Studio and the CLI. It is part of "required dependencies resolved": the aggregate cannot run
  without the child. An optional child is never required.
- **Cycles.** Child references among the selected claims that form a cycle, required or optional, are refused with a
  `CHILD_CLAIM_CYCLE` conflict naming every claim in the cycle. For example, A names B and B names A.
  - The projection never nests along a cycle. Its members stay under the assembly, where they remain visible.
  - The projection warns: `CHILD_CLAIM_CYCLE: <members> name each other as children`.
`ADD_COMPONENT` and `REPLACE_COMPONENT` advance the current candidate pointer to
the accepted catalog entry. `REMOVE_COMPONENT` clears that pointer when the
removed catalog entry is the active candidate so later compile/export calls do
not use stale draft state. Port-level intents such as `CONNECT` and
`DISCONNECT` preserve the current candidate while recording their payload for
history and collaboration.

Compile is intentionally not pointer-based. `compileCandidate` replays the
session intent log (`ADD_COMPONENT`, `REMOVE_COMPONENT`, `REPLACE_COMPONENT`) to
derive the complete draft inventory, grounds every referenced catalog entry in
the tenant catalog, validates the resulting DCP composition against the stored
or supplied `Need`, resolves deployment shapes with the requested deployment
policy, and only then emits hash-pinned export artifacts. Successful compile
responses echo `expectedRevision` and `receivedRevision` with the revision
checked by the server; stale responses use `expectedRevision` for the current
server revision and `receivedRevision` for the caller-supplied revision.

The compiled decision audit records the selection mode exactly as the CLI does
(`AUTO_SINGLE` and `AUTO_BEST_SCORE` when the planner picked the candidate,
`MANUAL` when an operator did). A Studio draft is composed by the operator's own
intents, so every Studio compile records `MANUAL`.

Grounding uses only claims read from artifacts. Each draft component resolves to
its admitted or scanned catalog entry; after a restart or a snapshot load the
entry is rebuilt by parsing its persisted `catalogManifest` and is used only when
the recomputed claim hash equals the pinned `claimHash`. An entry whose manifest
no longer matches its hash makes compile return `CATALOG_ENTRY_NOT_ADMITTED`
naming the entry; Studio never builds a stand-in claim from visual metadata. The
catalog holds no entry without a manifest (*What a catalog entry is*).

Export artifacts are not synthetic URLs. The root contract, substrate profile,
signed root contract, and support artifacts returned by compile are stored as
immutable, hash-pinned tenant-scoped bytes and served through
`/studio/tenants/{tenantId}/exports/{artifactId}/content?sha256=...`.
`contractArtifact` is the light root DCP `CompositionContract`, not the full
Fabric compiler envelope. `signedContractArtifact` is the signed/frozen root
DCP handoff when signing is requested. `supportArtifacts` may include the signed
compiled-envelope artifact needed by Fabric runtime-binding tools and DCP
runtime bundles used by Flow hydration. `diagnosticArtifacts` may include the
unsigned compiled envelope and response snapshots; these are analogous to
compiler debug symbols and must not be required by production handoff consumers.
Signing requires an operator-configured Studio signing key pair; when signing is
requested without configured keys, Studio emits unsigned artifacts with an
explicit warning rather than fabricating a signature.

`StudioCreateDraftCompositionRequest` accepts optional `catalogFileId` and `displayName`. When `catalogFileId` is
present, Fabric resolves the selected tenant catalog file, sets the draft `baseCatalogHash` from that file's immutable
snapshot, records the catalog file id on the session, and creates a `studio_session_files` link with role `INPUT`. When
the request omits a display name, Fabric derives one from the selected catalog title and assembly id. The session response
contains the provenance fields so historical sessions can be rendered without re-reading the draft diagnostic artifact:
`catalogFileId`, `displayName`, `sessionType`, `status`, `createdAt`, `updatedAt`, and `lastOpenedAt`.

`GET /sessions` returns tenant-scoped historical sessions sorted by most recently updated/opened first. The response rows
include session id, assembly id, display name, status/type, selected catalog file id, catalog hash, timestamps, and counts
for linked files and accepted intents. This route is intentionally tenant-level so Studio can render a ChatGPT-style
history panel across assemblies while still accepting `assemblyId` as an optional filter.

The session-file link read model uses `tenantId` on both sides of the association:

```json
{
  "id": "link-...",
  "tenantId": "tenant-local",
  "sessionId": "studio-session-...",
  "correlationId": "compile-...",
  "fileId": "file-...",
  "role": "OUTPUT",
  "createdAt": "2026-07-19T..."
}
```

## Session Events

| Method | Path | Query | Response |
|---|---|---|---|
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/sessions/{sessionId}/events` | `once=true` optional | `text/event-stream` |

Without `once=true`, the endpoint holds the event stream open for up to 60 seconds and sends keep-alives when no event is available. Events use:

```text
event: session
data: <StudioSessionEvent JSON>
```

`StudioServer` must run the JDK `HttpServer` with an explicit concurrent executor.
Live event streams are long-lived by design and must not block same-session
intent, heartbeat, catalog, dynamic DCP, or layout requests while the stream is
open.

## Authoring

| Method | Path | Request | Response |
|---|---|---|---|
| `POST` | `/studio/authoring/converse` | `StudioAuthoringConverseRequest` | `StudioAuthoringConverseResponse` |

Authoring delegates to Foundry through DCP `agent.run` when configured. When no Foundry endpoint is configured, Fabric returns deterministic fallback behavior for local development and tests.
Fabric sets `invocation.metadata.executionMode=harness` on Foundry-backed authoring calls so the governed Foundry
agent harness can call proposal tools until a terminal `clarify`, `gap`, or `proposal` response is produced.
Fabric bounds the Foundry DCP HTTP exchange, including response body completion, with `UNFURL_FOUNDRY_DCP_TIMEOUT_MS`
or `-Dunfurl.foundry.dcp.timeoutMillis` (default 120 seconds). A timeout maps to a structured authoring gap instead of
leaving the Studio UI waiting for an open response body.
Clarification questions are typed API objects. Select-style questions expose options as `{ "label": "...", "value": "..." }`
objects so Studio can render friendly text while sending stable catalog file ids, catalog entry ids, or enum values back
to Fabric/Foundry. Follow-up turns carry those choices in `StudioAuthoringConverseRequest.questionAnswers`, a structured
map keyed by question id. Studio may also include a concise answer summary in `userMessage` that pairs question labels
with selected labels and stable values; that summary is context for generic ids, while `questionAnswers` remains the
authoritative machine-readable answer source.
Foundry is still advisory: when a proposal returns an intent whose catalog entry looks like a display label, uploaded
artifact basename, or other model-normalized alias, Fabric canonicalizes it only if the selected tenant catalog has a
single exact authority match and then validates the rewritten intent against that exact `catalogEntryId`. Unknown or
ambiguous aliases remain `CATALOG_ENTRY_NOT_FOUND` gaps.
Proposal mapping also normalizes model-emitted Studio intent type spellings such as `add_component`, `Add Component`,
or missing type with a `catalogEntryId` into canonical Studio intent names before alias grounding. Proposal validation
must use the same session-selected catalog file that was passed to Foundry, not the tenant's mutable default catalog,
so historical catalog-version runs remain reproducible.
`StudioAuthoringConverseRequest` may carry `catalogFileId`. Fabric resolves that tenant catalog file before building
the authoring context. When no session id is supplied, the authoring session is started from the requested catalog file
or, when absent, from the latest tenant `CATALOG` file version. Foundry receives only a compact invocation context:
`catalogFile`, `catalogFiles`, `session`, `sessionHistory`, `catalogHash`, `catalogSummary`, `catalogPreview`,
`contextLimits`, `questionAnswers`, and `toolHydration`. `catalogPreview` is a bounded list of label/id/capability
summaries for clarification UX; `catalogFiles`, `sessionHistory`, and `conversation` are also bounded previews of the
latest rows/turns. `contextLimits` reports each preview limit, total count, and omitted count so the agent knows when
to call hydration tools. The full catalog, visual manifests, ports, file paths, hashes, full chat replay, and complete
session history are not prompt payload. When the agent needs details it must call Fabric/Flow tools such as
`fabric.catalog-query`, `fabric.file-list`, `fabric.session-history`, `fabric.dynamic-dcp-project`, or Flow workflow
tools, keeping Fabric/Flow as the source of truth for expansion.
Follow-up turns that already carry structured clarification answers must narrow the prompt previews further: Fabric
keeps only the selected catalog file row, the active session history row, and catalog entries named by the answer/action
context, plus a small fallback preview. This prevents repeated agent-harness turns from exhausting Foundry prompt
budgets while preserving enough labels for the model to explain the current choice. Missing details remain a tool
hydration concern rather than a larger prompt concern.
The request may also carry an `actionContext` map for UI-originated edit assist.
Valid action contexts include `ADD_COMPONENT`, `REMOVE_COMPONENT`,
`REPLACE_COMPONENT`, `CONNECT`, `DISCONNECT`, and `CONFIGURE_SUBSTRATE`, plus
the selected catalog entry/component id, visible ports, substrate requirements,
and draft session id when available. Fabric passes this context through to
Foundry and the deterministic fallback uses it to ask configuration or removal
impact questions. The action context is advisory only; draft mutation still
requires accepted Studio intents through `/sessions/{sessionId}/intents`.
Foundry-backed authoring responses that claim `kind=execution` are rejected as gaps because Flow owns Flowfoundry
runbook DAG execution. Runbook steps call Studio tools through Flow nodes and consume Flow artifact outputs instead of
conversation text.
If the Foundry DCP endpoint itself returns a non-2xx response, Fabric reports a `DCP_TRANSPORT_ERROR`
that includes the sanitized response body when present, so operators see actionable provider/server
diagnostics instead of only an HTTP status code.

## Authoring Tool Gateway

| Method | Path | Request | Response |
|---|---|---|---|
| `POST` | `/studio/tools/{toolName}` | `StudioToolCallRequest` | `StudioToolCallResult` |

The tool gateway is the HTTP adapter for governed Studio tools. Flow runbook nodes and Foundry proposal tools may both
call this route when an operation must cross into Fabric Studio state. Fabric does not import Flow or Foundry runtime
classes here; it mirrors the stable tool-call JSON shape (`callId`, `toolName`, `arguments`, `metadata`) and converts
tool arguments into the existing Studio API records before calling `StudioCatalogService`.

The gateway covers Studio-owned state transitions/read models plus local
Flowfoundry runbook execution tools used in development and CI. The local
execution tools do not make the authoring agent a filesystem, signing, or Docker
executor; they expose explicit tool calls that Flow can gate with PASS/GAP
artifacts. Production deployments may bind the same logical tools through DCP
`tool.call`, Foundry `pluginJar`, or deployment-runner HTTP tools when stronger
runtime authority separation is required.

- `fabric.artifact-inventory`
- `fabric.catalog-admit`
- `fabric.catalog-query`
- `fabric.catalog-verify`
- `fabric.file-list`
- `fabric.session-history`
- `fabric.assembly-create`
- `fabric.needs-extract`
- `fabric.session-start`
- `fabric.authoring-converse`
- `fabric.session-intent-apply`
- `fabric.dynamic-dcp-project`
- `fabric.deployment-resolve`
- `fabric.candidate-compile`
- `fabric.contract-diff` [P2a]: the `contract-diff` route's inputs (`baseRevision`, `revision`, `deploymentPolicy`);
  `PASS` with the diff when available, `GAP` naming the absent reason otherwise.
- `fabric.overlay-annotate` [P2c]: the `overlays/{overlay}` route's inputs (`sessionId`, `overlay`, `view`,
  `deploymentPolicy`); `PASS` with the annotations, which state what is unavailable and why.
- `fabric.emit-plan-preview` [P2d]: the `emit-plan` route's inputs (`sessionId`, `environment`, `deploymentPolicy`);
  `PASS` with the preview when it is available, `GAP` naming the absent reason otherwise.
- `fabric.view-project` [P2b]: the `views/{view}` route's inputs (`sessionId`, `view`, `deploymentPolicy`); `PASS`
  with the projection, whose sections state what is unavailable and why.
- `fabric.contract-verify` [P2a]: the `verify` route's `StudioVerifyRequest`; `PASS` only when the contract verified
  and the catalog is clean, `GAP` otherwise.
- `fabric.export-download`
- `fabric.export-download-all`
- `fabric.runtime-binding-generate`
- `fabric.foundry-root-assemble`
- `fabric.flow-root-assemble`
- `fabric.container-image-build`

The local deployment-runner tools use Studio's existing validation and artifact
services where possible:

- `fabric.artifact-inventory` hashes configured local files and returns `GAP`
  with `questions` for missing required artifacts.
- `fabric.export-download-all` downloads primary handoff/support export
  artifacts by artifact id and SHA-256. Diagnostic sidecars are excluded by
  default because they are debug-symbol style artifacts; callers may opt in with
  `includeDiagnostics`.
- `fabric.runtime-binding-generate` wraps the DCP runtime-binding generator over
  the signed compiled support envelope. When the contract hosts in-process components
  (HLD-in-process-component-hosting §6) it applies the CLI's rule
  (`HostedComponentConfiguration.forContract`): `catalogPath` names the catalog whose packaged
  schemas are read, and `componentConfig` lists `<claimUri>#<key>=<value|config://…|secret://…>`
  entries validated with DCP's validator. A missing catalog, invalid or missing values, or values
  for a contract that hosts nothing return `GAP` naming the problem; no binding is written.
- `fabric.foundry-root-assemble` and `fabric.flow-root-assemble` copy only
  explicit deployment-root inputs (including the source root's profiles) and validate required files before returning
  PASS. When the signed compiled contract hosts components on Foundry,
  `fabric.foundry-root-assemble` also takes `catalogPath`, `dcpRuntimeBundlePath` and optional
  `trustKeysPath`, and writes the admitted-component overlay into `<root>/admitted/` with the
  emitter's `renderIntoDeploymentRoot` (HLD-E §4.0b). The result's `admittedOverlay` names the folder
  and the profile binding Foundry needs to read it; a host other than Foundry, or overlay inputs for
  a contract that hosts nothing, return `GAP`.
- `fabric.container-image-build` defaults to explicit `PLAN_ONLY` or
  `DOCKER_BUILD`; missing mode or image context returns `GAP` questions instead
  of guessing.

`fabric.catalog-admit` accepts the normal `StudioCatalogAdmissionRequest` shape. For local Flowfoundry runbook execution
it may also accept `artifactInventoryPath` or an inline `artifactInventory` object produced by Step 1; the gateway turns
inventory entries into JAR `artifactBase64` drafts and delegates to the same catalog admission service. This is a local
tool-runner convenience for already-inventoried files, not a public tenant upload shortcut.

`fabric.catalog-query` is the authoring hydration tool. It resolves tenant catalog data from Studio state by
`catalogFileId`, `catalogEntryId`, `capability`, or `capabilities`. It can return compact entries for clarification,
matching providers for required capabilities, and a detailed single-entry port summary when `includeDetails=true`.
Foundry agents must use this tool instead of requiring Fabric to embed full catalog metadata in `agent.run` prompts.
An unfiltered `fabric.catalog-query` call is also a bounded preview, not a full catalog dump: it returns at most the
requested `entryLimit`/`limit` value, capped by Fabric, and reports `entryTotal`, `entryLimit`, and `entriesOmitted`.
Agents that need authority for a specific decision should call the tool with `catalogEntryId`, `capability`, or
`capabilities` rather than hydrating every entry into the model loop.

`fabric.file-list` delegates to the tenant file registry and returns immutable file-version rows with optional
`fileType`, `sessionId`, and `correlationId` filters. Step 6 should use it to select a `CATALOG` file id before starting
a draft. `fabric.session-history` delegates to tenant session history so an authoring agent can ask whether to continue
an existing draft or create a new one without scraping diagnostic artifacts.

`fabric.session-start` accepts the same `catalogFileId` and `displayName` fields as
`StudioCreateDraftCompositionRequest`. If neither `catalogFileId` nor a nonblank `baseCatalogHash` is supplied, the
gateway resolves the latest tenant `CATALOG` file and starts the draft from that file id. If no catalog file exists, it
returns a `GAP` with a question asking which catalog should be admitted or selected.

Every Studio tool gateway call may include `outputPath`. When supplied, the gateway writes the canonical PASS/GAP tool
output to that local run artifact path and returns SHA-256 metadata. This is the artifact handoff used by Flow runbook
nodes; the authoring agent may read the artifact metadata but must not become the step executor.

`fabric.session-intent-apply` accepts either one normal Studio intent request or an `intents` array. Batch mode overlays
the top-level tenant, assembly, session, collaborator, and starting `baseRevision`, applies intents one at a time through
the same Studio validation path, advances the revision after each valid intent, and stops on the first invalid/stale
response with `output.status=GAP`. This shape is required because Flow references can pass a whole proposal intent list
but do not index arrays inside the DAG expression language.

Tool responses always include a structured runbook status in `output.status` when the call itself was understood:
`PASS` for successful Studio operations and `GAP` for blocking design/input gaps that should stop the runbook. Unexpected
handler/runtime failures return `success=false` with `errorCode` and `errorMessage`.

## Deployment Resolution

| Method | Path | Request | Response |
|---|---|---|---|
| `POST` | `/studio/deployment/resolve` | `StudioDeploymentResolveRequest` | `StudioDeploymentResolveResponse` |

This endpoint resolves deployment shape choices for Studio without making UI
layout authoritative. `StudioDeploymentResolveRequest` supports two input
strategies:

- Filesystem mode, for CLI/debug usage: `catalogPath` + `needsPath`.
- Studio session mode, for the UI flow: `tenantId` + `assemblyId` + `sessionId`
  with optional `needsYaml`/`needsId`.

Session mode replays the same complete draft inventory as compile and consumes
the tenant's admitted catalog/session state. It must never require browser users
to know server filesystem paths.

Errors:
- **Neither strategy complete.** A request with neither strategy complete, or with only one of `catalogPath` and
  `needsPath`, is `400 DEPLOYMENT_RESOLVE_INPUTS_INVALID`, and the message names what is missing. That includes a
  session request without its `tenantId` or `assemblyId`.
- **The real reason, not "malformed".** The request is checked while its body is read, so the handler reports the
  request's own message. Only a body that is not JSON, or does not match the request's fields, is
  `400 malformed json body`.
- **Unknown session.** An unknown session is `400` with the session id.

Studio's client always sends the tenant and assembly of its scope.

**Where a selection belongs.**
- A selection's `componentId` is the component's artifact coordinates (for example `com.unfurl:flow:1.0.0`), as the
  deployment resolver names it.
- Studio's draft projection names components by catalog entry (for example `uploaded:flow.jar`) and carries no
  coordinates.
- So in session mode each selection also carries `catalogEntryId`: the draft's catalog entry it was resolved for.
  Studio places the shape on that entry's node.
- Filesystem mode has no draft, and its selections carry an empty `catalogEntryId`.

## Deployment Environments [P2d]

A deployment environment is what an emit needs besides the contract: where it runs, and the operator's settings for its
runtime bindings. Studio keeps the tenant's environments, so the emit-plan preview (*StudioEmitPlanPreview*) never asks
a browser user for server paths or CLI flags. An environment holds **references only, never secret values**: secrets
are bound by the deploy backend at apply time.

| Method | Path | Request | Response |
|---|---|---|---|
| `GET` | `/studio/tenants/{tenantId}/deployment-environments` | | `List<StudioDeploymentEnvironment>` |
| `GET` | `/studio/tenants/{tenantId}/deployment-environments/{name}` | | `StudioDeploymentEnvironment` |
| `PUT` | `/studio/tenants/{tenantId}/deployment-environments/{name}` | `StudioDeploymentEnvironmentRequest` | `StudioDeploymentEnvironment` |
| `DELETE` | `/studio/tenants/{tenantId}/deployment-environments/{name}` | `expectedRevision` | `204` |

**Model.** `StudioDeploymentEnvironment {name, revision, updatedAt, contentSha256, target, bindings}`:
- `target`: the `DeploymentTarget` the CLI's `--target` file holds, `{kind, environment, region?, endpoint?, options,
  credentialRefs[]}`. `kind` is an emitter the server runs (`local-compose`, `k8s`, `remote`). The options are the
  target file's, used unchanged: images (`image.<coordinates>`), and the operator's deployment inputs the emitter renders
  from, the same option keys `fabric deploy` threads from its flags (`flowWorkflowsPath`, `foundryDeploymentRootPath`,
  `flowConfigPath`, `flowSecretsPath`, `serviceConfigPath.<name>`, `serviceSecretsPath.<name>`), naming folders on the
  Studio server. The preview never invents them: without one the emitter's refusal is the absent reason, for example
  "Flow deployment root requires target option flowWorkflowsPath" (`EMIT_FAILED`) or `COMPOSITION_PART_INPUT_MISSING`.
  **Reserved options.** The inputs the preview itself produces from the compile record and Studio's configuration are
  Studio's alone: `fabricContractPath`, `substrateProfilePath`, `runtimeBindingPath`, `dcpRuntimeBundlePath`,
  `componentArtifactsPath` and `trustKeysPath`. An environment never names them. Otherwise the emitter would read the
  operator's file while the preview reported what Studio generated (a target file's `putIfAbsent` lets its own options
  win), so the reported bindings and hashes could describe inputs the emit never used.
- `bindings`: the inputs `fabric runtime-bindings` takes as flags, read by the same code
  (`RuntimeBindingInputs`):
  - `serviceBaseUrls {providerClaimUri: url}`, `timeoutMs?`, `telemetryNamespace?`;
  - `identity? {issuer, audiences {serviceClaimUri: audience}, tenantClaim, rolesClaim, permissionsClaim,
    tokenEndpointRef, verificationKeysRef, clientCredentialsRef, serviceCredentials {serviceClaimUri: secretRef}}`;
  - `componentConfig[]`, entries `<claimUri>#<key>=<value>` for hosted in-process components;
  - `documentation? {visibilityDefault?, visibility?, observationIntervalMs?, observationMaxBackoffMs?}`, where
    `visibility` is the text of a documentation-visibility file;
  - `disabledBindings[]`, entries `<providerClaimUri>#<capability>`.
- `revision` starts at 1 and grows with each save; `updatedAt` is the save time. The revision is for concurrent
  editing only: deleting an environment and creating it again starts at revision 1 again, so a revision never
  identifies content.
- `contentSha256` is the SHA-256 of the canonical JSON of `{target, bindings}` (object keys sorted, absent values
  left out), computed by Fabric on every save and every read, never taken from a request. It identifies what an emit
  reads from the environment: equal content gives an equal hash, whatever the revision.

**Saving.** `PUT` with `StudioDeploymentEnvironmentRequest {expectedRevision, target, bindings}` creates the environment
when `expectedRevision` is 0 and it does not exist, and replaces it when `expectedRevision` is its revision. Otherwise
it is `409 ENVIRONMENT_REVISION_CONFLICT` with the current revision, so two editors never overwrite each other. `DELETE`
takes the same check. A save or delete is all or nothing: the change is kept only once Studio state is written, and
when writing fails the tenant's previous environments are restored and the request fails, so a retry meets the
revision it expects and a restart reads what was answered. Each save is checked before it is stored, and a refusal is
`400 ENVIRONMENT_INVALID` listing every problem with its field path:
- `name` matches `[a-z0-9][a-z0-9-]{0,62}`;
- `target` builds as a `DeploymentTarget`, which refuses a blank kind and credential refs that look like secret values,
  and `kind` is one the server's `EmitterBootstrap` provides;
- `serviceBaseUrls` parse as `ServiceBaseUrls`, `disabledBindings` as `DisabledBindings`, and `timeoutMs` and the
  observation intervals are positive whole numbers;
- every identity reference (`tokenEndpointRef`, `verificationKeysRef`, `clientCredentialsRef`, each service credential)
  is a `secret://` or `config://` URI, never a value;
- `target.options` names no reserved option (path `target.options.<key>`).

The preview checks the reserved options too, before it renders anything, because an environment stored before the rule
can still name one: it is then `ENVIRONMENT_INVALID` naming the option, and no emit runs. So every preview reports
exactly the inputs the emitter consumed.

Rules that depend on a contract are not save-time rules, because one environment serves many compiles. The preview
runs them, and their refusal is its absent reason under the generator's own code: identity inputs are required exactly
when the contract has an `identity-context` contract and refused when it has none (root CLAUDE.md rule 6), hosted
configuration is checked against the component's packaged schema, and documentation gaps are found.

Environments are tenant state, persisted with the rest of Studio state (`environmentsByTenant`); a state file written
before environments existed reads with none. An assembly's `defaultDeploymentTarget` stays a display hint and is not an
environment.

## Views and Overlays

Studio's workbench is organised by views, perspective overlays and renderers
(`unfurl-ui/docs/LLD-studio-viewpoints-and-perspectives.md`). These routes supply the typed data its P2 phase needs.
They are read models: none of them changes a session, a revision or a contract, and none is a validity source.

**Status.** P2 is built in slices (Studio LLD §10):
- **P2a, implemented:** compile records and freshness, durable compiled contracts, `contract-diff`, `verify`, and
  structured match failures.
- **P2b, implemented:** `views/{flow,data,topology,runtime}` (*StudioViewProjection* below). Runtime bindings moved to
  P2d, where bindings are generated for the emit plan anyway.
- **P2c, implemented:** `overlays/{security,performance,compliance}` (*StudioOverlayAnnotations* below). The runtime
  `timeout_ms` override and `audit_enabled` live in runtime bindings, so they arrive with P2d.
- **P2d, implemented:** deployment environments, `emit-plan` (*StudioEmitPlanPreview* below) with the runtime bindings
  it generates, and the Security overlay's "Identity in the needs" finding. Studio draws the bindings in Substrate &
  Topology and adds their facts to its overlays.
- **P2e, implemented:** `overlays/resilience` (*StudioOverlayAnnotations* below): each component's declared faults
  (DCP HLD-C2 A.10, divergence #5 resolved) and their propagation up the `CONTAINS` tree. It reads claims only.

**Fabric interprets claims; Studio never does.**
- Every claim field in these responses is read from the claim Fabric parsed from the artifact, through the
  `unfurl-dcp` model.
- Grounding is the same as compile: the persisted `catalogManifest` is used only when its recomputed claim hash equals
  the pinned `claimHash`. A mismatched or display-only entry is reported as `CATALOG_ENTRY_NOT_ADMITTED`, never
  approximated.
- Studio does not parse `catalogManifest` or dependency strings.

**Response rules shared by every route below:**
- Session scope matches `/dynamic-dcp` with `sessionId`: the accepted intents are replayed and only that draft
  inventory is projected.
- Element ids are the `/dynamic-dcp` node, edge, port and substrate-port ids, so selection and overlays line up across
  views.
- Each response carries `available` and, when false, `absentReason`, a stable code plus message. Examples:
  `NO_OWNED_STATE_DECLARED`, `NO_FAULTS_DECLARED`, `COMPILE_REQUIRED`, `NOT_IN_DCP_SCHEMA`.
- Every value carries `source`, the DCP field path it was read from (for example
  `claim.domain.concerns[2].owns_state[0].sensitivity`). Studio can show where each value came from.
- A field outside the shape DCP HLD-C2 defines for it is reported as a diagnostic (`severity`, `code`, `path`,
  `message`) and not projected. This matters most for `integration_ports`, which the Java model holds as an untyped
  map (DCP HLD-C2 §I.1).
- Each response also exposes a hash-pinned diagnostic artifact, like the other tenant-scoped read models.

**Compile records and freshness.** Some values come from a compile, not from the draft: data mappings, transports,
runtime bindings, verification, contract timeouts, the decision audit, the signing state, the emit plan and the contract
diff. After an edit they can describe an older composition, so they are never mixed with current draft elements.

- **Compile records [P2a].** Fabric keeps an append-only list of compile records per session, in
  `StudioDraftSession.compileRecords`:
  `StudioCompileRecord {revision, deploymentPolicyHash, contractHash, compiledEnvelopeSha256, substrateProfileSha256, signedEnvelopeSha256?, compiledAt}`.
  - There is one record per successful compile (`COMPILED`). A refused, failed or stale compile records nothing.
  - `revision` is the session revision that was compiled.
  - `deploymentPolicyHash` is the canonical hash of the deployment policy the compile was requested with. The policy
    is request input, not session state, so an operator can change it without a new revision.
  - `contractHash` is the compiled contract's canonical hash: the SHA-256 of
    `CompiledContractCodec.canonicalBytes`, as lower-case hex without a prefix (the signer's form). Signing signs exactly these bytes, so a signed envelope's `canonicalHash`
    equals it.
  - `compiledEnvelopeSha256`, `substrateProfileSha256` and `signedEnvelopeSha256` are the SHA-256 of the stored
    compiled envelope, the compile's substrate profile (`substrate-profile.yaml`; the envelope carries only its hash)
    and, when the compile was signed, the signed envelope (`signed-compiled-contract.yaml`). They address the bytes in
    the compiled-contract store below. `signedEnvelopeSha256` is absent when signing was not requested, and when the
    request asked for it but it failed, for example with no Studio signing keys.
  - A record names no runtime bindings. Bindings belong to a deployment environment, not to a compile, and one
    compile can be previewed for several environments; the emit-plan preview reports the bindings it generated
    (P2d). A state file that still holds the former, never-set `bindingsHash` field reads unchanged.
  - The latest record is the *current compile record*.
  - Records are stored and kept with the session, exactly like its intent log, and are never edited. A state file
    written before records existed reads with none.
  - **Records are facts of the Studio that compiled, so a snapshot never carries them in.** An intent log is replayed
    and re-validated on import; a record cannot be: nothing in the compiled bytes proves which session revision or
    policy it was compiled from. Importing an assembly snapshot therefore gives each imported session no records,
    whatever the snapshot holds, and the imported draft is compiled again here. Exported snapshots still list them,
    as a record of what the exporting Studio compiled.
  - The compile response carries the new record as `compileRecord`, so Studio pins a compile to Fabric's own hashes
    and never computes one.
- **Durable compiled contracts [P2a].** Export artifacts are a download cache: in memory, keyed by session, and
  replaced by the next compile. They cannot answer about a past compile, so the envelopes a record names are kept in
  the `StudioCompiledContractStore`, a content-addressed repository partitioned by tenant:
  - files named by their SHA-256 in `studio-compiled/<tenant folder>/` beside the Studio state file, next to
    `studio-companions/`; in memory when Studio runs without a state file;
  - the tenant folder is named by the SHA-256 of the tenant id, and a tenant reads only its own folder, so a record can
    never reach another tenant's contract by naming its hash;
  - a write is atomic (temporary file, then move) and never replaces existing content;
  - a read recomputes the SHA-256 and refuses bytes that do not match, so a damaged or edited file is never used.
  A record whose bytes are missing or refused answers `COMPILE_RECORD_UNAVAILABLE` naming the hash, never a guess.
- **A record is checked against its bytes [P2a].** A record names its hashes, and every read proves them before any
  value is derived from it (`StudioCompiledContractReader`):
  - the compiled envelope's canonical hash (SHA-256 of `CompiledContractCodec.canonicalBytes`) equals `contractHash`;
  - the stored profile's recomputed hash equals the envelope's `substrateProfileHash`;
  - the signed envelope's `canonicalHash`, and the canonical hash of the contract it carries, both equal `contractHash`.
  Any mismatch answers `COMPILE_RECORD_INCONSISTENT`, naming which part does not belong to the record, and nothing is
  diffed or verified. A `verify` result is therefore always about the contract its provenance names, and a
  `contract-diff` compares the contracts its provenance names.
- **The policy a request would compile with [P2a].** A freshness-checked read takes that policy as
  `deploymentPolicy`, URL-encoded JSON in the `StudioDeploymentPolicyDraft` shape (the compile request's
  `deploymentPolicy`). Fabric parses it into the record, so an absent list and an empty list are the same policy, and
  hashes it with the same canonical form it hashed the compile's policy with. Studio sends the policy it holds and
  never hashes anything, so the two sides cannot canonicalize differently. A malformed `deploymentPolicy` is
  `400 DEPLOYMENT_POLICY_INVALID`.
- **One freshness rule [P2a].** `StudioCompileFreshness` (Policy) decides whether a record is current, for every route
  here. Its inputs are the session, the record and the optional request policy. It answers `CURRENT`,
  `COMPILE_REQUIRED` or `COMPILE_STALE` with its message.
- **Provenance.** Every compile-derived value carries `sourceRevision` and `contractHash` from the record it came
  from. That covers every view section, every overlay annotation, and the `emit-plan`, `contract-diff` and `verify`
  responses.
- **Current means the same inputs.** A compile-derived value is current only when its `sourceRevision` equals the
  session's current revision. Records belong to one session, so a value from another session is never current. A
  request may also pass the policy it would compile with now, as `deploymentPolicy`. A record compiled with a
  different policy is then `COMPILE_STALE` too, with that reason, for example "compiled with a different deployment
  policy". Studio always passes it.
  - **No compile yet:** the section is `available: false` with `COMPILE_REQUIRED`.
  - **The draft has changed since:** the section is `available: false` with `COMPILE_STALE`. The message names both
    revisions, for example "compiled at revision 7; the draft is at revision 9". The stale values are left out, not
    returned beside current elements.
  - **Overlays:** annotations from a stale source are left out the same way. The response lists the source in
    `staleSources[] {source, sourceRevision, contractHash}`.
- **Historical is explicit.**
  - `contract-diff` and `verify` accept `revision` or `contractHash` to answer about a past compile, from that compile
    record. A revision compiled more than once (with different policies, say) names its latest record there; a
    `contractHash` names its latest record with that hash. When no compile was recorded at that revision or with that
    hash, the answer is `COMPILE_RECORD_UNAVAILABLE`.
  - Such a response carries `historical: true`.
  - Studio shows historical results only in its compile and history panels, labelled with their revision and hash,
    never as annotations on current elements.
- **Element ids.** A compiled artifact is mapped onto `/dynamic-dcp` element ids only when it is current. After a
  replace, an id can name a different component, so a stale artifact is never mapped.

| Method | Path | Query | Response |
|---|---|---|---|
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/views/{view}` | `sessionId`, optional `deploymentPolicy` | `StudioViewProjection` [P2b] |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/overlays/{overlay}` | `sessionId`, `view`, optional `deploymentPolicy` | `StudioOverlayAnnotations` [P2c] |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/sessions/{sessionId}/emit-plan` | `environment`, optional `deploymentPolicy` | `StudioEmitPlanPreview` [P2d] |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/sessions/{sessionId}/contract-diff` | `baseRevision`, optional `revision`, optional `deploymentPolicy` | `StudioContractDiff` [P2a] |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/sessions/{sessionId}/verify` | body `StudioVerifyRequest {revision?, contractHash?, deploymentPolicy?}` | `StudioVerificationResult` [P2a] |

The session's records reach Studio inside the session itself (`StudioDraftSession.compileRecords`), so Studio offers
a base revision for `contract-diff` from them without another route.

### `StudioViewProjection`

[P2b] `view` is one of `flow`, `data`, `topology`, `runtime`. Each adds to `/dynamic-dcp` and does not duplicate it.
`sessionId` is required: the views describe a draft. An unknown view is `400 VIEW_UNKNOWN`.

**Response:** `StudioViewProjection {view, tenantId, assemblyId, sessionId, revision, <the view's sections>,
diagnostics[], diagnosticArtifacts}`. Each section is `StudioViewSection {available, absentReason?, provenance?,
items[]}`:
- `items` is present and non-empty exactly when `available` is true. A section with nothing to show is
  `available: false` with its reason, never an empty list.
- every item carries `source`, the DCP field path it was read from (for example
  `claim.domain.concerns[0].owns_state[1]`), and the `/dynamic-dcp` node id of the element it belongs to;
- `provenance` is set on compile-derived sections only: the compile record's `{sourceRevision, contractHash,
  compiledAt}`.

**Grounding.** The view projects the draft's `/dynamic-dcp` projection for `sessionId` (the same replay), and grounds
each component node's `catalogEntryId` through the claim compile uses (`catalogEntryForVisual`, then companions). A node
that does not ground is one diagnostic (`CATALOG_ENTRY_NOT_ADMITTED`, `path: nodes[<nodeId>]`), and nothing is
projected for it; nothing is approximated from visual metadata. A draft with no component node gives every claim section
`NO_COMPONENTS`.

**Compile-derived sections** read the current compile record's contract through `StudioCompiledContractReader.load`,
so they follow every P2a rule:
- `COMPILE_REQUIRED`, `COMPILE_STALE` (the request's `deploymentPolicy` included), and `COMPILE_RECORD_UNAVAILABLE` /
  `COMPILE_RECORD_INCONSISTENT`;
- a child contract is mapped to a node by its provider party's `claim_uri`, and only while the record is current; an
  unmapped one has no node id.

| View | Section | Items, and the absent reason when there are none |
|---|---|---|
| `flow` | `externalOwners` | `{boundaryNodeId, owner, dependency, neededByNodeId, source}`, one per claim dependency (`needs` and `optional`) whose `owner=` qualifier (`Dependencies.ownerOf`) is non-blank. `boundaryNodeId` is `owner:<owner>`, so every node needing the same owner points at one boundary node. `NO_EXTERNAL_OWNERS`. |
| `flow` | `externalProtocolOffers` | `{nodeId, capability, protocol, source}` for each `external_protocol` offer (A.6). `NO_EXTERNAL_PROTOCOL_OFFERS`. |
| `data` | `ownedState` | `{nodeId, concern, resource, description, sensitivity, source}` (A.3.2). `NO_OWNED_STATE_DECLARED`. |
| `data` | `ownedDecisions` | `{nodeId, concern, decision, description, authority, source}` (A.3.3). `NO_OWNED_DECISIONS_DECLARED`. |
| `data` | `operations` | `{nodeId, capability, operation, summary?, requestSchema, responseSchema, source}`, the schemas as the claim's `artifact_ref`s (A.6). `NO_OPERATIONS_DOCUMENTED`. |
| `data` | `dataMappings` | compile-derived: `{childContractId, providerNodeId?, inbound, outbound, source}` for each child contract whose mapping is not empty (§B). `NO_DATA_MAPPINGS`. |
| `topology` | `transports` | compile-derived: `{childContractId, providerNodeId?, kind, details, source}` for each child contract (§B). Shapes stay in `/studio/deployment/resolve`; emitted facts and runtime bindings arrive in P2d. |
| `runtime` | `monitoring` | `{nodeId, required, healthEndpoints, readinessRequired, livenessRequired, notes?, source}` from the typed A.9 port. `NO_MONITORING_DECLARED`. |
| `runtime` | `telemetry` | `{nodeId, required, signals, correlationIdRequired, notes?, source}` from the typed A.9 port. `NO_TELEMETRY_DECLARED`. |
| `runtime` | `verification` | the P2a `verify` result for the current record (`StudioVerificationResult`), computed by the same service method, not a second implementation. |

The `ports` wiring map (A.9) is opaque to DCP and is never projected.

### `StudioOverlayAnnotations`

[P2c, P2e] `overlay` is one of `security`, `performance`, `resilience`, `compliance`; any other value is
`400 OVERLAY_UNKNOWN`. `sessionId` is required (`400 VIEW_SESSION_REQUIRED`), and `view` is
one of the four view ids (`400 VIEW_UNKNOWN`).

**Response:** `{overlay, view, tenantId, assemblyId, sessionId, revision, provenance?, available, absentReason?,
annotations[], findings[], staleSources[], diagnostics[], diagnosticArtifacts}`.
- **Provenance.** `provenance` (`{sourceRevision, contractHash, compiledAt}`) names the compile record an overlay that
  reads the compile (`performance`, `compliance`) was answered from, current or not, whenever the session has one, even
  when nothing came of it (for example `NO_TIMEOUTS_DECLARED`), as a compile-derived view section does. It is absent
  before any compile, and always absent for `security` and `resilience`, which read claims only. Studio pins a
  compile-reading overlay's answer to it, and never pins a claims-only overlay to a compile.
- **Annotation:** `{elementId, elementKind, kind, severity, label, detail, source, sourceRevision?, contractHash?}`.
  Annotations are on `/dynamic-dcp` elements only, so Studio's guard can keep only those of the active view's model:
  a node id (`elementKind: NODE`), or [P2e] an edge id (`elementKind: EDGE`). An edge's id is formed from its
  `/dynamic-dcp` edge as `RELATIONSHIP:fromNodeId->toNodeId`, for example `CONTAINS:assembly->flow`, the form Studio
  already gives its edges.
- **Finding** (overlay-level, for what belongs to no element, such as the decision audit): `{label, value, source,
  sourceRevision?, contractHash?}`.
- **Provenance and staleness.** `sourceRevision` and `contractHash` are present exactly on compile-derived entries,
  which are returned only while the current compile record is current and consistent (P2a rules). Otherwise they are
  left out, and the record is named in `staleSources[] {source, sourceRevision?, contractHash?, reason}`.
- **Availability.** `available` is true exactly when there is at least one annotation or finding. Otherwise
  `absentReason` says why: the overlay's own code, or the compile's reason when its only data is compile-derived.
- **Grounding.** Nodes are grounded as for views (a node that does not ground is a `CATALOG_ENTRY_NOT_ADMITTED`
  diagnostic, and nothing is projected for it). The overlay never introduces an element id that is not a node or edge
  of the draft's projection.

| Overlay | Reads | Annotations and findings | Absent reason |
|---|---|---|---|
| `security` | the typed A.9 `authentication` and `authorization` ports of each grounded claim, and whether a node provides `identity-context` | One `AUTHENTICATION` and one `AUTHORIZATION` annotation per node declaring them. The label states `enforced` when identity is in the composition and `trusted network` when it is not (root CLAUDE.md rule 6). The detail names the accepted modes, or the decision owner and permission prefixes. A finding states whether identity is in the composition, and [P2d] an "Identity in the needs" finding whether the session's need requires `identity-context` (the need compile uses). Trust rejections stay empty in session mode (*Gap: a session's trust policy is not applied*). | `NO_AUTH_PORTS_DECLARED` |
| `performance` | each child contract's `expectations.timeout_ms` in the current compile record | One `TIMEOUT` annotation on the child contract's provider node: "timeout N ms" for its capability. The runtime `timeout_ms` override arrives with P2d. | the compile's reason (`COMPILE_REQUIRED`, `COMPILE_STALE`, `COMPILE_RECORD_*`), or `NO_TIMEOUTS_DECLARED` |
| `resilience` [P2e] | each grounded claim's declared faults (`faults.emitted`, DCP HLD-C2 A.10), and the projection's `CONTAINS` edges | One `FAULT` annotation per declared fault on its node, labelled `fault <code>`. Its severity is `INFO` for `INFO`, `WARNING` for `WARNING` and `DEGRADED`, and `ERROR` for `CRITICAL` and `BLOCKING`. The detail gives the category, DCP severity, description, affected needs, offers and constraints, evidence signals and allowed actions. A fault whose `parent_impact` is `DEGRADED` or `BLOCKED` adds a `FAULT_PROPAGATION` annotation on the `CONTAINS` edge from the node's parent to it and on the parent node, labelled `<impact> by <code>`, with `propagates_when` and `suppresses_when` in the detail. Propagation is drawn to the immediate parent only: whether the parent escalates further is its own policy, known at runtime. A node with no parent in the projection gets no propagation annotation, and a finding names it. Findings: how many faults are declared, and how many propagate. Claims only, so no `provenance` and never stale. | `NO_FAULTS_DECLARED` |
| `compliance` | the current compile's decision audit; each claim's `telemetry.signals`; the session's trust policy | Findings for the decision audit (selection mode, alternatives considered, selection reasons, score breakdown, planning warnings) with provenance; a contract whose audit records no selection mode reads "not recorded", never a placeholder value. One `AUDIT_EVENTS` annotation per node whose telemetry declares `AUDIT_EVENTS`. A trust-policy finding: the named policy, or permissive, always stated as not applied while that gap stands. The runtime `audit_enabled` arrives with P2d. | never absent: the trust-policy finding is always present |

### `StudioEmitPlanPreview`

The preview runs the deploy emitter's real pipeline, `EmitPipeline.emit`
(`unfurl-deploy-emitter/docs/HLD-E-deployment-emitter.md` §3). It never calls `apply` or touches a live environment.

**One code path.** There is no separate, in-memory emit. `EmitRequest` requires a signed contract and an output
directory, and `EmitPipeline` runs signature preflight before any backend renders. A second "plan" method on every
backend would duplicate rendering and drift from what a deploy emits. So the preview refuses exactly what a real emit
refuses (preflight, `SERVICE_NAME_CONFLICT`, `COMPOSITION_PART_INPUT_MISSING`, `OPERATOR_MOUNT_TARGET_UNSUPPORTED`),
and reports it as the absent reason.

**Inputs.**
- **Contract:** the signed contract of the session's current compile record (see *Compile records and freshness*).
  - No compile yet: `COMPILE_REQUIRED`.
  - An edit since the compile: `COMPILE_STALE`.
  - An unsigned compile: `NOT_SIGNED`.
- **Trust keys:** the tenant's configured public keys, which `EmitPipeline` verifies the contract with. Without them
  the preview is `TRUST_KEYS_UNAVAILABLE`.
- **Environment:** `environment` names one of the tenant's deployment environments (*Deployment Environments*); an
  unknown name is `ENVIRONMENT_UNKNOWN`. Its target is used with its own options unchanged, and its binding inputs
  generate the bindings.
- **Signing key:** the DCP runtime bundle is signed with Studio's signing key, as compile signs it. Without the key the
  preview is `SIGNING_KEY_REQUIRED`.
- **Composition-part inputs:** a target that renders a declared composition part (HLD-E §4.0d) needs
  `dcpRuntimeBundlePath`, `runtimeBindingPath` and `trustKeysPath`. The preview generates them with the code a deploy
  uses, and never guesses them:
  - the bundle from `DcpRuntimeBundleWriter` over the contract's selections resolved in the tenant's admitted catalog;
  - the bindings from `RuntimeBindingSetGenerator` with the environment's binding inputs;
  - the tenant's public keys;
  - for hosted in-process components, their pinned jars and companions from the tenant's artifact store
    (`COMPONENT_ARTIFACT_UNAVAILABLE` when a jar is not there).

  A generation failure is the absent reason under its own code, for example `SERVICE_BASE_URL_MISSING`,
  `DOCUMENTATION_GAP` or `CATALOG_ENTRY_MISSING`.
- **Operator inputs:** operator directories (`serviceConfigPath.*`, `serviceSecretsPath.*`, Flow's workflows and the
  Foundry deployment root) are the operator's, so the preview never generates them: they come only from the
  environment's target options, as from a target file. When the composition needs one the environment does not name,
  the emitter's own refusal becomes the absent reason: `COMPOSITION_PART_INPUT_MISSING` on Compose,
  `OPERATOR_MOUNT_TARGET_UNSUPPORTED` on Kubernetes, or the emitter's message under `EMIT_FAILED`.

**Isolated output.**
- Each run gets a fresh temporary directory owned by Fabric, with owner-only permissions. Its name is unique to the
  run. It holds the generated inputs and the emitter's output.
- It is deleted in a `finally`, whatever the outcome, and nothing in it is served or returned except the fields
  below.
- At most one preview runs per session at a time. Concurrent requests for the same inputs share one run.
- **Cache.** Only an available answer is kept, per session. A refusal is never kept: it can come from an input
  outside Studio state, such as a missing workflow folder, and answering it again shows a repair at once.
  - **Key:** the record's `contractHash` (the record is current, so it was compiled with the request's policy), the
    environment's name and `contentSha256`, and a fingerprint of every input the run reads from outside Studio state:
    the trust-key folder, Studio's signing key files, and each operator path the environment's target options name
    (`flowWorkflowsPath`, `foundryDeploymentRootPath`, `flowConfigPath`, `flowSecretsPath`, `serviceConfigPath.<name>`,
    `serviceSecretsPath.<name>`).
  - **Fingerprint:** for each path, every file's relative path, size and modification time, walking folders; a
    missing path is recorded as missing. It reads metadata only and never opens a file, so a secrets folder's content
    is never read.
  - **What changes the key:** a new compile record, an environment whose content changed (edited, or deleted and
    created again with other settings), or an edited, added or removed file in one of those paths. The revision is
    not part of it, because a re-created environment reuses revisions.
  - Asking again with the same key does not render again. Only the response is kept, never the rendered files.

**Response.** `StudioEmitPlanPreview {tenantId, assemblyId, sessionId, revision, environment, environmentRevision?,
environmentSha256?, provenance?, available, absentReason?, targetKind?, artifacts[], requiredSecrets[], runtimeBindings[], bindingsSha256?,
emitManifestSha256?, diagnosticArtifacts}`:
- `provenance` is the compile record the preview read, current or not, as for the other compile-derived answers; absent
  before any compile. `environmentRevision` and `environmentSha256` are the revision and `contentSha256` of the
  environment the preview read, absent when it is unknown; Studio pins the answer to the content hash.
- `targetKind`, `artifacts[] {path, kind, sha256}`, `requiredSecrets[] {name, boundComponentId, targetStoreHint}` (the
  `SecretRequest` records, HLD-E §5.3) and `emitManifestSha256` come from the emit.
- `runtimeBindings[]` lists the generated set's bindings, each `{bindingId, contractId, capability, providerComponent,
  providerNodeId?, deploymentKind, baseUrl?, enabled, timeoutMs, auditEnabled, telemetryNamespace, secretRefNames[],
  configRefNames[]}`. `providerNodeId` is the draft node of the provider (the root binding has none).
  `secretRefNames` and `configRefNames` are the configuration keys whose values are references, never the references'
  targets. `bindingsSha256` is the generated set's SHA-256.
- An unavailable preview keeps `provenance`, `environment` and `environmentRevision`, and has empty lists.
- **Absent reasons.** Each refusal keeps its own code: `COMPILE_REQUIRED`, `COMPILE_STALE`, `COMPILE_RECORD_*`,
  `NOT_SIGNED`, `TRUST_KEYS_UNAVAILABLE`, `SIGNING_KEY_REQUIRED`, `ENVIRONMENT_UNKNOWN`,
  `COMPONENT_ARTIFACT_UNAVAILABLE`, `CATALOG_ENTRY_MISSING` (a selection the admitted catalog no longer holds),
  `HOSTED_CONFIGURATION_<reason>` (HLD-in-process-component-hosting §6); the generator's codes
  (`SERVICE_BASE_URL_MISSING`, `SERVICE_BASE_URL_UNKNOWN`, `DOCUMENTATION_GAP`, `DOCUMENTATION_SETTINGS_WITHOUT_CATALOG`,
  `BINDING_DISABLE_INVALID`, `IDENTITY_BINDING_INCOMPLETE`, `IDENTITY_INPUTS_WITHOUT_IDENTITY`); the emitter's codes
  (`SERVICE_NAME_CONFLICT`, `COMPOSITION_PART_INPUT_MISSING`, `OPERATOR_MOUNT_TARGET_UNSUPPORTED`, ...);
  `EMIT_PREFLIGHT_FAILED` for a signature preflight refusal; `EMIT_TARGET_UNSUPPORTED` for a kind with no installed
  emitter; `EMIT_SHAPE_UNSUPPORTED` with the shapes the target cannot emit. The code is read from the `CODE: message`
  prefix the generator and emitter write. A failure that names no code takes its stage's code:
  `DCP_RUNTIME_BUNDLE_FAILED`, `RUNTIME_BINDING_FAILED` or `EMIT_FAILED`, with its message.
- Tool: `fabric.emit-plan-preview`.

Paths, kinds, secret requests and bindings describe what a deploy of this contract to this environment emits. The
per-artifact `sha256`, `bindingsSha256` and `emitManifestSha256` describe this preview run only (the bindings carry their
generation time). Rendered files can hold host paths (HLD-E §4.0a), so
an operator's emit can hash differently. Studio labels them "preview hashes" and never compares them with a deployed
manifest.

Secret **values never appear**. The emitter does not have them, and the preview does not ask for them.

The emit manifest does not declare named volumes, published ports, the runtime state store mode or
`substrateProfileMode` today; they exist only inside rendered files. The preview reports them only once the manifest
declares them (gap recorded in HLD-E §3). Studio does not parse rendered compose or profile files.

### Structured match failures and trust rejections

[P2a] `NO_MATCH` responses from `/studio/deployment/resolve` and compile keep their `details` string unchanged and
add:
- `reasons[] {kind UNMET_NEED|CONFLICT, reasonCode, capability?, componentCoordinates?, message}`, one per cause.
  - **One source.** `message` is the cause's own detail text, which names its fix. `NoMatchExplanation` builds both
    `details` and `reasons` from the same causes in the same order, so the CLI, Studio and the advisor print the same
    words.
  - **`UNMET_NEED`:** `reasonCode` is the `UnmetCapabilityRequirement.Reason` value (`NOT_IN_CATALOG`,
    `VERSION_NOT_OFFERED`, `OFFER_DETAILS_UNSATISFIED`, `REJECTED_BY_TRUST_POLICY`).
  - **`CONFLICT`:** `reasonCode` names the `Conflict` variant (`MISSING_CAPABILITY`, `VERSION`, `OFFER_DETAIL`,
    `ARTIFACT`, `REFUSAL`, `BINDING`, `DEPENDENCY`, `CHILD_CLAIM`, `CHILD_CLAIM_CYCLE`).
  - **Optional fields:** `capability` is set when the cause names one. `componentCoordinates` is set for `DEPENDENCY`,
    naming the component whose claim declares the unresolved dependency, and for `CHILD_CLAIM`, naming the aggregate
    whose required child is missing.
- `trustRejections[] {coordinates, reasonCode, message}`, one per reason of each `trust.RejectedEntry` the match
  considered.
  - `coordinates` are the rejected entry's artifact coordinates.
  - `reasonCode` names the `RejectionReason` variant (`VendorNotTrusted`, `ArtifactGroupNotAllowed`,
    `StabilityBelowFloor`, `LifecycleNotAllowed`, `UnsignedClaim`, `CapabilityDenied`, `CapabilityNotAllowed`).

Which causes each mode produces:
- **Filesystem resolve** (`catalogPath` + `needsPath`) runs the `StructuralMatcher` under the request's trust policy,
  so it produces unmet needs, conflicts and trust rejections.
- **Session mode** validates the draft's own inventory with `CandidateValidator`, so its causes are conflicts. Its
  `details` keeps its form (the conflict details, sorted, joined with `; `), and `reasons` lists the same conflicts in
  the same order.
- **Gap: a session's trust policy is not applied.** A session's `trustPolicyId` is stored and returned, but nothing
  resolves it to a `TrustPolicy`: there is no tenant trust-policy registry. Session planning therefore runs without
  trust classification whatever the id, and `trustRejections` is empty there. The `TRUST_POLICY_PERMISSIVE` warning
  covers only a blank id. Applying a named policy needs a tenant trust-policy registry (admission, versioning, and
  resolving the id at plan and compile time). That is its own design, to be written before Studio shows a named
  policy as enforced.

### Contract diff and verification

[P2a] Both routes read records and envelopes only, never change a session, and register a hash-pinned diagnostic
artifact of their response.

- **`contract-diff`** compares two compile records of the session: the one at `baseRevision`, and either the current
  record or the one at `revision`.
  - **One differ.** The comparison is `verify.ContractDiffer`, extracted from the CLI's `cli/DiffCommand`. Its input
    is two `CompiledContract`s; the selection, deployment-entry and substrate-port deltas work on those, so unsigned
    compiles can be diffed too. It returns a typed `ContractDiff`: both canonical hashes, `canonicalHashChanged`,
    `contractIdChanged`, the added, removed and changed selections and deployment entries, the changed deployment
    shapes, and the added, removed and changed substrate ports. `fabric diff` prints that value with its output
    unchanged, and this route serializes it. Neither reimplements the comparison.
  - **Substrate ports.** A compiled envelope carries only its profile's hash, so the route diffs the two records'
    stored profiles (`substrateProfileSha256`). The CLI reads them from `--left-profile` and `--right-profile` as it
    does today, and prints `profileDetail=<not provided>` without them.
  - **Response:** `StudioContractDiff {available, absentReason?, base?, target?, historical, diff?,
    diagnosticArtifacts}`. `base` and `target` are each a provenance `{sourceRevision, contractHash, compiledAt}`, and
    `absentReason` is `{code, message}`.
  - It is `historical: true` when `revision` names a record other than the current one, and
    `COMPILE_RECORD_UNAVAILABLE` when no compile was recorded at a requested revision or its bytes are unavailable.
  - Against the current record, it is `COMPILE_STALE` when the draft or the request's `deploymentPolicy` has changed
    since that compile, and the diff is left out.
  - With no compile at all it is `COMPILE_REQUIRED`.
  - `baseRevision` is required; a missing or non-numeric one is `400 CONTRACT_DIFF_INVALID`.
- **`verify`** checks a signed contract with `com.unfurl.fabric.verify`. By default that is the current compile
  record's; with `revision` or `contractHash`, a past one (`historical: true`).
  - **Signature.** It reads the record's signed envelope from the compiled-contract store and runs
    `SignatureVerifier` with Studio's trust keys.
  - **Trust keys** are a directory of public keys (`TrustKeyDirectoryLoader`), configured as
    `unfurl.studio.trust.keysDir` or `UNFURL_STUDIO_TRUST_KEYS_DIR`, the same form as `fabric verify --trust-keys`.
    Without it the response is `available: false` with `TRUST_KEYS_UNAVAILABLE`. Fabric never falls back to the
    signing key, because a contract checked only against the key that signed it proves nothing about trust.
  - **Catalog drift** is always checked, against the tenant's current admitted catalog: every admitted entry that
    `catalogEntryForVisual` grounds, with the tenant's companions. A display-only entry is not part of it. The result
    is the `CatalogDriftReport` content.
  - **Response:** `StudioVerificationResult {available, absentReason?, provenance?, historical, signature? {ok,
    result, detail}, catalogDrift? {clean, hashDrifts[], missingFromCatalog[]}, overallOk?, diagnosticArtifacts}`.
    `provenance` is the verified record's `{sourceRevision, contractHash, compiledAt}`, and `result` names the
    `SignatureVerificationResult` variant (`Ok`, `KeyNotPinned`, `SignatureInvalid`, `HashMismatch`).
  - **Request:** `StudioVerifyRequest {revision?, contractHash?, deploymentPolicy?}`. A `deploymentPolicy` that is
    absent or null names no policy, so only the revision is compared.
  - Without a signed contract the response is `available: false` with `NOT_SIGNED`. Studio never reports an unsigned
    contract as verified.
  - Against the current record it follows the freshness rule like `contract-diff`.

### Layout state extension [P1]

`StudioLayoutState` and `StudioLayoutStateRequest` gain the workbench view state (Studio LLD §6):

| Field | Values |
|---|---|
| `activeViewpoint` | `FLOW`, `DATA`, `TOPOLOGY`, `RUNTIME` |
| `activeOverlays` | a list of distinct `SECURITY`, `PERFORMANCE`, `RESILIENCE`, `COMPLIANCE` |
| `colourOverlay` | one of the active overlays, or absent |
| `renderer` | `THREE_D`, `TWO_D`, `TABLE`; `THREE_D` only with `FLOW` or `TOPOLOGY` (Studio LLD §5.2) |
| `layout` | `ASSEMBLY`, `EXPLODED`; only with `FLOW` and `THREE_D` |

- **Validation.** Each value is validated, and a value outside its set is `400 LAYOUT_STATE_INVALID` naming the field.
  It is never coerced to a default. The same applies to a `colourOverlay` that is not among `activeOverlays`, and to a
  `layout` outside 3D Flow.
- **Old layouts.** A stored layout written before this has only `activeView`. It is returned as stored, with the new
  fields absent, and Studio maps `activeView` (Studio LLD §5.4).
- **Writes** carry the new fields. `activeView` is no longer written, and a request that sends it is
  `400 LAYOUT_STATE_INVALID`.
- Layout remains UI state and is never a contract validity source.

### Permissive trust policy warning [P1]

A draft session created without a `trustPolicyId` is planned under `TrustPolicy.permissive()`, the policy
`StudioDeploymentService` already falls back to. Its `warnings` therefore carry the planning warning
`TRUST_POLICY_PERMISSIVE`: "No trust policy is set for this session; every catalog entry is trusted." The warning is
present exactly when `trustPolicyId` is blank, and is recomputed whenever the session is returned. Studio's Security &
Trust overlay displays it and never infers it (Studio LLD §4).

## Client Mirror

The TypeScript client mirror lives in:

```text
unfurl-ui/packages/fabric-validation-client/src/httpClient.ts
```

When changing server routes or record shapes, update the TypeScript client and its tests in the same change.
