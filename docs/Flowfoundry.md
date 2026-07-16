# Flowfoundry Export Runbook

This runbook describes the intended end-to-end path for creating a complete Flow + Foundry deployment export from Fabric Studio. It starts with DCP claims authored or uploaded through the UI, downloads the exported artifacts locally through Studio APIs, and assembles a containerized deployment that uses Foundry and all Foundry Substrate capability surfaces.

The flow is intentionally DCP-first:

- Fabric owns design-time catalog admission, Studio sessions, composition, deployment resolution, contract compilation, signing, and export metadata.
- DCP owns claim, contract, runtime binding, deterministic broker, and protocol-level port semantics.
- Foundry owns agent execution, provider/tool registries, concrete model/RAG/vector bindings, cost reporting, and the `agent.run` DCP endpoint.
- Foundry Substrate owns the stable AI domain and port surfaces: agent runtime, model provider, embedding provider, vector store, RAG retriever, tool executor/registry, provider registry, cost guardrail, permission bridge, and agent event sink.
- Flow owns durable workflow execution and may invoke Foundry capabilities through DCP contracts or Foundry Substrate node executors.

## Current Assumptions

Some pieces exist today and some are the intended production export shape.

Implemented today:

- Fabric Studio catalog admission accepts uploaded claim YAML or uploaded JAR bytes with `META-INF/unfurl-catalog.yaml`
  and validates the resulting claim with `unfurl-dcp`.
- Fabric Studio session APIs can create sessions, apply intents, compile candidates, and return export artifact metadata.
- Fabric Studio deployment resolution can resolve container-friendly deployment selections.
- Fabric can delegate authoring to Foundry over DCP `agent.run` with `UNFURL_FOUNDRY_DCP_ENDPOINT`.
- Foundry can run an `agent.run` DCP server from a deployment root containing agents, prompts, tool plugins, and provider configuration.

Assumed for the complete export:

- Studio exposes downloadable artifact content for the `StudioExportArtifact.url` returned by compile.
- The exported deployment is containerized.
- Runtime authentication, authorization, audit, telemetry, and secrets are expressed through DCP runtime bindings or ports, not hardcoded headers, vendors, or inline secrets.
- Foundry provider, embedding, vector, RAG, tool, permission, cost, and event bindings are supplied by deployment profiles or runtime bindings.

## Export Inputs

Prepare these inputs before starting:

| Input | Owner | Purpose |
|---|---|---|
| DCP component claims | Component authors / Studio | Describe Flow, Foundry, tools, model providers, RAG/vector, auth, telemetry, and deployable application components. |
| Catalog artifacts | Fabric | JAR/YAML/component packages carrying claims and optional visual metadata. |
| Needs file or Studio needs extraction | Fabric | Describes the desired application and required capabilities. |
| Trust policy | Fabric | Decides which claim issuers, stability levels, and trust tiers are acceptable. |
| Deployment policy | Fabric / deployment owner | Chooses container runtime, resource class, networking, secret refs, and substrate support. |
| Foundry deployment root | Foundry | Contains agent definitions, prompts, tool plugins, provider plugins, and registry config. |
| Runtime binding file | DCP / deployment owner | Binds frozen contracts to container endpoints, secret refs, config refs, telemetry namespace, and audit behavior. |

## Runbook Phases

The first 18 steps are grouped into five build phases. Steps 19-21 are post-build runtime execution, verification,
and promotion gates.

For the phase-gated authoring-agent question and tool execution contract, see
`FlowfoundryAuthoringAgentRunbook.md`.

| Phase | Name | Steps | Exit criteria |
|---|---|---|---|
| 1 | Catalog Creation | 1-3 | Required Flow, Foundry, substrate, provider, RAG/vector, runtime-support, and workload capabilities are admitted and visible in the tenant catalog. |
| 2 | Assembly | 4-11 | A Studio assembly draft exists, needs are captured, authoring has produced catalog-backed intents, dynamic DCP projection is valid, and deployment choices resolve. |
| 3 | Export | 12-14 | The candidate is compiled, signed, downloaded, hash-verified, and converted into DCP runtime bindings without inline secrets. |
| 4 | Deployment | 15-17 | Foundry and Flow deployment roots are assembled and container images are built from the exported contract and runtime-binding artifacts. |
| 5 | API Documentation | 18 | OpenAPI documents and a Swagger UI bundle are generated from the signed DCP contract, runtime bindings, and service route/tool descriptors. |

## Phase 1: Catalog Creation

## Step 1: Upload Foundry And Substrate Claims

Foundry and the Foundry Substrate modules already publish or carry DCP claim material for their capability surfaces. For this flow, the operator should collect the existing Foundry product, Foundry Substrate, Flow, provider, runtime-support, and application artifact files and upload them through the Studio UI so Fabric can add them to the tenant catalog after DCP validation.

Foundry deployment-root files are handled separately. Agent YAML, prompt Markdown, ToolPlugin JARs, and copied provider plugins are loaded by Foundry from its deployment root; they are not Fabric catalog-admission artifacts unless their owning package also publishes a DCP catalog claim.

Upload the artifact files that represent the complete deployment capability surface:

1. Foundry product claim exposing the `agent.run` host capability.
2. Foundry Substrate artifacts for the AI domain, ports, offers, engine, prompt, tools, RAG, resolver, serialization, adapters, and testing fixtures used by the deployment.
3. Flow runtime claim exposing `workflow.execute` for durable workflow execution.
4. Application or workload component artifacts.
5. Model provider or model gateway adapter artifacts.
6. Embedding provider adapter artifacts.
7. Vector store and RAG retriever artifacts.
8. Authentication, authorization, audit, telemetry, and secrets/config provider artifacts where production deployment needs them.

