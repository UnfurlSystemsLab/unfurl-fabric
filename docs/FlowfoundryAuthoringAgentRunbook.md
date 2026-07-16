# Flowfoundry Authoring Agent Runbook

This runbook defines how the Fabric authoring agent should guide and execute the first 18 Flowfoundry export steps.
The agent is advisory until Fabric validates each artifact through Studio, CLI, DCP, and deployment tooling.

The authoring agent must be phase-gated:

1. Ask only the questions needed for the current phase.
2. Execute the phase tools only after the required answers are present.
3. Persist every tool result as a hash-pinned artifact in the run workspace.
4. Feed each artifact into the next step instead of reconstructing state from conversation text.
5. Stop on the first blocking gap and return the gap artifact.

The canonical build phases are:

| Phase | Steps | Goal |
|---|---:|---|
| Catalog Creation | 1-3 | Admit and verify all DCP catalog inputs. |
| Assembly | 4-11 | Create the Studio draft, ask recursive-scope questions, apply validated intents, and resolve deployment shape. |
| Export | 12-14 | Compile, sign, download, verify hashes, and generate DCP runtime bindings. |
| Deployment | 15-17 | Assemble Foundry and Flow deployment roots and build runtime container images. |
| API Documentation | 18 | Generate OpenAPI documents and a static Swagger UI bundle from the signed export handoff. |

Steps 19-21 remain runtime run, verification, and promotion gates. The authoring agent can prepare their inputs, but it
must not claim the deployment is complete until those gates pass.

## Agent Contract

The authoring agent response must be one of:

| Kind | Meaning |
|---|---|
| `clarify` | More input is required before the next tool call is safe. |
| `gap` | A blocking gap was found. Include artifact links and the exact failed check. |
| `proposal` | The next tool calls are ready and grounded in admitted state. |
| `execution` | A tool call completed and produced artifacts for the next step. |

Every response must include:

- `phase`: one of `catalog-creation`, `assembly`, `export`, `deployment`, `api-documentation`.
- `step`: the current runbook step number.
- `assistantMessage`: concise operator-facing status.
- `questions`: unanswered phase questions, when `kind=clarify`.
- `toolCalls`: the tool names and inputs the agent will execute or has executed.
- `artifacts`: generated artifacts with path, SHA-256 when available, and consumer step.
- `gap`: blocking diagnostics when `kind=gap`.

The agent must not invent catalog ids, needs, contract ids, runtime-binding ids, endpoints, or hashes. Those values must
come from tool outputs.

## Foundry Tool Construct

The authoring agent must use Foundry's normal agent/tool constructs:

- The agent declares logical tool names in `toolRefs` and phase `allowedToolRefs`.
- Foundry resolves those names through its tenant-scoped `ToolRegistry`.
- Concrete execution is supplied by a `ToolExecutor` binding. Valid binding types include deployment `pluginJar`
  tools loaded by Foundry and HTTP tools that call Fabric Studio or Fabric-owned execution endpoints.
- The agent never implements catalog, assembly, export, deployment, Docker, filesystem, or CLI behavior locally.
- Runbook execution phases must return `kind=execution` only after a declared tool has completed. If the active
  model provider does not expose native tool/function calls, the model may request tools with the structured
  `{"toolCalls":[{"id":"...","toolName":"...","arguments":{...}}]}` envelope; Foundry still executes those calls
  through the governed `ToolRegistry` and passes the real tool result back before the terminal execution JSON.

The deployment authoring agent already names these proposal tools:

| Tool | Current purpose |
|---|---|
| `fabric.catalog-query` | Resolve admitted catalog entries by offered capability from invocation catalog payload. |
| `fabric.needs-emitter` | Emit deterministic needs YAML for selected capabilities. |
| `fabric.intent-emitter` | Emit Studio `ADD_COMPONENT` intents for selected catalog entry ids. |

These are enough to propose catalog-backed Assembly intents. They are not enough to execute all 17 runbook steps.
The phase execution tools below should be added as Foundry `ToolRegistry` entries. Use `pluginJar` bindings for
in-process deployment tools and HTTP bindings for calls to Fabric Studio or a Fabric-owned execution endpoint. A
plugin JAR is valid when the tool logic belongs in the customer deployment root and is loaded by Foundry; an HTTP tool
is preferred when the operation must cross into Fabric Studio, a deployment runner, Docker, or another governed
service boundary. In both cases, Foundry should see a tool name, permission scope, binding type, request schema,
response schema, timeout, and any secret/config refs.

## Flow Tool Execution Decision

Flow-side tool execution must use DCP hydration for product deployments. The authoring agent must not wire Flow
directly to Foundry's internal `ToolRegistry` or `ToolExecutor` implementations.

