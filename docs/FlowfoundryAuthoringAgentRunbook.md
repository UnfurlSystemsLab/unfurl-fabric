# Flowfoundry Authoring Agent Runbook

This runbook defines how the Fabric authoring agent should guide and execute the first 17 Flowfoundry export steps.
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

Steps 18-20 remain runtime run, verification, and promotion gates. The authoring agent can prepare their inputs, but it
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

- `phase`: one of `catalog-creation`, `assembly`, `export`, `deployment`.
- `step`: the current runbook step number.
- `assistantMessage`: concise operator-facing status.
- `questions`: unanswered phase questions, when `kind=clarify`.
- `toolCalls`: the tool names and inputs the agent will execute or has executed.
- `artifacts`: generated artifacts with path, SHA-256 when available, and consumer step.
- `gap`: blocking diagnostics when `kind=gap`.

The agent must not invent catalog ids, needs, contract ids, runtime-binding ids, endpoints, or hashes. Those values must
come from tool outputs.

## Current Tools

The deployment authoring agent already has these Foundry ToolPlugin tools:

| Tool | Current purpose |
|---|---|
| `fabric.catalog-query` | Resolve admitted catalog entries by offered capability from invocation catalog payload. |
| `fabric.needs-emitter` | Emit deterministic needs YAML for selected capabilities. |
| `fabric.intent-emitter` | Emit Studio `ADD_COMPONENT` intents for selected catalog entry ids. |

These are enough to propose catalog-backed Assembly intents. They are not enough to execute all 17 runbook steps. The
phase execution tools below should be added as Fabric-owned ToolPlugin wrappers over Studio APIs, Fabric CLI commands,
and deployment emitter operations.

## Phase Tool Set

| Tool | Backing operation | Output artifact |
|---|---|---|
| `fabric.artifact-inventory` | Inspect expected files and compute local existence/hash metadata. | `step-01-artifact-inventory.json` |
| `fabric.catalog-admit` | `POST /studio/tenants/{tenant}/catalog/admissions` | `step-02-catalog-admission-response.json`, claim bundle |
| `fabric.catalog-verify` | `GET /studio/tenants/{tenant}/catalog` and capability scan | `step-03-capability-verification.json` |
| `fabric.assembly-create` | `POST /studio/tenants/{tenant}/assemblies` | `step-04-assembly-response.json` |
| `fabric.needs-extract` | `POST /studio/tenants/{tenant}/assemblies/{assembly}/needs/extract` | `step-05-needs-extraction-response.json`, `step-05-needs.yaml` |
| `fabric.session-start` | `POST /studio/tenants/{tenant}/assemblies/{assembly}/sessions` | `step-06-draft-session-response.json` |
| `fabric.authoring-tooljar-build` | `mvn -pl unfurl-foundry/sample-authoring-tools -am package` and copy to deployment root | `step-07-build-summary.json` |
| `fabric.authoring-converse` | `POST /studio/authoring/converse` backed by Foundry `agent.run` | `step-08-authoring-response.json` |
| `fabric.session-intent-apply` | `POST /studio/tenants/{tenant}/assemblies/{assembly}/sessions/{session}/intents` | `step-09-session-after.json` |
| `fabric.dynamic-dcp-project` | `GET /studio/tenants/{tenant}/assemblies/{assembly}/dynamic-dcp?sessionId=...` | `step-10-dynamic-dcp.json` |
| `fabric.deployment-resolve` | `POST /studio/deployment/resolve` in Studio session mode | `step-11-deployment-resolve-response.json` |
| `fabric.candidate-compile` | `POST /studio/tenants/{tenant}/assemblies/{assembly}/sessions/{session}/compile` | `step-12-compile-response.json` |
| `fabric.export-download` | `GET /studio/tenants/{tenant}/exports/{artifact}/content?sha256=...` | downloaded contract/profile/signed-contract artifacts |
| `fabric.runtime-binding-generate` | `fabric runtime-bindings --signed-contract ...` | `step-14-runtime-binding.yaml`, validation report |
| `fabric.foundry-root-assemble` | Copy agent, prompt, tool/provider jars, registries, signed contract, runtime binding | `step-15-foundry-deployment-inventory.json` |
| `fabric.flow-root-assemble` | Copy workflows, DCP child contracts, runtime binding refs, trust keys | `step-16-flow-deployment-inventory.json` |
| `fabric.container-image-build` | Build Flow and Foundry images from deployment roots | `step-17-container-image-validation.json` |