### Files To Add To The Catalog

Add these files through the Studio catalog UI, or upload the matching DCP claim YAML for each file when the artifact does not already embed a catalog manifest. JAR uploads should carry `META-INF/unfurl-catalog.yaml`; YAML uploads should be pure DCP `Claim` YAML or the Fabric catalog-manifest envelope with a top-level `claim` block.

Foundry product artifact:

| File | Catalog role |
|---|---|
| `unfurl-foundry/target/unfurl-foundry-0.1.0-SNAPSHOT.jar` | Foundry runtime product artifact exposing the Foundry DCP claim and `agent.run` host capability. |

Do not add Fabric authoring agent definitions, prompt Markdown files, or authoring ToolPlugin JARs to this catalog list. Fabric authoring is served by Fabric through Foundry, and those files are prepared as Foundry deployment-root inputs in Step 7.

Foundry Substrate module artifacts:

| File | Catalog role |
|---|---|
| `unfurl-foundry-substrate/foundry-substrate-domain/target/foundry-substrate-domain-0.1.0-SNAPSHOT.jar` | Agent, phase, skill, tool, prompt, model, RAG, embedding, and run-state domain shapes. |
| `unfurl-foundry-substrate/foundry-substrate-ports/target/foundry-substrate-ports-0.1.0-SNAPSHOT.jar` | AI ports, guardrails, permission bridge, provider/tool/RAG/vector/agent interfaces, and node-executor adapters. |
| `unfurl-foundry-substrate/foundry-substrate-offers/target/foundry-substrate-offers-0.1.0-SNAPSHOT.jar` | DCP AI offer fragments and `ContractInvocable` adapters for `agent.run`, `tool.call`, `rag.search`, and `provider.call`. |
| `unfurl-foundry-substrate/foundry-substrate-engine/target/foundry-substrate-engine-0.1.0-SNAPSHOT.jar` | Minimal embedded agent runtime used beneath Foundry's durable runtime. |
| `unfurl-foundry-substrate/foundry-substrate-events/target/foundry-substrate-events-0.1.0-SNAPSHOT.jar` | Agent event schema for metadata-safe audit and telemetry signals. |
| `unfurl-foundry-substrate/foundry-substrate-prompt/target/foundry-substrate-prompt-0.1.0-SNAPSHOT.jar` | Prompt assembly and message rendering support. |
| `unfurl-foundry-substrate/foundry-substrate-tools/target/foundry-substrate-tools-0.1.0-SNAPSHOT.jar` | Default tool registry support. |
| `unfurl-foundry-substrate/foundry-substrate-rag/target/foundry-substrate-rag-0.1.0-SNAPSHOT.jar` | RAG helper implementation over the vector-store port. |
| `unfurl-foundry-substrate/foundry-substrate-resolver/target/foundry-substrate-resolver-0.1.0-SNAPSHOT.jar` | Agent and data reference resolution support. |
| `unfurl-foundry-substrate/foundry-substrate-serialization/target/foundry-substrate-serialization-0.1.0-SNAPSHOT.jar` | Stable JSON/YAML codec for Foundry Substrate public shapes. |
| `unfurl-foundry-substrate/foundry-substrate-springai-adapter/target/foundry-substrate-springai-adapter-0.1.0-SNAPSHOT.jar` | Standard Foundry provider bridge for this runbook; wraps deployment-supplied Spring AI model, embedding, or vector bindings behind neutral Foundry ports. |
| `unfurl-foundry-substrate/foundry-substrate-testing/target/foundry-substrate-testing-0.1.0-SNAPSHOT.jar` | Test/dev fixtures only; add to catalog only for local demos, examples, or non-production validation flows. |

Flow, application, and runtime-support artifacts:

| File | Catalog role |
|---|---|
| `unfurl-flow/target/*.jar` or the Flow runtime package | Flow runtime component exposing `workflow.execute`. |
| Application/workload component package files | Deployable application components selected by the composition. |
| Model provider adapter package files | Spring AI provider module or explicit provider plugin selected by the deployment profile. Direct provider-specific bindings are valid only when the deployment intentionally opts out of the standard Spring AI bridge. |
| Embedding provider adapter package files | Concrete `EmbeddingProvider` bindings. |
| Vector store adapter or service claim files | Concrete `VectorStore` binding for the RAG pipeline. |
| RAG retriever adapter or service claim files | Concrete `RagRetriever` binding when RAG is deployed as a separate capability. |
| Authentication provider claim file | Optional for the current runbook; required for production when a verified identity source must be represented as a DCP port/runtime binding. |
| Authorization or policy-engine claim file | Optional for the current runbook; required for production when an authorization decision owner must be represented as a DCP port/runtime binding and mapped to Foundry `PermissionBridge`. |
| Audit sink claim file | Optional for the current runbook; required for production when audit events must be routed to a governed destination. |
| Telemetry sink claim file | Optional for the current runbook; required for production when metrics, traces, and correlation signals must be routed to a governed destination. |
| Secret/config provider claim file | Optional for the current runbook; required for production when secret and config references must be resolved through a governed provider. |

For now, the five cross-cutting claim rows above are non-blocking catalog inputs. Before production promotion, represent cross-cutting concerns through DCP constructs:

- Authentication: claim/runtime-binding port for verified identity source.
- Authorization: claim/runtime-binding port plus Foundry `PermissionBridge`.
- Audit: claim/runtime-binding port plus event sink binding.
- Telemetry: claim/runtime-binding port plus trace/metric context.
- Secrets: `SecretRef` / `ConfigRef`, never inline credentials.

## Step 2: Admit Claims Through Studio

Use Fabric Studio catalog admission so every uploaded artifact is validated by DCP before it becomes selectable.

The JSON admission API accepts either explicit DCP claim material in `claimYaml` or JAR artifact bytes in
`artifactBase64`. When a `.jar` draft includes `artifactBase64` and omits `claimYaml`, Studio reads the embedded
catalog manifest from:

```text
META-INF/unfurl-catalog.yaml
```

The Studio UI performs this archive read automatically for JAR uploads. Direct API clients can either send the full JAR
as base64 in `artifactBase64`, or extract `META-INF/unfurl-catalog.yaml` locally and send that YAML as `claimYaml`.
The YAML may be either:

- a pure DCP `Claim` document; or
- the Fabric catalog-manifest envelope with a top-level `claim` block and a `catalog` block.

#### Field-name convention: snake_case is canonical

All field names in **both** blocks are authored in **snake_case** — this is the canonical DCP wire format
(see `HLD-C2 §H`). Examples: `boundary_principles`, `consumer_access`, `negotiation_surface`,
`protocols_supported`, `supported_intents`, `answer_grounding`, `cost_implications`, `owned_by`,
`dcp_version`, `claim_version`, and in the catalog block `default_mode`, `supported_modes`,
`component_shape_profile` (`default_shape`, `supported_shapes`, `shape_runtime` with nested `binding_mode`,
`endpoint_ref_hint`, …).

Both Fabric parse paths agree on this: the Studio admission validator
(`StudioClaimAdmissionValidator`) and the catalog scanner (`CatalogManifestCodec`) both bind with Jackson's
`PropertyNamingStrategies.SNAKE_CASE`. Because `FAIL_ON_UNKNOWN_PROPERTIES` is disabled, **camelCase keys
do not error — they silently fail to bind**, producing misleading "field is required" diagnostics on a
field that is in fact present. Always author manifests in snake_case. (The opaque claim-hash mapper is a
deliberate exception kept stable for hash continuity; it does not affect the bound `Claim` object.)

```bash
curl -sS -X POST "http://127.0.0.1:7878/studio/tenants/tenant-a/catalog/admissions" \
  -H "content-type: application/json" \
  -d @catalog-admission.json
```

Example `catalog-admission.json` shape:

```json
{
  "tenantId": "tenant-a",
  "artifacts": [
    {
      "fileName": "foundry-runtime.jar",
      "sha256": "sha256:<artifact-sha>",
      "artifactBase64": "<base64-encoded JAR bytes>"
    },
    {
      "fileName": "workflow-runtime-claim.yaml",
      "sha256": "sha256:<claim-file-sha>",
      "claimYaml": "<pure DCP claim YAML or META-INF/unfurl-catalog.yaml contents>"
    }
  ]
}
```

Expected outcome:

- Valid claims are admitted into the tenant catalog with claim hash and artifact hash.
- Invalid claims return structured DCP diagnostics with severity, code, path, and message.
- Studio UI should render those diagnostics next to the uploaded claim.
- When at least one artifact is verified, Studio returns a hash-pinned `claimBundleArtifact` for downloading the
  resolved multi-file claim set.
- Studio also returns a catalog-admission diagnostic artifact so the exact upload result can be downloaded without
  unpacking the resolved claims ZIP.

For a multi-file upload, download the combined claim set from the returned `claimBundleArtifact.url`. This bundle keeps
each DCP claim as its own YAML file and adds an admission manifest plus diagnostics; it does not merge independent
component claims into one synthetic claim.

```bash
jq -r '.claimBundleArtifact.url' catalog-admission-response.json | xargs -I{} \
  curl -sS "http://127.0.0.1:7878{}" -o flowfoundry-claims.zip
```

## Step 3: Verify The Catalog

Fetch the tenant catalog and confirm all required capabilities are visible.

```bash
curl -sS "http://127.0.0.1:7878/studio/tenants/tenant-a/catalog" \
  -H "X-Unfurl-Tenant: tenant-a" \
  -o catalog.json
```

Check that the catalog contains offers for:

- `workflow.execute`
- `agent.run`
- `tool.call`
- `rag.search`
- `provider.call`
- authentication/authorization/audit/telemetry ports required by the deployment

Studio UI should show a download action for the catalog snapshot diagnostic artifact so this exact catalog view can be
attached to support tickets or replayed in CI.

## Phase 2: Assembly

## Step 4: Create A Studio Assembly

Create the design-time assembly that will hold the draft composition.

```bash
curl -sS -X POST "http://127.0.0.1:7878/studio/tenants/tenant-a/assemblies" \
  -H "content-type: application/json" \
  -H "X-Unfurl-Tenant: tenant-a" \
  -d '{
    "assemblyId": "flowfoundry-export",
    "targetApplicationName": "Flowfoundry Export",
    "defaultDeploymentTarget": "containerized-local"
  }' \
  -o assembly.json
```

The `assemblyId` is the stable tenant-scoped workspace identifier used by later needs, session,
layout, projection, and compile routes. `targetApplicationName` is the operator-facing label,
and `defaultDeploymentTarget` seeds later deployment resolution choices. Record the returned
`assemblyId`.