The distinction is:

- `toolRef` is a definition reference. It resolves a pinned Foundry `ToolDefinition` for schema, version,
  provenance, and audit context.
- `uses: tool.call` is an executable Flow capability. It is runnable only after Flow hydrates an accepted DCP child
  contract for the `tool.call` capability.

When a workflow contains a node that uses `tool.call`, the generated deployment artifacts must include:

1. A provider claim that offers `tool.call`, normally from Foundry or another tool-provider component.
2. A frozen child DCP contract binding Flow's `tool.call` need to that provider offer.
3. A `runtime-binding.yaml` entry for the child contract, with the provider endpoint or runtime instance reference.
4. Flow deployment-root placement of the child claim, frozen contract, runtime binding, trust keys, and workflow file.

Flow then registers `tool.call` through `DcpDeploymentHydrator` and `FlowCapabilityRegistrar`, which installs a
contract-backed Flow `NodeExecutor`. The Foundry-side provider remains responsible for resolving the actual tool name
through its `ToolRegistry` and invoking the bound `ToolExecutor`.

The Foundry-substrate `ToolExecutorNodeExecutor` adapter remains valid for co-packaged embedded hosts and tests, but
it is not the Flowfoundry product deployment path.

## Phase Tool Set

| Tool | Foundry binding | Backing operation | Output artifact |
|---|---|---|---|
| `fabric.artifact-inventory` | `pluginJar` or HTTP tool | Deployment-local inventory tool or Fabric execution endpoint inspects expected files and computes local existence/hash metadata. | `step-01-artifact-inventory.json` |
| `fabric.catalog-admit` | HTTP tool | `POST /studio/tenants/{tenant}/catalog/admissions` | `step-02-catalog-admission-response.json`, claim bundle |
| `fabric.catalog-verify` | HTTP tool | `GET /studio/tenants/{tenant}/catalog` and capability scan | `step-03-capability-verification.json` |
| `fabric.assembly-create` | HTTP tool | `POST /studio/tenants/{tenant}/assemblies` | `step-04-assembly-response.json` |
| `fabric.needs-extract` | HTTP tool | `POST /studio/tenants/{tenant}/assemblies/{assembly}/needs/extract` | `step-05-needs-extraction-response.json`, `step-05-needs.yaml` |
| `fabric.session-start` | HTTP tool | `POST /studio/tenants/{tenant}/assemblies/{assembly}/sessions` | `step-06-draft-session-response.json` |
| `fabric.authoring-tool-registry-verify` | `pluginJar`, HTTP tool, or Foundry management/status tool | Verify Foundry deployment root contains the required tool registry bindings. | `step-07-tool-registry-verification.json` |
| `fabric.authoring-converse` | HTTP tool or direct Studio route | `POST /studio/authoring/converse` backed by Foundry `agent.run` | `step-08-authoring-response.json` |
| `fabric.session-intent-apply` | HTTP tool | `POST /studio/tenants/{tenant}/assemblies/{assembly}/sessions/{session}/intents` | `step-09-session-after.json` |
| `fabric.dynamic-dcp-project` | HTTP tool | `GET /studio/tenants/{tenant}/assemblies/{assembly}/dynamic-dcp?sessionId=...` | `step-10-dynamic-dcp.json` |
| `fabric.deployment-resolve` | HTTP tool | `POST /studio/deployment/resolve` in Studio session mode | `step-11-deployment-resolve-response.json` |
| `fabric.candidate-compile` | HTTP tool | `POST /studio/tenants/{tenant}/assemblies/{assembly}/sessions/{session}/compile` | `step-12-compile-response.json` |
| `fabric.export-download` | HTTP tool | `GET /studio/tenants/{tenant}/exports/{artifact}/content?sha256=...` | downloaded handoff and support artifacts: root contract, profile, signed root contract, signed compiled envelope, runtime bundle |
| `fabric.runtime-binding-generate` | `pluginJar` or HTTP tool | Deployment-local tool or Fabric execution endpoint wraps `fabric runtime-bindings --signed-contract ...` using the signed compiled support envelope. | `step-14-runtime-binding.yaml`, validation report |
| `fabric.foundry-root-assemble` | `pluginJar` or HTTP tool | Deployment-local tool or Fabric execution endpoint assembles Foundry deployment root from signed root contract, runtime binding, support envelope, agent, prompts, registries, and plugins. | `step-15-foundry-deployment-inventory.json` |
| `fabric.flow-root-assemble` | `pluginJar` or HTTP tool | Deployment-local tool or Fabric execution endpoint assembles Flow deployment root from signed root contract, workflows, child DCP contracts for `agent.run` / `tool.call`, runtime binding refs, and trust keys. | `step-16-flow-deployment-inventory.json` |
| `fabric.container-image-build` | `pluginJar` or HTTP tool | Deployment-local tool or Fabric/deployment execution endpoint builds Flow and Foundry images from deployment roots. | `step-17-container-image-validation.json` |
| `fabric.swagger-ui-generate` | `pluginJar` or HTTP tool | Deployment-local tool or Fabric/deployment execution endpoint projects OpenAPI from the signed contract, runtime binding, substrate profile, service route descriptors, and Foundry tool schemas, then emits a static Swagger UI bundle. | `step-18-swagger-ui-generation-report.json`, OpenAPI files, Swagger UI directory |

