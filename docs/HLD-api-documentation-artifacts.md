# HLD: API Documentation Artifacts in Fabric

**Status:** Implemented (Phase 3 of the API catalog plan).
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
- DCP's claim validator runs over each claim too, for its documentation rules only: an error in the claim's
  `documentation` or `api_documents` shape (for example `ARTIFACT_REF_INVALID`, or `CLAIM_EXTERNAL_PROTOCOL_INVALID`
  for an `external_protocol` offer that carries documentation) is the same `DOCUMENTATION_ARTIFACT_INVALID` skip. The
  warning `CLAIM_DOCUMENTATION_HINT_ONLY` (schema hints in `interface.details` without `documentation`) is reported on
  the scan report's `warnings` and printed by `fabric scan`. It does not skip the entry.

## 3. Bundle: content-addressed artifacts

`DcpRuntimeBundleWriter` writes the claims of the bundle's providers to `claims/` as today. For every `artifact_ref`
those claims name, it now also writes the verified bytes to `artifacts/sha256/<sha256>`. It reads them from each
provider's own jar with `PackagedArtifactStore.fromArchive`, which re-hashes them.

- Bytes are copied exactly as scanned. The claim is not rewritten, and the claim hash is unchanged.
- Writing is idempotent: every file is verified against its own hash, so one hash always names one file, and a hash
  named twice (for example a schema two offers share) is written once.
- An artifact that cannot be read or does not match its hash fails the bundle (`BUNDLE_ARTIFACT_UNAVAILABLE`), naming
  the provider and path. The scan (§2) normally prevents this; the writer checks again because it reads the jar again.
- The bundle layout becomes:

```text
claims/<slug>.claim.json
contracts/root.frozen.json
contracts/frozen/<capability>-<id>.frozen.json
artifacts/sha256/<sha256>
```

## 4. Bind: endpoints and the catalog's settings

### 4.0 Inputs: the signed contract and its catalog

The signed contract pins each child's provider by artifact coordinates, artifact SHA-256 and claim hash, but it does
not carry the claims or their catalog blocks. Binding needs both: the claims for the documentation gate (§4.3) and the
provider's `external_protocol` interface (§4.1), and the catalog blocks for service names and ports (§4.1).