## Step 5: Extract Or Provide Needs

If needs are authored outside Studio, store the needs file beside the export workspace. If Studio is doing needs extraction, call:

```bash
curl -sS -X POST \
  "http://127.0.0.1:7878/studio/tenants/tenant-a/assemblies/<assemblyId>/needs/extract" \
  -H "content-type: application/json" \
  -H "X-Unfurl-Tenant: tenant-a" \
  -d @needs-extraction-request.json \
  -o needs-extraction-response.json
```

The needs must include the durable workflow and AI capabilities required by the workload. For an agent-backed flow, include a need for `workflow.execute` and a need for `agent.run`; model, RAG, tool, auth, telemetry, and secret concerns should appear as DCP dependencies or ports.
Studio derives those needs from supplied source metadata: `workflow.yaml` implies the Flow runtime need
`workflow.execute`, `*.agent.yaml` implies the Foundry need `agent.run`, and inline workflow content can add node
capabilities from `nodes[].uses`. A correct Flowfoundry extraction response therefore contains at least:

```yaml
requiredCapabilities:
  - capability: workflow.execute
    capabilityVersion: ^1
  - capability: agent.run
    capabilityVersion: ^1
```

This extraction seeds composition; it does not prove Flow or Foundry artifacts are already part of the assembly draft.
Verify draft membership after Step 9 by checking the session intent log or assembly snapshot for the accepted
`uploaded:unfurl-flow-...` and `uploaded:unfurl-foundry-...` catalog entry ids.

Download the needs diagnostic artifact from the response or UI before continuing; it preserves both the suggested
`needs.yaml` and the extraction warnings used to seed composition.

## Step 6: Start A Draft Session

Create a Studio draft session for the assembly.

```bash
curl -sS -X POST \
  "http://127.0.0.1:7878/studio/tenants/tenant-a/assemblies/<assemblyId>/sessions" \
  -H "content-type: application/json" \
  -H "X-Unfurl-Tenant: tenant-a" \
  -d '{
    "tenantId": "tenant-a",
    "assemblyId": "<assemblyId>",
    "baseCatalogHash": "",
    "needsId": "<needsId>",
    "trustPolicyId": "<trustPolicyId>",
    "initialCandidateId": "",
    "collaboratorId": "operator-1",
    "collaboratorName": "Operator"
  }' \
  -o session.json
```

Record the returned `sessionId` and current revision.

Download the draft-session diagnostic artifact so the starting revision, catalog hash, needs id, trust policy id, and
collaborator context are captured before intents are applied.

## Step 7: Build And Copy Foundry Authoring Tool JARs

Fabric authoring is serviced by Fabric through Foundry. The Fabric authoring agent definition, authoring prompt, and authoring ToolPlugin JARs belong to the Foundry deployment root, not to the Fabric catalog envelope.

Build the sample authoring tool module:

```bash
mvn -pl unfurl-foundry/sample-authoring-tools -am package
```

Copy the generated tool JAR into the Foundry deployment root:

```bash
mkdir -p unfurl-foundry/deployment/tools
cp unfurl-foundry/sample-authoring-tools/target/fabric-authoring-tools-sample-0.1.0-SNAPSHOT.jar \
  unfurl-foundry/deployment/tools/
```

PowerShell equivalent:

```powershell
New-Item -ItemType Directory -Force unfurl-foundry/deployment/tools
Copy-Item unfurl-foundry/sample-authoring-tools/target/fabric-authoring-tools-sample-0.1.0-SNAPSHOT.jar `
  unfurl-foundry/deployment/tools/
```

Expected generated deployment artifact:

```text
unfurl-foundry/deployment/tools/fabric-authoring-tools-sample-0.1.0-SNAPSHOT.jar
```

The tool JAR must declare `META-INF/services/com.unfurl.foundry.tools.ToolPlugin` and provide the tools referenced by `unfurl-foundry/deployment/agents/fabric-authoring.agent.yaml`, such as `fabric.catalog-query`, `fabric.needs-emitter`, and `fabric.intent-emitter`. For production authoring, replace the sample implementation with the deployment's real Foundry ToolPlugin JARs while preserving the same Foundry loading contract.

## Step 8: Use Foundry Authoring From Studio

Start Foundry's DCP server from a deployment root that contains the authoring agent and its substrate-backed tools/providers.

```bash
java -cp <foundry-classpath> com.unfurl.foundry.server.FoundryDcpServerLauncher \
  --port 7979 \
  --deployment-root /path/to/unfurl-foundry/deployment
```

Configure Fabric Studio to delegate authoring to Foundry:

```bash
set UNFURL_FOUNDRY_DCP_ENDPOINT=http://127.0.0.1:7979/dcp/agent.run
```

Then send authoring requests through Studio:

```bash
curl -sS -X POST "http://127.0.0.1:7878/studio/authoring/converse" \
  -H "content-type: application/json" \
  -d @authoring-request.json \
  -o authoring-response.json