All tool bindings must return structured status: `PASS`, `GAP`, or `ERROR`. `GAP` means the runbook should stop and the
agent should ask the operator or engineer for a design decision. `ERROR` means the binding failed unexpectedly and
should include logs.

## Phase 1: Catalog Creation

### Questions

Ask these before Step 1 if absent from invocation context:

- Which tenant id should own the catalog?
- Which files from `Files To Add To The Catalog` should be admitted for this run?
- Are auth, authorization, audit, telemetry, and secret/config claims in scope now, or optional for this run?
- Are Fabric authoring agent YAML, prompt Markdown, and authoring tool JARs intentionally excluded from catalog admission?

### Tool flow

| Step | Tool call | Input artifact | Output artifact | Stop condition |
|---:|---|---|---|---|
| 1 | `fabric.artifact-inventory` | operator file list | `step-01-artifact-inventory.json` | Required catalog file missing. |
| 2 | `fabric.catalog-admit` | artifact inventory | `step-02-catalog-admission-response.json` | Any required claim is rejected with DCP `ERROR`. |
| 3 | `fabric.catalog-verify` | catalog admission response | `step-03-capability-verification.json` | Required capability missing from admitted catalog. |

The Phase 1 exit artifact is the verified tenant catalog snapshot. Phase 2 must consume that snapshot and must not rely
on a model-generated catalog list.

## Phase 2: Assembly

### Questions

Ask these before applying intents:

- What assembly name/id should be used?
- Is needs input supplied as a file/source bundle, or should Studio extract needs?
- Which recursive Flow capabilities are required for this integrated environment?
- Which recursive Foundry capabilities are required? For the current Flowfoundry run, tools and RAG must be explicit
  when needed.
- Which model provider, embedding provider, vector store, tool bundles, and runtime profile should be used?
- Do any Flow workflow nodes call tools directly with `uses: tool.call`, and if so which provider claim should satisfy
  the `tool.call` child DCP contract?
- Are cross-cutting claims optional for this development run or required for production readiness?
- Should deployment resolution prefer local Docker Compose, Kubernetes, or another target?

### Tool flow

| Step | Tool call | Input artifact | Output artifact | Stop condition |
|---:|---|---|---|---|
| 4 | `fabric.assembly-create` | verified catalog snapshot | `step-04-assembly-response.json` | Assembly cannot be created. |
| 5 | `fabric.needs-extract` or `fabric.needs-emitter` | source bundle or operator answers | `step-05-needs.yaml` | Needs omit `workflow.execute` or `agent.run` for Flowfoundry. |
| 6 | `fabric.session-start` | assembly response + needs | `step-06-draft-session-response.json` | Session cannot be created. |
| 7 | `fabric.authoring-tool-registry-verify` | Foundry deployment root | `step-07-tool-registry-verification.json` | Missing required `pluginJar` or HTTP tool binding for any required authoring tool. |
| 8 | `fabric.authoring-converse` | catalog snapshot + needs + recursive-scope answers | `step-08-authoring-response.json` | Agent returns `clarify` or `gap`; do not apply intents. |
| 9 | `fabric.session-intent-apply` | proposal intents | `step-09-session-after.json` | Any intent is rejected or revision is stale. |
| 10 | `fabric.dynamic-dcp-project` | session after intents | `step-10-dynamic-dcp.json` | Projection does not match draft inventory or recursive DCP closure. |
| 11 | `fabric.deployment-resolve` | session + needs + deployment policy | `step-11-deployment-resolve-response.json` | Resolution returns `NO_MATCH` or misses Flow/Foundry runtime components. |

The Phase 2 exit artifact is a deployment-resolved Studio draft session. Step 12 must compile from the session, not from
the last candidate pointer or model output.

## Phase 3: Export

### Questions

Ask these before compiling and signing:

