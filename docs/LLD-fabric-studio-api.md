# LLD: Fabric Studio API

Fabric Studio API is served by `com.unfurl.fabric.studio.StudioServer`. It uses the JDK `HttpServer` and JSON records, not generated OpenAPI.

Default bind address is `127.0.0.1` and default port is `7878`.

## Configuration

| Setting | Source | Default |
|---|---|---|
| Bind address | `--bind` | `127.0.0.1` |
| Port | `--port` | `7878` |
| State path | `--state-path` or `UNFURL_STUDIO_STATE_PATH` / `-Dunfurl.studio.state.path` | module default |
| Asset root | `--asset-root` or `UNFURL_STUDIO_ASSET_ROOT` / `-Dunfurl.studio.asset.root` | fixture asset root |
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
| `GET` | `/studio/tenants/{tenantId}/catalog/snapshot` | | | `StudioCatalogSnapshot` |
| `POST` | `/studio/tenants/{tenantId}/catalog/snapshot` | | `StudioCatalogSnapshot` | `StudioCatalogVisualsResponse` |
| `POST` | `/studio/tenants/{tenantId}/catalog/admissions` | | `StudioCatalogAdmissionRequest` | `StudioCatalogAdmissionResponse` |
| `DELETE` | `/studio/tenants/{tenantId}/catalog/{catalogEntryId}` | | | `StudioCatalogRemovalResponse` |
| `GET` | `/studio/tenants/{tenantId}/catalog/admissions/{admissionId}/claims.zip` | `sha256` required | | ZIP claim bundle |
| `GET` | `/studio/tenants/{tenantId}/diagnostic-artifacts/{artifactId}/content` | `sha256` required | | Hash-pinned diagnostic artifact |
| `GET` | `/studio/tenants/{tenantId}/assets/{assetId}` | | | `StudioVisualAsset` |
| `GET` | `/studio/tenants/{tenantId}/assets/{assetId}/content` | `sha256` optional | | Binary asset content |

Catalog admissions accept uploaded component artifact drafts and update the tenant catalog after DCP claim verification.
Each draft may carry `claimYaml`, containing either a pure DCP `Claim` YAML document or a catalog manifest with a
top-level `claim` block. For `.jar` uploads, a draft may instead carry `artifactBase64`; Studio decodes the archive and
reads `META-INF/unfurl-catalog.yaml` without loading classes or executing artifact code. Missing, malformed, or
non-decodable embedded manifests are rejected with DCP-shaped diagnostics. Fabric parses the claim, runs `unfurl-dcp`
claim validation, and rejects the artifact when validation returns any `ERROR` diagnostic. Admission responses preserve
DCP diagnostics as structured records with `severity`, `code`, `path`, and `message` so Studio can render actionable
claim errors next to the artifact. Warnings remain visible on otherwise verified claims.

When an admission verifies one or more artifacts, Studio also emits a hash-pinned `claimBundleArtifact`. The bundle is a
ZIP containing the resolved DCP claim YAML for each verified artifact, an `admission-manifest.yaml` index, and
`diagnostics.json` for the full admission result set. Claims remain separate files inside the bundle; Fabric must not
merge multiple component claims into a synthetic mega-claim.

Catalog removal deletes one catalog entry from the tenant-scoped Studio catalog and returns the updated
`StudioCatalogVisualsResponse`. Removal is a Studio catalog curation operation, not a DCP validity shortcut: existing
draft sessions that reference the removed entry continue to fail later catalog-grounding checks until the operator
replaces or removes those draft components. The response includes a diagnostic artifact so support and CI can replay
the exact catalog state after the removal.

Catalog snapshots are tenant-scoped portable JSON state. Saving a catalog returns the exact DCP-backed visual entries
and catalog hash Fabric currently serves to Studio. Loading a catalog replaces only the addressed tenant catalog; the
route tenant remains the isolation boundary even when the JSON was saved from another tenant. The loaded entries remain
subject to the same downstream intent validation and catalog-grounding checks as admitted entries.

Studio responses that produce or transform operator-visible state may also include `diagnosticArtifacts`. These artifacts
use the same `StudioExportArtifact` shape and point at the tenant-scoped diagnostic-artifact endpoint. Diagnostic
artifacts are immutable, hash-pinned snapshots intended for support, CI replay, and step-by-step Flowfoundry debugging.
Catalog admissions, catalog removals, catalog snapshots, needs extraction, dynamic DCP projections, saved draft
summaries, created draft sessions, and compile responses should all expose a downloadable diagnostic artifact when the
response is produced by a tenant-scoped route.

## Assemblies

| Method | Path | Query | Request | Response |
|---|---|---|---|---|
| `GET` | `/studio/tenants/{tenantId}/assemblies` | | | `StudioAssemblyListResponse` |
| `POST` | `/studio/tenants/{tenantId}/assemblies` | | `StudioCreateAssemblyRequest` | `StudioAssemblySummary` |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/needs/extract` | | `StudioNeedsExtractionRequest` | `StudioNeedsExtractionResponse` |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/dynamic-dcp` | | | `StudioDynamicDcpProjection` |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/dynamic-dcp/replacements` | `componentNodeId` | | `StudioReplacementCandidatesResponse` |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/dynamic-dcp/connection-candidates` | `catalogEntryId` | | `StudioConnectionCandidatesResponse` |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/snapshot` | | | `StudioAssemblySnapshot` |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/snapshot` | | `StudioAssemblySnapshot` | `StudioAssemblySnapshot` |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/drafts/save` | | `StudioSaveDraftRequest` | `StudioSaveDraftResponse` |

Dynamic DCP endpoints are read-model endpoints for visual composition and replacement guidance. They do not replace compile-time validation.

Assembly snapshots are portable Studio workspace JSON. Saving an assembly captures the assembly summary, saved layout,
and draft sessions for that tenant/assembly. Loading an assembly writes those records back through Fabric's Studio state
store after normalizing tenant and assembly ids to the route. It does not deserialize arbitrary contract exports or bypass
DCP validation; subsequent edits, compile, and export still use Fabric's governed intent and validation APIs.

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

## Session Events

| Method | Path | Query | Response |
|---|---|---|---|
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/sessions/{sessionId}/events` | `once=true` optional | `text/event-stream` |

Without `once=true`, the endpoint holds the event stream open for up to 60 seconds and sends keep-alives when no event is available. Events use:

```text
event: session
data: <StudioSessionEvent JSON>
```

## Authoring

| Method | Path | Request | Response |
|---|---|---|---|
| `POST` | `/studio/authoring/converse` | `StudioAuthoringConverseRequest` | `StudioAuthoringConverseResponse` |

Authoring delegates to Foundry through DCP `agent.run` when configured. When no Foundry endpoint is configured, Fabric returns deterministic fallback behavior for local development and tests.

## Deployment Resolution

| Method | Path | Request | Response |
|---|---|---|---|
| `POST` | `/studio/deployment/resolve` | `StudioDeploymentResolveRequest` | `StudioDeploymentResolveResponse` |

This endpoint resolves deployment shape choices for Studio without making UI layout authoritative.

## Client Mirror

The TypeScript client mirror lives in:

```text
unfurl-ui/packages/fabric-validation-client/src/httpClient.ts
```

When changing server routes or record shapes, update the TypeScript client and its tests in the same change.
