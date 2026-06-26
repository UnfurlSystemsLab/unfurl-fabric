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
| `POST` | `/studio/tenants/{tenantId}/catalog/admissions` | | `StudioCatalogAdmissionRequest` | `StudioCatalogAdmissionResponse` |
| `GET` | `/studio/tenants/{tenantId}/assets/{assetId}` | | | `StudioVisualAsset` |
| `GET` | `/studio/tenants/{tenantId}/assets/{assetId}/content` | `sha256` optional | | Binary asset content |

Catalog admissions accept uploaded component artifact drafts and update the tenant catalog after claim verification.

## Assemblies

| Method | Path | Query | Request | Response |
|---|---|---|---|---|
| `GET` | `/studio/tenants/{tenantId}/assemblies` | | | `StudioAssemblyListResponse` |
| `POST` | `/studio/tenants/{tenantId}/assemblies` | | `StudioCreateAssemblyRequest` | `StudioAssemblySummary` |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/needs/extract` | | `StudioNeedsExtractionRequest` | `StudioNeedsExtractionResponse` |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/dynamic-dcp` | | | `StudioDynamicDcpProjection` |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/dynamic-dcp/replacements` | `componentNodeId` | | `StudioReplacementCandidatesResponse` |
| `GET` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/dynamic-dcp/connection-candidates` | `catalogEntryId` | | `StudioConnectionCandidatesResponse` |
| `POST` | `/studio/tenants/{tenantId}/assemblies/{assemblyId}/drafts/save` | | `StudioSaveDraftRequest` | `StudioSaveDraftResponse` |

Dynamic DCP endpoints are read-model endpoints for visual composition and replacement guidance. They do not replace compile-time validation.

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