- Should Studio sign the contract in this run?
- Which signing key reference or environment-provided key should Studio use?
- Where should downloaded artifacts be stored?
- What Flow and Foundry service base URLs should be written into runtime bindings?
- Which provider, embedding, vector, RAG, tool, auth, telemetry, audit, and secret/config refs should be represented
  as DCP runtime-binding child refs?
- Which workflow-required `tool.call` capabilities require Flow child contracts, and what provider endpoint should each
  runtime binding target?

### Tool flow

| Step | Tool call | Input artifact | Output artifact | Stop condition |
|---:|---|---|---|---|
| 12 | `fabric.candidate-compile` | deployment-resolved session | `step-12-compile-response.json` | Compile fails, signature missing when required, or artifact ids/hashes are absent. |
| 13 | `fabric.export-download` | compile response artifact URLs | `step-13-download-verification.json` | Any artifact download hash mismatches. |
| 14 | `fabric.runtime-binding-generate` | signed compiled support envelope + substrate profile + deployment resolution | `step-14-runtime-binding.yaml` | Inline secret, missing child binding ref, containment cycle, or invalid runtime binding. |

The Phase 3 exit artifact is a signed root DCP contract plus DCP runtime-binding set. The compile response may also carry
support artifacts such as the signed compiled envelope and `dcp-runtime-bundle.zip`; those are required when a downstream
tool needs Fabric compiler context or Flow runtime hydration. Diagnostic artifacts are replay/debug files only.

## Phase 4: Deployment

### Questions

Ask these before assembling roots and images:

- Which Foundry agent definitions, prompts, tool JARs, provider plugins, and registries should be included?
- Which Flow workflows, child contracts for `agent.run` / `tool.call`, trust keys, and runtime binding refs should be
  mounted?
- Which image tags should be built for Flow and Foundry?
- Should local Docker image build be performed now, or should the runbook emit image build instructions only?

### Tool flow

| Step | Tool call | Input artifact | Output artifact | Stop condition |
|---:|---|---|---|---|
| 15 | `fabric.foundry-root-assemble` | signed root contract + runtime binding + signed compiled support envelope + Foundry deployment inputs | `step-15-foundry-deployment-inventory.json` | Missing agent, prompt, tool/provider plugin, registry, or signed contract. |
| 16 | `fabric.flow-root-assemble` | signed root contract + runtime binding + Flow workflow inputs | `step-16-flow-deployment-inventory.json` | Missing workflow, `agent.run` / `tool.call` child DCP contract refs, trust keys, or runtime binding ref. |
| 17 | `fabric.container-image-build` | Flow and Foundry deployment roots | `step-17-container-image-validation.json` | Image build fails or image lacks required deployment-root files. |

The Phase 4 exit artifact is a local container-image validation report. API documentation should continue with Step 18
only after this report passes.

## Phase 5: API Documentation

### Questions

Ask these before generating OpenAPI and Swagger UI:

- Which API surfaces should be documented: Flow DCP endpoints, Foundry DCP endpoints, Fabric Studio tool gateway, or
  all deployed operator/client endpoints?
- Should generated server URLs target local Docker Compose, Kubernetes service DNS, or external ingress URLs?
- Are Swagger UI artifacts allowed to use CDN assets, or must the bundle be fully static and offline?
- Which authentication schemes should be represented as OpenAPI security schemes from DCP auth/runtime-binding refs?

### Tool flow

| Step | Tool call | Input artifact | Output artifact | Stop condition |
|---:|---|---|---|---|
| 18 | `fabric.swagger-ui-generate` | signed contract + runtime binding + substrate profile + deployment resolution + route/tool schemas | `step-18-swagger-ui-generation-report.json`, OpenAPI files, Swagger UI directory | Missing endpoint/schema metadata, unresolved OpenAPI refs, inline secrets, sensitive examples, or docs that describe endpoints absent from the signed handoff. |

The Phase 5 exit artifact is a hash-pinned API documentation bundle. Runtime execution should continue with Step 19
only after the generation report passes.

## Artifact Ledger

Every tool call must append an entry to the run ledger:

```json
{
  "phase": "assembly",
  "step": 9,
  "tool": "fabric.session-intent-apply",
  "status": "PASS",
  "inputArtifacts": ["step-08-authoring-response.json"],
  "outputArtifacts": [
    {
      "path": "target/flowfoundry-run/step-09-session-after.json",
      "sha256": "<hash>",
      "consumedByStep": 10
    }
  ],
  "diagnostics": []
}
```

The ledger is the source of truth for restart and support. Conversation history can explain decisions, but the next
step must consume ledger artifacts.

## Required Agent Behavior