```

Treat every authoring response as advisory. Apply only catalog-backed intents and run them back through validation and compilation.

For Flowfoundry, a top-level authoring proposal is not complete merely because it found providers for
`workflow.execute` and `agent.run`. If the request does not state the recursive Flow and Foundry
capability scope, the authoring response should be `kind=clarify` and ask which recursive
capabilities are expected, including Flow runtime closure, Foundry AI/runtime closure, provider
bindings, runtime/deployment profile, and cross-cutting concerns such as identity, audit,
telemetry, and secret configuration. Proceed to intent application only after the clarification
answers or a deterministic resolver can compute the full recursive DCP closure from policy.

## Step 9: Apply Composition Intents

Use session intent APIs to add, replace, or configure catalog-backed components. The exact intent payload depends on the UI action, but every request must include the session identity and base revision.

```bash
curl -sS -X POST \
  "http://127.0.0.1:7878/studio/tenants/tenant-a/assemblies/<assemblyId>/sessions/<sessionId>/intents" \
  -H "content-type: application/json" \
  -H "X-Unfurl-Tenant: tenant-a" \
  -d @intent.json \
  -o intent-response.json
```

Keep the returned revision. If the server reports a revision conflict, reload the session and replay the operator decision against the latest revision.

## Step 10: Inspect Dynamic DCP Projection

Use the dynamic DCP projection to verify the composition tree before compiling.

```bash
curl -sS \
  "http://127.0.0.1:7878/studio/tenants/tenant-a/assemblies/<assemblyId>/dynamic-dcp?sessionId=<sessionId>" \
  -H "X-Unfurl-Tenant: tenant-a" \
  -o dynamic-dcp.json
```

For runbook execution, always include the Step 6/Step 9 `sessionId`. Without it, the route returns a catalog browsing projection and may include components that are admitted to the tenant catalog but not part of the draft assembly.

For this assembly runbook, Step 10 is an environment-closure check, not a workload DAG check. The
draft projection must show exactly the selected catalog-backed runtime components for the integrated
Flow/Foundry environment:

- Flow runtime component for `workflow.execute`.
- Foundry runtime component for `agent.run`, `tool.call`, `rag.search`, `provider.call`, and `skill.invoke`.
- Foundry substrate engine, tools, RAG, and Spring AI adapter components.
- Environment and runtime leaf bindings that will be supplied later by runtime binding/deployment
  packaging, such as model provider beans, embedding provider beans, vector store, RAG corpus, tool
  implementation JARs, and signed workflow contract.

Do not require a concrete Flow workflow DAG or Foundry workload agent DAG before Step 11. Those DAG
definitions are deployable workloads that can be installed into the integrated environment after the
environment itself is assembled, compiled, resolved, and packaged.

Download the dynamic projection diagnostic artifact whenever graph behavior is surprising; it is the source of truth for
what the UI rendered at this step.

## Step 11: Resolve Containerized Deployment

Ask Studio to resolve deployment choices for the draft composition.

```bash
curl -sS -X POST "http://127.0.0.1:7878/studio/deployment/resolve" \
  -H "content-type: application/json" \
  -d '{
    "tenantId": "tenant-a",
    "assemblyId": "<assemblyId>",
    "sessionId": "<sessionId>",
    "autoSelectBest": true,
    "deploymentPolicy": {
      "preferredShapes": ["CONTAINERIZED_SERVICE", "SPRING_BOOT_SERVICE", "IN_PROCESS_LIBRARY"],
      "disallowedShapes": [],
      "requireIsolationForCapabilityPatterns": [],
      "runtime": {
        "javaVersion": "21",
        "springBoot": true,
        "kubernetes": true,
        "serviceMesh": true
      }
    }
  }' \
  -o deployment-resolve-response.json
```

For this runbook, assume the deployment policy selects containerized runtime shapes for product runtimes. Flow and Foundry product entries must resolve from their admitted `catalog.component_shape_profile` and should select `CONTAINERIZED_SERVICE` when that shape is supported by the catalog, substrate, and policy. Foundry Substrate jars, provider plugins, tools, RAG retrievers, and adapters may legitimately resolve as `IN_PROCESS_LIBRARY` when they are packaged inside the Foundry runtime service.

The response should identify deployable selections for:

- Fabric Studio control-plane service if included.
- Flow runtime service.
- Foundry runtime service.
- Model provider adapter container or in-process provider plugin.
- Embedding provider adapter.
- Vector store.
- RAG service or in-process retriever binding.
- Tool plugin bundles.
- Auth, authorization, audit, telemetry, and secret/config services or references.

## Phase 3: Export

## Step 12: Compile And Sign The Candidate

Compile the session candidate into export artifacts.

```bash
curl -sS -X POST \
  "http://127.0.0.1:7878/studio/tenants/tenant-a/assemblies/<assemblyId>/sessions/<sessionId>/compile" \
  -H "content-type: application/json" \
  -H "X-Unfurl-Tenant: tenant-a" \
  -d '{
    "expectedRevision": <latestRevision>,
    "sign": true
  }' \
  -o compile-response.json
```

The response contains:

- `contractArtifact`: unsigned root DCP composition contract. This is the light handoff view, not the full compiler
  envelope.
- `substrateProfileArtifact`: runtime/substrate profile.
- `signedContractArtifact`: signed/frozen root DCP contract handoff artifact when signing is enabled.
- `supportArtifacts`: required companion artifacts for deployment packaging, such as the signed compiled envelope and
  `dcp-runtime-bundle.zip`.
- `diagnosticArtifacts`: compiler/debug snapshots such as the unsigned compiled envelope and compile response.
- `warnings`: operator-visible export warnings.
- `expectedRevision` and `receivedRevision`: revision safety details.

The signed contract must contain a DCP contract closure: an aggregate parent contract whose
`metadata.extensions.contains` references child composition contracts for the selected Flow, Foundry,
provider, RAG, vector, tool, and substrate edges. A `bindingPlan` may be present for diagnostics, but it
is not a substitute for referenced child DCP contracts.

Do not deploy raw session state. The signed contract closure and runtime binding tree are the deployment handoff.

Download compile support artifacts needed by later deployment steps. Download diagnostics when support, replay, or
debugging is needed; diagnostics must not be the only source of the production handoff.

## Step 13: Download Export Artifacts Locally

Download every returned artifact by its `url` and verify the `sha256`.

```bash
mkdir -p exports/flowfoundry

