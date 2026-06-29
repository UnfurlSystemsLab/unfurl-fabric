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

## Step 1: Upload Foundry And Substrate Claims

Foundry and the Foundry Substrate modules already publish or carry DCP claim material for their capability surfaces. For this flow, the operator should collect the existing Foundry, Foundry Substrate, Flow, and application artifact files and upload them through the Studio UI so Fabric can add them to the tenant catalog after DCP validation.

Upload the artifact files that represent the complete deployment capability surface:

1. Foundry product claim and deployment files exposing `agent.run`, and optionally `tool.call`, `rag.search`, and `provider.call`.
2. Foundry Substrate artifacts for the AI domain, ports, offers, engine, prompt, tools, RAG, resolver, serialization, adapters, and testing fixtures used by the deployment.
3. Flow runtime claim exposing `workflow.execute` for durable workflow execution.
4. Application or workload component artifacts.
5. Model provider or model gateway adapter artifacts.
6. Embedding provider adapter artifacts.
7. Vector store and RAG retriever artifacts.
8. Tool plugin artifacts for every tool the agent may call.
9. Authentication, authorization, audit, telemetry, and secrets/config provider artifacts where production deployment needs them.

### Files To Add To The Catalog

Add these files through the Studio catalog UI, or upload the matching DCP claim YAML for each file when the artifact does not already embed a catalog manifest. JAR uploads should carry `META-INF/unfurl-catalog.yaml`; YAML uploads should be pure DCP `Claim` YAML or the Fabric catalog-manifest envelope with a top-level `claim` block.

Foundry product and deployment artifacts:

| File | Catalog role |
|---|---|
| `unfurl-foundry/target/unfurl-foundry-0.1.0-SNAPSHOT.jar` | Foundry runtime product artifact exposing the Foundry DCP claim and `agent.run` host capability. |
| `unfurl-foundry/deployment/agents/fabric-authoring.agent.yaml` | Default Fabric authoring agent definition loaded by Foundry. |
| `unfurl-foundry/deployment/prompts/fabric-authoring.md` | Prompt material referenced by the authoring agent. |
| `unfurl-foundry/deployment/tools/fabric-authoring-tools-sample-0.1.0-SNAPSHOT.jar` | Sample deployment tool plugin bundle for catalog query, needs emission, and intent emission tools. |
| `unfurl-foundry/deployment/providers/*.jar` | Provider plugin bundles when using deployment-supplied provider adapters. |
| `unfurl-foundry/deployment/tools/*.jar` | Additional deployment tool plugin bundles required by the agent. |

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
| `unfurl-foundry-substrate/foundry-substrate-springai-adapter/target/foundry-substrate-springai-adapter-0.1.0-SNAPSHOT.jar` | Optional Spring AI adapter when a deployment supplies Spring AI model, embedding, or vector bindings. |
| `unfurl-foundry-substrate/foundry-substrate-testing/target/foundry-substrate-testing-0.1.0-SNAPSHOT.jar` | Test/dev fixtures only; add to catalog only for local demos, examples, or non-production validation flows. |

Flow, application, and runtime-support artifacts:

| File | Catalog role |
|---|---|
| `unfurl-flow/target/*.jar` or the Flow runtime package | Flow runtime component exposing `workflow.execute`. |
| Application/workload component package files | Deployable application components selected by the composition. |
| Model provider adapter package files | Concrete `ModelProvider` bindings, such as Anthropic, Gemini, OpenAI, Azure OpenAI, Ollama, or customer adapters. |
| Embedding provider adapter package files | Concrete `EmbeddingProvider` bindings. |
| Vector store adapter or service claim files | Concrete `VectorStore` binding for the RAG pipeline. |
| RAG retriever adapter or service claim files | Concrete `RagRetriever` binding when RAG is deployed as a separate capability. |
| Authentication provider claim file | Verified identity source represented as a DCP port/runtime binding. |
| Authorization or policy-engine claim file | Authorization decision owner represented as a DCP port/runtime binding and mapped to Foundry `PermissionBridge`. |
| Audit sink claim file | Audit event destination represented as a DCP port/runtime binding. |
| Telemetry sink claim file | Metrics/traces destination represented as a DCP port/runtime binding. |
| Secret/config provider claim file | Secret and config reference provider used by runtime bindings. |