- Ask phase questions before tool execution when a required answer is missing.
- Reuse earlier answers from conversation only when they are explicit and unambiguous.
- Stop when a tool returns `GAP`; do not proceed to later steps.
- Return `proposal` only for tool calls that are ready to execute.
- Return `execution` after a tool call completes and include the artifact ledger update.
- Treat Foundry model output as advisory. Fabric tools and Studio endpoints decide validity.
- Keep secrets out of artifacts. Use `SecretRef` and `ConfigRef`; never echo raw keys.
- Use DCP child contracts/runtime-binding child refs for aggregation; do not introduce product-specific runtime closure blocks.

## Current Deployment Status

The Flowfoundry deployment should satisfy the Foundry tool-loading part of this design as follows:

- `foundry-deployment/registries/tools.yaml` declares `pluginJar` metadata for
  `fabric-authoring-tools-sample-0.1.0-SNAPSHOT.jar`.
- `AgentRunBootstrap` hydrates `registries/tools.yaml` through Foundry's deployment tool-registry loader.
- `pluginJar` bindings resolve through `DeploymentToolLoader`; HTTP bindings resolve through Foundry's generic HTTP
  `ToolExecutor` adapter.
- The deployed Foundry server exposes `/health`, `/dcp/agent.run`, and `/status/tools`.

The authoring agent should call `/status/tools` during Step 7 and return a `gap` if any required authoring or phase
tool is absent, bound to the wrong type, or missing the expected tenant scope. A development run may use the sample
Java plugin JAR for the three proposal tools, but runbook-compliant phase execution still requires every needed tool to
be represented as a Foundry registry binding.

The deployment `fabric-authoring` agent must route explicit runbook execution requests before proposal generation. For
Step 2 it uses a route phase to detect catalog admission, then runs an `execute-catalog-admission` phase whose
`allowedToolRefs` contains `fabric.catalog-admit`. The phase output is mapped deterministically from the Foundry
tool-output map (`$.tools.step-02-catalog-admit.output`) and returns `kind=execution` only after Foundry executes the
tool; otherwise it returns `gap`.

Studio-backed phase tools are exposed through Fabric's Foundry-compatible HTTP gateway:

```text
POST /studio/tools/{toolName}
```

This route accepts the canonical Foundry tool-call JSON shape and delegates to the existing Studio API/service methods.
It currently covers the Studio-owned tools for catalog admission/verification, assembly/session creation, needs
extraction, authoring converse, intent application, Dynamic DCP projection, deployment resolution, candidate compile,
and export download. Deployment-local tools that need filesystem, runtime-binding, deployment-root, OpenAPI/Swagger UI,
Docker, or Kubernetes authority remain separate `pluginJar` or HTTP bindings owned by a deployment runner.

## Implementation Notes

The existing `fabric.catalog-query`, `fabric.needs-emitter`, and `fabric.intent-emitter` tools should remain the
proposal tool names used inside the authoring phase. The additional phase tools should be registered as Foundry tool
definitions with `pluginJar` or HTTP bindings and permission scopes that match their authority:

| Permission | Tools |
|---|---|
| `fabric.catalog.read` | `fabric.catalog-query`, `fabric.catalog-verify` |
| `fabric.catalog.write` | `fabric.catalog-admit` |
| `fabric.assembly.write` | `fabric.assembly-create`, `fabric.session-start`, `fabric.session-intent-apply` |
| `fabric.needs.propose` | `fabric.needs-emitter`, `fabric.needs-extract` |
| `fabric.export.write` | `fabric.candidate-compile`, `fabric.export-download`, `fabric.runtime-binding-generate` |
| `fabric.deployment.write` | `fabric.foundry-root-assemble`, `fabric.flow-root-assemble`, `fabric.container-image-build` |
| `fabric.documentation.write` | `fabric.swagger-ui-generate` |

Foundry must enforce these permission scopes through `PermissionBridge`; Fabric should revalidate every write through
Studio/API checks even after Foundry authoring succeeds.

The Foundry-side product path is a registry-driven binding loader/status path, not a Fabric-specific local
implementation:

1. Define the deployment-root tool binding schema for `pluginJar` and HTTP bindings, including jar path or endpoint,
   headers/config refs, secret refs, request schema, response schema, timeout, and permission scope.
2. Keep `pluginJar` loading through Foundry's deployment loader and add a generic Foundry `ToolExecutor` adapter for
   HTTP tool bindings in Foundry product code, not in the substrate.
3. Hydrate `registries/tools.yaml` as the authoritative tool registry during `AgentRunBootstrap`.
4. Expose a safe status/diagnostic endpoint or artifact so Step 7 can verify loaded tool bindings.