jq -r '.contractArtifact.url' compile-response.json | xargs -I{} \
  curl -sS "http://127.0.0.1:7878{}" -o exports/flowfoundry/contract.yaml

jq -r '.substrateProfileArtifact.url' compile-response.json | xargs -I{} \
  curl -sS "http://127.0.0.1:7878{}" -o exports/flowfoundry/substrate-profile.yaml

jq -r '.signedContractArtifact.url' compile-response.json | xargs -I{} \
  curl -sS "http://127.0.0.1:7878{}" -o exports/flowfoundry/signed-contract.json

jq -r '.supportArtifacts[] | select(.url | contains("signed-compiled-contract.yaml")) | .url' compile-response.json | xargs -I{} \
  curl -sS "http://127.0.0.1:7878{}" -o exports/flowfoundry/signed-compiled-contract.yaml

jq -r '.supportArtifacts[] | select(.url | contains("dcp-runtime-bundle.zip")) | .url' compile-response.json | xargs -I{} \
  curl -sS "http://127.0.0.1:7878{}" -o exports/flowfoundry/dcp-runtime-bundle.zip
```

Compile artifact URLs are hash-pinned Studio export routes. A missing or mismatched `sha256` must fail the download
rather than returning mutable session state.

## Step 14: Create Runtime Bindings

Create a runtime binding file for the container environment from the signed compiled support envelope, substrate profile,
and deployment resolution response. The signed root DCP contract remains the deployment handoff; the signed compiled
support envelope is a Fabric tooling input that supplies selection and binding-plan context. This is the boundary where
Studio handoff ends and the deploy emitter/runtime package assembly begins.

The binding set must use DCP-native aggregation. Generate an aggregate parent runtime binding whose
`metadata.extensions.contains` references child runtime bindings for each resolved Flow, Foundry, and
substrate/provider/tool/RAG edge. Each child is a normal DCP runtime binding with its own child contract pin,
component instance, endpoint/config refs, secret refs, and runtime policy. Do not add product-specific
runtime-closure sections such as `flowfoundry_runtime`; recursive child references are the DCP construct
for aggregation.

The binding must reference:

- The signed contract id and version.
- Flow service endpoint for `workflow.execute`.
- Foundry service endpoint for `agent.run`.
- Provider, embedding, vector, RAG, and tool bindings.
- `SecretRef` values for API keys, provider credentials, signing keys, and auth material.
- `ConfigRef` values for non-secret runtime configuration.
- Runtime policy: enabled, timeout, telemetry namespace, audit enabled.

Do not inline secrets in the binding. Use secret/config references that the deployment environment resolves.
Missing child binding refs, containment cycles, or inline secret material in any child binding must block Step 14.

Generate the binding from the signed compiled support envelope, not by hand-copying ids:

```bash
fabric runtime-bindings \
  --signed-contract exports/flowfoundry/signed-compiled-contract.yaml \
  --tenant tenant-local \
  --environment local-dev \
  --flow-base-url http://flow:8080 \
  --foundry-base-url http://foundry:7979 \
  --out exports/flowfoundry/runtime-binding.yaml
```

## Phase 4: Deployment

## Step 15: Assemble Foundry Deployment Root

Create the Foundry deployment directory that will be mounted into the Foundry container.

```text
foundry-deployment/
  agents/
    fabric-authoring.agent.yaml
    workload-agent.agent.yaml
  prompts/
    fabric-authoring.md
    workload-agent.md
  tools/
    fabric-authoring-tools-sample-0.1.0-SNAPSHOT.jar
    domain-tool.jar
  providers/
    model-provider-plugin.jar
    embedding-provider-plugin.jar
  registries/
    providers.yaml
    tools.yaml
    skills.yaml
  runtime-binding.yaml
  signed-contract.json
  signed-compiled-contract.yaml
```

The authoring tool JAR under `tools/` is the deployment artifact produced in Step 7. It is loaded by Foundry's ToolPlugin loader and should not be uploaded as a Fabric catalog item.

Bind all Foundry Substrate surfaces through ports:

- `AgentRuntime`: Foundry durable runtime wrapping the substrate runner.
- `ProviderRegistry`: tenant-scoped model and embedding lookup.
- `ModelProvider`: concrete model adapter.
- `EmbeddingProvider`: concrete embedding adapter.
- `VectorStore`: concrete vector store.
- `RagRetriever`: retriever over vector store.
- `ToolRegistry` / `ToolExecutor`: deployment tool plugins.
- `CostGuardrail`: budget and quota decision port.
- `PermissionBridge`: authorization decision port.
- `AgentEventSink`: audit/telemetry event publication.

## Step 16: Assemble Flow Deployment Root

Create the Flow deployment directory that will be mounted into the Flow container.

```text
flow-deployment/
  workflows/
    workflow.yaml
  claims/
    unfurl-foundry.claim.yaml
  contracts/
    frozen/
      agent-run.frozen.json
  trust-keys/
    studio-public-key.pem
  signed-contract.json
  signed-compiled-contract.yaml
  runtime-binding.yaml
  substrate-profile.yaml