Tool wrappers must return structured status: `PASS`, `GAP`, or `ERROR`. `GAP` means the runbook should stop and the
agent should ask the operator or engineer for a design decision. `ERROR` means the tool failed unexpectedly and should
include logs.

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
- Are cross-cutting claims optional for this development run or required for production readiness?
- Should deployment resolution prefer local Docker Compose, Kubernetes, or another target?

### Tool flow

| Step | Tool call | Input artifact | Output artifact | Stop condition |
|---:|---|---|---|---|
| 4 | `fabric.assembly-create` | verified catalog snapshot | `step-04-assembly-response.json` | Assembly cannot be created. |
| 5 | `fabric.needs-extract` or `fabric.needs-emitter` | source bundle or operator answers | `step-05-needs.yaml` | Needs omit `workflow.execute` or `agent.run` for Flowfoundry. |
| 6 | `fabric.session-start` | assembly response + needs | `step-06-draft-session-response.json` | Session cannot be created. |
| 7 | `fabric.authoring-tooljar-build` | Foundry authoring tool source | `step-07-build-summary.json` | Tool JAR missing `ToolPlugin` service or required tools. |
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

### Tool flow

| Step | Tool call | Input artifact | Output artifact | Stop condition |
|---:|---|---|---|---|
| 12 | `fabric.candidate-compile` | deployment-resolved session | `step-12-compile-response.json` | Compile fails, signature missing when required, or artifact ids/hashes are absent. |
| 13 | `fabric.export-download` | compile response artifact URLs | `step-13-download-verification.json` | Any artifact download hash mismatches. |
| 14 | `fabric.runtime-binding-generate` | signed contract + substrate profile + deployment resolution | `step-14-runtime-binding.yaml` | Inline secret, missing child binding ref, containment cycle, or invalid runtime binding. |

The Phase 3 exit artifact is a signed contract plus DCP runtime-binding set. Phase 4 must consume those files directly.

## Phase 4: Deployment

### Questions

Ask these before assembling roots and images:

- Which Foundry agent definitions, prompts, tool JARs, provider plugins, and registries should be included?
- Which Flow workflows, child contracts, trust keys, and runtime binding refs should be mounted?
- Which image tags should be built for Flow and Foundry?
- Should local Docker image build be performed now, or should the runbook emit image build instructions only?

### Tool flow

| Step | Tool call | Input artifact | Output artifact | Stop condition |
|---:|---|---|---|---|
| 15 | `fabric.foundry-root-assemble` | signed contract + runtime binding + Foundry deployment inputs | `step-15-foundry-deployment-inventory.json` | Missing agent, prompt, tool/provider plugin, registry, or signed contract. |
| 16 | `fabric.flow-root-assemble` | signed contract + runtime binding + Flow workflow inputs | `step-16-flow-deployment-inventory.json` | Missing workflow, child DCP contract refs, trust keys, or runtime binding ref. |
| 17 | `fabric.container-image-build` | Flow and Foundry deployment roots | `step-17-container-image-validation.json` | Image build fails or image lacks required deployment-root files. |

The Phase 4 exit artifact is a local container-image validation report. Runtime execution should continue with Step 18
only after this report passes.

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

## Implementation Notes

The existing `fabric.catalog-query`, `fabric.needs-emitter`, and `fabric.intent-emitter` tools should remain the
proposal tools used inside the authoring phase. The additional phase tools should be implemented as Fabric-owned
ToolPlugin wrappers and registered in Foundry's deployment root, with permission scopes that match their authority:

| Permission | Tools |
|---|---|
| `fabric.catalog.read` | `fabric.catalog-query`, `fabric.catalog-verify` |
| `fabric.catalog.write` | `fabric.catalog-admit` |
| `fabric.assembly.write` | `fabric.assembly-create`, `fabric.session-start`, `fabric.session-intent-apply` |
| `fabric.needs.propose` | `fabric.needs-emitter`, `fabric.needs-extract` |
| `fabric.export.write` | `fabric.candidate-compile`, `fabric.export-download`, `fabric.runtime-binding-generate` |
| `fabric.deployment.write` | `fabric.foundry-root-assemble`, `fabric.flow-root-assemble`, `fabric.container-image-build` |

Foundry must enforce these permission scopes through `PermissionBridge`; Fabric should revalidate every write through
Studio/API checks even after Foundry authoring succeeds.