`fabric runtime-bindings` (and Studio's `runtime-binding-generate` tool) therefore always takes the catalog the
contract was compiled from: `--catalog <dir>` (Studio: `catalogPath`). It is required for every composition.

- The catalog is scanned with §2's rules, so only valid entries are candidates.
- Each selection of the contract is resolved to the scanned entry with the same artifact SHA-256. Its coordinates and
  claim hash must equal the selection's.
  - No entry with that hash: `CATALOG_ENTRY_MISSING`.
  - The same jar under other coordinates, or another claim hash: `CATALOG_CLAIM_MISMATCH`.
  - Both refuse the run, naming the selection. Nothing is written.
- The same resolution writes the DCP runtime bundle (`fabric sign --dcp-runtime-bundle`), so both commands read one
  verified set (`SelectedCatalogEntries`).

### 4.1 Endpoints for any HTTP-served provider

`RuntimeBindingSetGenerator.endpointFor` used to pick Flow's base URL for `workflow.*` capabilities and Foundry's for
everything else, with `http://flow:8080` and `http://foundry:7979` as built-in defaults. A third HTTP provider, the
catalog among them, would therefore get Foundry's address. It is now generic, and Fabric no longer guesses:

- Each component declares where it listens in its **catalog block** (catalog metadata, not the DCP claim):
  `binding.service_name` and `binding.service_port`. Both or neither are given; the port is 1 to 65535. Flow declares
  `flow` / `8080`, Foundry `foundry` / `7979`, and the catalog `api-catalog` / `8090`.
- **One source: the signed plan.** Compile copies the declared service into the binding plan for a component placed
  in a shape the emitter renders as a service of its own (containerized, Spring Boot, standalone; HLD-D §6.2b). The
  generator reads it from that plan entry, never from the catalog block, and the emitter names and exposes the service
  from the same entry (HLD-E §4.0d). So the endpoint and the rendered service agree by construction, and both are
  pinned by the contract's signature. A component placed in process or as a remote microservice carries no service:
  its base URL is the operator's.
- The endpoint of a provider's binding is `<base>/dcp/<provider_capability>`, where `<base>` is:
  - the operator's `--service-base-url <claim-uri>=<url>` when given (repeatable, or comma-separated); otherwise
  - `http://<name>:<port>` of the service the plan entry carries.
- Which children get an endpoint:
  - **HTTP-served** (deployment kind `container`, `remote_service` or `sidecar`): always. One with no base is a
    binding-generation error (`SERVICE_BASE_URL_MISSING`), naming the provider and both ways to fix it.
  - **Not HTTP-served, not hosted** (for example a component whose catalog default is `IN_PROCESS` but which runs as
    its own service): an endpoint when it has a base. Without one its binding simply has no endpoint, as DCP C
    allows for a provider that is not DCP-HTTP invocable; it is not refused.
  - **Hosted in process** (HLD-in-process-component-hosting §6) and **identity** (HLD-needs-driven-identity §5):
    unchanged; they never have a base URL.
  - **`external_protocol` offer** (DCP HLD-C2 A.6, C): never. Its binding carries the conventional endpoint reference
    (`config://<tenant>/dcp/<capability>/endpoint`) in `base_url_ref`, because its consumers reach it over its own
    protocol.
- A `--service-base-url` for a claim URI that is not a provider of the composition is a usage error.
- `--flow-base-url` and `--foundry-base-url` remain as aliases for `--service-base-url` on Flow's claim URI
  (`urn:unfurl:flow:runtime`) and Foundry's (`urn:unfurl:foundry`), so existing runbooks keep working. Giving an alias
  and `--service-base-url` for the same claim URI is a usage error.
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
- The visibility file is YAML with two optional maps, `capabilities` and `api_documents`, whose values are `public`,
  `internal` or `private`. Any other key or value is a usage error. The default comes from the option, never the file.
- `interval_ms` and `max_backoff_ms` are positive, and `max_backoff_ms` is at least `interval_ms`.
- The catalog has no defaults of its own and refuses to start without these settings.
- Compositions without a `documentation.catalog` provider get none of this. That absence is normal, not an error. Any
  of the four options given for such a composition is refused (`DOCUMENTATION_SETTINGS_WITHOUT_CATALOG`), as identity
  inputs are refused for a composition without identity, so an operator setting is never silently dropped.

### 4.3 The documentation gap gate

The gate runs whenever a policy is written, so whenever the closure contains a `documentation.catalog` provider (§4.2).
Before it writes the policy, the generator runs DCP's
`CapabilityDocumentationValidator.gaps(children, claims, bindings, policy)` over the child contracts, the resolved
claims (§4.0), the bindings it just built and that policy. A non-empty result fails the run with `DOCUMENTATION_GAP`
and lists every contract, capability and provider. There are two ways to resolve a gap:
- the component ships the schemas (the right fix); or
- the operator marks that capability `private` in the visibility file. That decision is explicit and recorded in the
  binding.

Fabric never infers a schema, and it never drops an entry. An `external_protocol` offer, such as Keycloak's
`identity-context`, is an `external` entry whatever its deployment kind, so it is never a gap (DCP HLD-C2 G.1).

Without a catalog provider no policy is emitted, so the gate does not run. Each host still documents itself (its own
`/openapi.json`) and refuses an undocumented registered offer at runtime (HLD-C2 G.2); `fabric documentation-check`
(§6) lets an operator check that before deploying.

## 5. Identity

The catalog's claim declares `identity-context@v1` as an **optional** need, as Flow does. Existing rules then apply
unchanged (HLD-needs-driven-identity):

| | `identity-context` in the composition | Not in the composition |
|---|---|---|
| Catalog in `neededBy` | Yes: the catalog gets an audience in the identity binding | Not applicable |
| Service credentials | Required: the catalog calls providers' `/dcp/registrations` as itself, so `requireIdentityInputs` needs its `secret://` credentials input, as for Flow | None |
| Catalog settings (§4.2) | Written the same way | Written the same way |

The identity provider itself is documented the same way in both columns of its own deployment choice: bundled as a
container or run by the customer, its `external_protocol` offer is listed, never documented as a DCP operation (§4.1,
§4.3).

## 6. Flowfoundry Step 18: `fabric documentation-check`

Step 18 (`Flowfoundry.md`) was planned as an export-time OpenAPI and Swagger UI generator. It is redefined as
**`fabric.documentation-check`**, which runs at export time. Its CLI is:

```text
fabric documentation-check --signed-contract <signed.yaml> --catalog <dir> --out <report.json>
    [--documentation-visibility-default public|internal|private] [--documentation-visibility <file>]
    [--service-base-url <claim-uri>=<url> ...] [the other runtime-bindings options]
```

It takes the same options as `runtime-bindings`, identity inputs included, because it checks the bindings that command
would write. It runs:
- §4.0's resolution, so every selected artifact is found and its claim pin matches, and §2's artifact verification
  over the selected components (done when the catalog is scanned);
- §4.3's gap gate against the given visibility policy, over the bindings §4.1 would generate, whether or not the
  composition contains a catalog provider.

Studio's `fabric.documentation-check` tool takes the same arguments as `fabric.runtime-binding-generate`, plus
`reportPath`, and returns `PASS` or `GAP`.

Its result is `step-18-documentation-check-report.json`: `{"status": "PASS"|"GAP", "gaps": [...], "components":
[...]}`. Each gap names its `contractId`, `capability` and `provider`. Each component has its `claimUri` and `version`,
the capabilities it `documented`, and the ones it offers over its own protocol (`external`). It exits 0 on `PASS` and 1 on `GAP`. It writes no binding. Documentation itself is served by the
API catalog in the deployed composition.

## 7. Tests

- **Scan:** valid artifacts; a missing file; a hash mismatch; a remote `$ref`; an OpenAPI operation without
  `x-dcp-surface` or without `x-dcp-access`; an OpenAPI document with `servers`; a schema with `$id` or `$anchor`; a `$ref`
  to `#/paths/...`; two documents declaring the same method and path. Each invalid case gives `DOCUMENTATION_ARTIFACT_INVALID` with the
  DCP code. An `external_protocol` offer with documentation is skipped; a hint-only claim warns.
- **Bundle:** artifacts are written by hash; a shared schema is written once; a jar changed after the scan fails with
  `BUNDLE_ARTIFACT_UNAVAILABLE`; claim bytes and hashes are unchanged.
- **Inputs:** `--catalog` is required; a selection missing from the catalog and a claim-hash mismatch each refuse the
  run.
- **Bind:**
  - endpoints for Flow, Foundry, the catalog and an arbitrary third HTTP provider all take the DCP shape;
  - **handoff:** for a composition emitted to Compose and to Kubernetes, every binding endpoint names a rendered service
    and its exposed port (`FabricCliDocumentationArtifactsTest.bindingEndpointsNameTheEmittedServices`);
  - `--service-base-url` overrides the catalog block;
  - a missing base is `SERVICE_BASE_URL_MISSING`;
  - the aliases still work, and an alias plus `--service-base-url` for one provider is refused;
  - an `external_protocol` provider gets no base URL, in both its container and its external shape;
  - the catalog settings are written only when a `documentation.catalog` provider is present, and the options are
    refused otherwise.
- **Gate:** a gap fails with every gap named; marking the capability `private` passes, and the policy records it; a
  composition with Keycloak, bundled or external, passes.
- **documentation-check:** `PASS` and `GAP` reports and exit codes.
- **Identity:** both columns of §5.