```

`signed-contract.json` and `substrate-profile.yaml` are the aggregate environment handoff artifacts.
Flow verifies the aggregate substrate profile hash against the signed contract, then scopes its
process-level boot compatibility to the local Flow runtime profile. Do not generate an unsigned
Flow-only contract sidecar to make strict boot pass; the aggregate signed DCP handoff remains the
proof of deployment closure.

For dynamic workflow capabilities such as `agent.run`, the Flow root must also include the
broker-consumable DCP runtime bundle: provider claims under `claims/`, frozen child contracts under
`contracts/frozen/`, and the matching child runtime binding in `runtime-binding.yaml`. Flow presents
the provider claim for the exact requested capability, accepts the matching frozen child contract,
and registers the resulting `ContractInvocable` as a Flow `NodeExecutor`. Provider claim identity
alone is not a valid lookup key because Foundry publishes multiple offers from one claim.
The Flow root must also include the public DCP trust-key directory used to verify those frozen child
contract signatures; the container receives it through `UNFURL_DCP_TRUST_KEYS_DIR`.

The preferred packaging path is `fabric emit` with the runtime handoff inputs:

```bash
fabric emit \
  --contract exports/flowfoundry/signed-compiled-contract.yaml \
  --profile exports/flowfoundry/substrate-profile.yaml \
  --runtime-binding exports/flowfoundry/runtime-binding.yaml \
  --dcp-runtime-bundle exports/flowfoundry/dcp-runtime-bundle.zip \
  --flow-workflows exports/flowfoundry/flow/workflows \
  --foundry-deployment-root exports/flowfoundry/foundry/foundry-deployment \
  --trust-keys exports/flowfoundry/trust-keys \
  --target local \
  --out exports/flowfoundry/deploy
```

For the local compose target this emits `deploy/flow-deployment/`, copies the signed contract,
substrate profile, runtime binding, and workflow files into the root, and safely extracts
`dcp-runtime-bundle.zip` into `claims/` and `contracts/frozen/`.

Flow should invoke Foundry through DCP or through registered Foundry Substrate node executors, not by importing Foundry internals.

For durable AI phases:

1. Flow owns durable phase routing and checkpoints.
2. Foundry owns reasoning inside the agent phase.
3. The outer budget envelope is passed in `ExecutionContext.metadata["outerBudgetRemainingUsd"]`.
4. Foundry applies the lower of the outer envelope and the agent budget policy.

## Step 17: Build Container Images

Build images for the runtime services. A minimal containerized export contains:

```text
flowfoundry-export/
  contracts/
    signed-contract.json
    runtime-binding.yaml
  fabric/
    substrate-profile.yaml
  flow/
    flow-deployment/
  foundry/
    foundry-deployment/
  trust-keys/
    studio-public-key.pem
  deploy/
    docker-compose.yaml
    k8s/
```

Container expectations:

- The Foundry image includes `unfurl-foundry`, `unfurl-foundry-substrate`, `unfurl-substrate`, and `unfurl-dcp`.
- The Flow image includes `unfurl-flow`, `unfurl-substrate`, `unfurl-dcp`, and any Foundry Substrate executor modules needed for AI capability registration.
- Provider SDKs are present only in provider adapter/plugin images or Foundry adapter packages.
- Secrets are mounted or injected by reference; they are not baked into images.

## Phase 5: API Documentation

## Step 18: Generate OpenAPI And Swagger UI

Generate API documentation from the frozen export, not from conversation state or hand-written endpoint lists. This
phase documents the API surface that operators and clients will use after deployment:

- Flow DCP endpoints, such as `workflow.execute`, `workflow.observe`, and `workflow.trigger`.
- Foundry DCP endpoints, such as `agent.run`, `tool.call`, `provider.call`, `rag.search`, and `skill.invoke`.
- Fabric Studio tool-gateway endpoints only when the authoring surface is part of the deployed environment.
- Health, readiness, and status endpoints that are intentionally exposed to operators.

Inputs:

- `signed-contract.json`.
- `runtime-binding.yaml`.
- `substrate-profile.yaml`.
- Deployment resolution and container manifest artifacts.
- Flow and Foundry route descriptors or generated route metadata.
- Foundry tool registry metadata for request/response schemas of exposed tools.

The generator must project OpenAPI from DCP contract/runtime-binding facts and service-owned route descriptors. It must
not infer request/response schemas from model output, logs, or live traffic. When a DCP capability lacks a request or
response schema, Step 18 must return a gap instead of emitting incomplete Swagger UI.

Expected outputs:

```text
exports/flowfoundry/openapi/flowfoundry-openapi.yaml
exports/flowfoundry/openapi/flow-openapi.yaml
exports/flowfoundry/openapi/foundry-openapi.yaml
exports/flowfoundry/swagger-ui/
target/flowfoundry-run/step-18-swagger-ui-generation-report.json
```

The Swagger UI bundle should be static and deployable inside the customer environment. Avoid CDN dependencies unless
the deployment policy explicitly allows them.

Block Step 18 when:

- Any public DCP endpoint is missing request or response schema metadata.
- A server URL cannot be derived from the runtime binding or deployment target.
- The generated OpenAPI contains unresolved references.
- Secret values, bearer tokens, API keys, prompts with sensitive data, raw model outputs, or retrieved RAG chunks appear
  in the OpenAPI examples or Swagger UI bundle.
- The generated docs describe endpoints that are not present in the signed contract/runtime-binding handoff.

## Step 19: Run The Containerized Deployment

Example service shape:

```text
services:
  flow:
    image: unfurl-flow:<version>
    mounts:
      - ./flow/flow-deployment:/opt/unfurl/flow
      - ./contracts:/opt/unfurl/contracts

  foundry:
    image: unfurl-foundry:<version>
    command:
      - com.unfurl.foundry.server.FoundryDcpServerLauncher
      - --bind
      - 0.0.0.0
      - --port
      - "7979"
      - --deployment-root
      - /opt/unfurl/foundry
    mounts:
      - ./foundry/foundry-deployment:/opt/unfurl/foundry
      - ./contracts:/opt/unfurl/contracts

  vector-store:
    image: <customer-vector-store>

  authz:
    image: <customer-policy-engine>

  telemetry:
    image: <customer-telemetry-sink>
