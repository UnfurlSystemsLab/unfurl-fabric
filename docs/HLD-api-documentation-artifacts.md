# HLD: API Documentation Artifacts in Fabric

**Status:** Design (Phase 0 of the API catalog plan). Implementation has not started.
**Governing designs:**
- DCP HLD-C2 §A.6, A.12, A.13, §C and §G;
- `unfurl-dcp/docs/LLD-unfurl-dcp-java.md` §"Capability Documentation Projection";
- `unfurl-api-catalog/docs/HLD-api-catalog.md`;
- this repo's `HLD-needs-driven-identity.md` and `HLD-in-process-component-hosting.md`.

## 1. Purpose

A deployed API catalog can document any component, but only if the component's documentation travels with the
deployment intact. Fabric is the one place that sees every component's artifact and writes the deployment bundle, so it
carries the documentation through and gates it:

1. **Scan:** verify each claim's shipped artifacts (`artifact_ref`) inside the component jar.
2. **Bundle:** copy them unchanged into the runtime bundle's content-addressed store.
3. **Gate:** refuse to emit a documentation policy that would leave a documented capability without schemas.
4. **Bind:** give every HTTP-served provider an endpoint of the DCP HTTP shape, and give the catalog its settings.

Fabric does not generate documentation. Documentation is projected at runtime by the catalog, because DCP requires
proof that a capability is registered in a running host (HLD-C2 G.1), and an export-time tool cannot have that proof.

## 2. Scan: verify shipped artifacts

`CatalogScanner` already reads each jar's `META-INF/unfurl-catalog.yaml` as data. For each entry with a claim, it now
also runs DCP's `ClaimArtifactValidator` over the claim, using DCP's `PackagedArtifactStore.fromArchive(jarPath)`. That
store reads the referenced paths from the jar as data: it never loads classes.

- Every `offers[].documentation` schema and every `api_documents[].openapi` document must be present, match its `sha256`,
  and pass DCP's document checks:
  - JSON Schema 2020-12 with only JSON-Pointer `$ref`s, and no `$id`, anchor or dynamic-reference keywords;
  - OpenAPI 3.1.0 obeying A.13: `x-dcp-surface` and `x-dcp-access` on every operation, `$ref`s only into its own
    `#/components/...`, and no method and path repeated across the component's documents.
- A failure makes the entry a `SkippedEntry` with the new reason `DOCUMENTATION_ARTIFACT_INVALID`. The detail starts with
  DCP's code (`ARTIFACT_UNAVAILABLE`, `ARTIFACT_HASH_MISMATCH`, `SCHEMA_DOCUMENT_INVALID`, `API_DOCUMENT_INVALID`) and names
  the path. The entry is not selectable and is reported, never silently dropped.
- The DCP warning `CLAIM_DOCUMENTATION_HINT_ONLY` (schema hints in `interface.details` without `documentation`) is shown
  in `scan` output as a warning. It does not skip the entry.

## 3. Bundle: content-addressed artifacts

`DcpRuntimeBundleWriter` writes the claims of the bundle's providers to `claims/` as today. For every `artifact_ref`
those claims name, it now also writes the verified bytes to `artifacts/sha256/<sha256>`.

- Bytes are copied exactly as scanned. The claim is not rewritten, and the claim hash is unchanged.
- Writing is idempotent: the same hash gives the same file. A file that exists with different bytes is a bundle error
  (`BUNDLE_ARTIFACT_CONFLICT`); it is never overwritten.
- The bundle layout becomes:

```text
claims/<slug>.claim.json
contracts/root.frozen.json
contracts/frozen/<capability>-<id>.frozen.json
artifacts/sha256/<sha256>
```

## 4. Bind: endpoints and the catalog's settings

### 4.1 Endpoints for any HTTP-served provider

`RuntimeBindingSetGenerator.endpointFor` today picks Flow's base URL for `workflow.*` capabilities and Foundry's for
everything else. A third HTTP provider, the catalog among them, would therefore get Foundry's address. It becomes
generic:

- Each component declares where it listens in its **catalog block** (catalog metadata, not the DCP claim):
  `binding.service_name` and `binding.service_port`. Flow declares `flow` / `8080`, Foundry `foundry` / `7979`, and the
  catalog `api-catalog` / `8090`.