Represent cross-cutting concerns through DCP constructs:

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
      "fileName": "foundry-agent.jar",
      "sha256": "sha256:<artifact-sha>",
      "artifactBase64": "<base64-encoded JAR bytes>"
    },
    {
      "fileName": "foundry-agent.yaml",
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

## Step 4: Create A Studio Assembly

Create the design-time assembly that will hold the draft composition.

```bash
curl -sS -X POST "http://127.0.0.1:7878/studio/tenants/tenant-a/assemblies" \
  -H "content-type: application/json" \
  -H "X-Unfurl-Tenant: tenant-a" \
  -d '{"name":"flowfoundry-export"}' \
  -o assembly.json
```

Record the returned `assemblyId`.

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

## Step 7: Use Foundry Authoring From Studio

Start Foundry's DCP server from a deployment root that contains the authoring agent and its substrate-backed tools/providers.

```bash
java -cp <foundry-classpath> com.unfurl.foundry.runtime.FoundryDcpServerLauncher \
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

## Step 8: Apply Composition Intents

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

## Step 9: Inspect Dynamic DCP Projection

Use the dynamic DCP projection to verify the composition tree before compiling.

```bash
curl -sS \
  "http://127.0.0.1:7878/studio/tenants/tenant-a/assemblies/<assemblyId>/dynamic-dcp" \
  -H "X-Unfurl-Tenant: tenant-a" \
  -o dynamic-dcp.json
```

Check that the projection drills into:

- Flow workflow nodes.
- Foundry agent nodes.
- Agent phases, tools, prompts, RAG, model providers, embedding providers, and vector store refs.
- DCP ports for auth/authz/audit/telemetry/secrets where required.

## Step 10: Resolve Containerized Deployment

Ask Studio to resolve deployment choices for the draft composition.

```bash
curl -sS -X POST "http://127.0.0.1:7878/studio/deployment/resolve" \
  -H "content-type: application/json" \
  -d @deployment-resolve-request.json \
  -o deployment-resolve-response.json
```

For this runbook, assume the deployment policy selects containerized runtime shapes. The response should identify deployable selections for:

- Fabric Studio control-plane service if included.
- Flow runtime service.
- Foundry runtime service.
- Model provider adapter container or in-process provider plugin.
- Embedding provider adapter.
- Vector store.
- RAG service or in-process retriever binding.
- Tool plugin bundles.
- Auth, authorization, audit, telemetry, and secret/config services or references.

## Step 11: Compile And Sign The Candidate

Compile the session candidate into export artifacts.

```bash
curl -sS -X POST \
  "http://127.0.0.1:7878/studio/tenants/tenant-a/assemblies/<assemblyId>/sessions/<sessionId>/compile" \
  -H "content-type: application/json" \
  -H "X-Unfurl-Tenant: tenant-a" \
  -d '{
    "candidateId": "<candidateId>",
    "baseRevision": <latestRevision>,
    "sign": true
  }' \
  -o compile-response.json
```

The response contains:

- `contractArtifact`: unsigned DCP composition contract.
- `substrateProfileArtifact`: runtime/substrate profile.
- `signedContractArtifact`: signed contract handoff artifact when signing is enabled.
- `warnings`: operator-visible export warnings.
- `expectedRevision` and `receivedRevision`: revision safety details.

Do not deploy raw session state. The signed contract and runtime binding are the deployment handoff.

## Step 12: Download Export Artifacts Locally

Download every returned artifact by its `url` and verify the `sha256`.

```bash
mkdir -p exports/flowfoundry

jq -r '.contractArtifact.url' compile-response.json | xargs -I{} \
  curl -sS "http://127.0.0.1:7878{}" -o exports/flowfoundry/contract.yaml

jq -r '.substrateProfileArtifact.url' compile-response.json | xargs -I{} \
  curl -sS "http://127.0.0.1:7878{}" -o exports/flowfoundry/substrate-profile.yaml

jq -r '.signedContractArtifact.url' compile-response.json | xargs -I{} \
  curl -sS "http://127.0.0.1:7878{}" -o exports/flowfoundry/signed-contract.json
```

Current implementation note: `StudioCompileDraftCandidateResponse` returns artifact metadata and URLs. If the export-content route is not wired in the running server yet, persist the same logical artifacts from the CLI/service layer and keep the file names above. The deployment package should not depend on Studio session internals.

## Step 13: Create Runtime Bindings

Create a runtime binding file for the container environment.

The binding must reference:

- The signed contract id and version.
- Flow service endpoint for `workflow.execute`.
- Foundry service endpoint for `agent.run`.
- Provider, embedding, vector, RAG, and tool bindings.
- `SecretRef` values for API keys, provider credentials, signing keys, and auth material.
- `ConfigRef` values for non-secret runtime configuration.
- Runtime policy: enabled, timeout, telemetry namespace, audit enabled.

Do not inline secrets in the binding. Use secret/config references that the deployment environment resolves.

## Step 14: Assemble Foundry Deployment Root

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
    catalog-query-tool.jar
    needs-emitter-tool.jar
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
```

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

## Step 15: Assemble Flow Deployment Root

Create the Flow deployment directory that will be mounted into the Flow container.

```text
flow-deployment/
  workflows/
    workflow.yaml
  signed-contract.json
  runtime-binding.yaml
  substrate-profile.yaml
```

Flow should invoke Foundry through DCP or through registered Foundry Substrate node executors, not by importing Foundry internals.

For durable AI phases:

1. Flow owns durable phase routing and checkpoints.
2. Foundry owns reasoning inside the agent phase.
3. The outer budget envelope is passed in `ExecutionContext.metadata["outerBudgetRemainingUsd"]`.
4. Foundry applies the lower of the outer envelope and the agent budget policy.

## Step 16: Build Container Images

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
  deploy/
    docker-compose.yaml
    k8s/
```

Container expectations:

- The Foundry image includes `unfurl-foundry`, `unfurl-foundry-substrate`, `unfurl-substrate`, and `unfurl-dcp`.
- The Flow image includes `unfurl-flow`, `unfurl-substrate`, `unfurl-dcp`, and any Foundry Substrate executor modules needed for AI capability registration.
- Provider SDKs are present only in provider adapter/plugin images or Foundry adapter packages.
- Secrets are mounted or injected by reference; they are not baked into images.

## Step 17: Run The Containerized Deployment

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
      - com.unfurl.foundry.runtime.FoundryDcpServerLauncher
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

## Step 18: Verify Runtime

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

## Step 19: Promote The Export

Before promoting the export:

- Re-run contract verification against the production trust key set.
- Re-run catalog drift checks.
- Confirm all deployment image digests are pinned.
- Confirm all secret/config references resolve in the target environment.
- Confirm auth/authz/audit/telemetry ports are bound.
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