```

In development, Fabric can call Foundry with:

```text
UNFURL_FOUNDRY_DCP_ENDPOINT=http://foundry:7979/dcp/agent.run
```

In production, this endpoint should be bound through the DCP transport security and governance ports, not a naked URL.
Foundry also requires `UNFURL_FOUNDRY_CREDENTIAL_KEY` (base64 16/24/32-byte AES key) for its
credential store. Local compose must read it from the operator environment or secret store; do not
inline the key in generated artifacts.

## Step 20: Verify Runtime

Run these checks after the containers start:

1. Foundry health:

   ```bash
   curl -sS http://127.0.0.1:7979/health
   ```

2. Fabric or deployment verifier validates the signed contract.
3. DCP runtime binding validator rejects inline secrets.
4. Flow can resolve `workflow.execute`.
5. Foundry can resolve `agent.run`.
6. Foundry provider registry resolves the configured model and embedding providers for the tenant.
7. Tool registry resolves every tool referenced by the agent and skills.
8. RAG retriever can query the vector store through the `VectorStore` port.
9. `PermissionBridge` denies a tool call when the user lacks permission.
10. `CostGuardrail` trips before an over-budget model call.
11. `AgentEventSink` emits metadata-only events with correlation id.

Optional workload DAG smoke test:

After the integrated environment is running, install a concrete Flow workflow and Foundry agent DAG and
project it through Fabric's generic DCP adapter to verify that recursive workload topology renders
correctly:

```bash
mvn -q -pl unfurl-foundry-substrate/foundry-substrate-offers -am -DskipTests package

mvn -q -pl unfurl-foundry-substrate/foundry-substrate-offers exec:java \
  -Dexec.mainClass=com.unfurl.foundry.substrate.offers.RecursiveProjectionRequestCli \
  -Dexec.args="--workflow workflow.yaml --agent workload-agent.agent.yaml --output recursive-projection-request.json"

curl -sS -X POST \
  "http://127.0.0.1:7878/studio/tenants/tenant-a/assemblies/<assemblyId>/dynamic-dcp/project" \
  -H "content-type: application/json" \
  -H "X-Unfurl-Tenant: tenant-a" \
  -d @recursive-projection-request.json \
  -o recursive-dcp.json
```

For a reusable smoke fixture, use `unfurl-fabric/docs/examples/flowfoundry-dag-test/`. This test must
not gate environment assembly; it only proves that an installed workload DAG can drill into Flow
workflow nodes, Foundry agent phases, prompts, model refs, RAG refs, and tools.

## Step 21: Promote The Export

Before promoting the export:

- Re-run contract verification against the production trust key set.
- Re-run catalog drift checks.
- Confirm all deployment image digests are pinned.
- Confirm all secret/config references resolve in the target environment.
- Confirm auth/authz/audit/telemetry ports are bound.
- Confirm OpenAPI and Swagger UI artifacts match the signed contract/runtime-binding handoff.
- Confirm no provider credentials, prompts with sensitive data, raw model outputs, or retrieved chunks are logged by default.

## Output Checklist

A complete Flowfoundry export contains:

- DCP claims for every component and cross-cutting concern.
- Compiled DCP composition contract.
- Signed contract handoff artifact.
- Runtime binding with `SecretRef` / `ConfigRef`, transport, telemetry, and audit policy.
- Substrate profile.
- Flow workflow definitions and runtime config.
- Foundry agent definitions, prompts, provider/tool/skill registry files, and plugin JARs.
- Container manifests for Flow, Foundry, vector/RAG, auth/authz, telemetry, and any provider adapter services.
- OpenAPI documents and a static Swagger UI bundle generated from signed contract/runtime-binding artifacts.
- Local SHA-256 verification for every downloaded artifact.
- Operator notes for warnings and accepted risk.

## Failure Handling

| Failure | Expected response |
|---|---|
| Claim validation fails | Fix claim YAML; do not admit the artifact. |
| Catalog lacks required offer | Add the missing component claim or dependency; do not compile around it. |
| Studio revision conflict | Reload session, replay intent against latest revision. |
| Deployment resolution rejects a shape | Change deployment policy or add a component that supports the required container shape. |
| Export artifact hash mismatch | Re-download and block promotion until the hash matches. |
| Runtime binding contains inline secret | Replace with `SecretRef` or `ConfigRef`. |
| Foundry provider missing | Fix provider registry/runtime binding before invoking `agent.run`. |
| Tool not authorized | Bind `PermissionBridge` policy or change the agent/tool permission scope. |
| Budget exceeded | Adjust agent budget/runtime envelope or reduce model/tool work. |