- The endpoint of an HTTP-served provider's binding is `<base>/dcp/<provider_capability>`, where `<base>` is:
  - the operator's `--service-base-url <claim-uri>=<url>` when given; otherwise
  - `http://<service_name>:<service_port>` from the provider's catalog block.
- An HTTP-served provider with neither is a binding-generation error (`SERVICE_BASE_URL_MISSING`). Fabric never guesses.
- `--flow-base-url` and `--foundry-base-url` remain as aliases for `--service-base-url` on Flow's and Foundry's claim
  URIs, so existing runbooks keep working.
- The deploy emitter uses the same `service_name` and `service_port` for the service it renders (HLD-E §4.0c), so the
  binding and the running container always agree.

### 4.2 The catalog's binding settings

When the verified closure contains a provider of `documentation.catalog`, the generator writes that provider's binding
`configuration.values`:

```yaml
documentation_visibility:
  default: internal                  # operator option --documentation-visibility-default
  capabilities: { <capability>: public|internal|private }   # operator file --documentation-visibility
  api_documents: { <api_id>: public|internal|private }
observation:
  interval_ms: 10000                 # operator option --catalog-observation-interval-ms
  max_backoff_ms: 60000              # operator option --catalog-observation-max-backoff-ms
```

The binding's `runtime_policy.timeout_ms` is the generator's `timeoutMs`.
- Every value is written explicitly; the defaults above are Fabric's option defaults, recorded in the binding.
- The catalog has no defaults of its own and refuses to start without these settings.
- Compositions without a `documentation.catalog` provider get none of this. That absence is normal, not an error.

### 4.3 The documentation gap gate

Before it writes the policy, the generator runs DCP's `CapabilityDocumentationValidator.gaps(closure, claims, policy)`.
A non-empty result fails the run with `DOCUMENTATION_GAP` and lists every contract, capability and provider. There are
two ways to resolve a gap:
- the component ships the schemas (the right fix); or
- the operator marks that capability `private` in the visibility file. That decision is explicit and recorded in the
  binding.

Fabric never infers a schema, and it never drops an entry.

## 5. Identity

The catalog's claim declares `identity-context@v1` as an **optional** need, as Flow does. Existing rules then apply
unchanged (HLD-needs-driven-identity):

| | `identity-context` in the composition | Not in the composition |
|---|---|---|
| Catalog in `neededBy` | Yes: the catalog gets an audience in the identity binding | Not applicable |
| Service credentials | Required: the catalog calls providers' `/dcp/registrations` as itself, so `requireIdentityInputs` needs its `secret://` credentials input, as for Flow | None |
| Catalog settings (§4.2) | Written the same way | Written the same way |

## 6. Flowfoundry Step 18

Step 18 (`Flowfoundry.md`) was planned as an export-time OpenAPI and Swagger UI generator. It is redefined as
**`fabric.documentation-check`**, which runs at export time:
- §2's artifact verification over the exported components;
- §4.3's gap gate against the export's visibility policy.

Its result is `step-18-documentation-check-report.json`. Documentation itself is served by the API catalog in the
deployed composition.

## 7. Tests

- **Scan:** valid artifacts; a missing file; a hash mismatch; a remote `$ref`; an OpenAPI operation without
  `x-dcp-surface` or without `x-dcp-access`; an OpenAPI document with `servers`; a schema with `$id` or `$anchor`; a `$ref`
  to `#/paths/...`; two documents declaring the same method and path. Each invalid case gives `DOCUMENTATION_ARTIFACT_INVALID` with the
  DCP code. A hint-only claim warns.
- **Bundle:** artifacts are written by hash; a re-write of identical bytes is a no-op; a conflict fails; claim bytes
  and hashes are unchanged.
- **Bind:**
  - endpoints for Flow, Foundry, the catalog and an arbitrary third HTTP provider all take the DCP shape;
  - `--service-base-url` overrides the catalog block;
  - a missing base is `SERVICE_BASE_URL_MISSING`;
  - the aliases still work;
  - the catalog settings are written only when a `documentation.catalog` provider is present.
- **Gate:** a gap fails with every gap named; marking the capability `private` passes, and the policy records it.
- **Identity:** both columns of §5.
