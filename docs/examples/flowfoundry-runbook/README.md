# Flowfoundry Runbook Flow Example

This example shows the intended execution construct for the Flowfoundry runbook:

- Flow owns phase/subtree execution through `subgraph.execute`.
- Studio tool calls are normal Flow `http.request` nodes.
- `tool.result.gate` stops the subgraph when a tool returns `GAP` or `ERROR`.
- Foundry is called only by Studio authoring/reasoning steps, not as the runbook executor.

The included catalog and assembly phase examples are concrete for Studio-owned steps because those tools are
implemented by the Studio tool gateway today. Export, deployment, and documentation examples use explicit
deployment-runner `function.local` bindings for filesystem/Docker/OpenAPI work that does not belong inside Studio.
Those handlers must return the same ToolCallResult-compatible envelope consumed by `tool.result.gate`.

Files:

- `flowfoundry-export-runbook.workflow.yaml`: parent workflow that invokes every build phase subgraph.
- `flowfoundry-catalog-runbook.workflow.yaml`: smaller parent workflow that invokes only the catalog phase subgraph.
- `flowfoundry-catalog-creation.subgraph.yaml`: Steps 1-3 as executable Flow nodes.
- `flowfoundry-assembly.subgraph.yaml`: Steps 4-11 as executable Flow nodes.
- `flowfoundry-export.subgraph.yaml`: Steps 12-14, with deployment-runner hooks for download aggregation and runtime binding generation.
- `flowfoundry-deployment.subgraph.yaml`: Steps 15-17 deployment-root and image assembly hooks.
- `flowfoundry-api-documentation.subgraph.yaml`: Step 18 Swagger/OpenAPI generation hook.
- `flowfoundry-runbook-profile.yaml`: local runtime profile with the required Flow components enabled.

The embedding host must register the catalog phase definition under `flowfoundry-catalog-creation@1.0.0` and provide a
`function.local` handler named `fabric.artifact-inventory` for Step 1. It must also register the other phase subgraphs
when using `flowfoundry-export-runbook.workflow.yaml`.

Required deployment-runner function handlers:

| Handler | Step | Responsibility |
|---|---:|---|
| `fabric.artifact-inventory` | 1 | Inventory local files and write the Step 1 artifact. |
| `fabric.authoring-tool-registry-verify` | 7 | Verify Foundry proposal tools are loaded and scoped. |
| `fabric.export-download-all` | 13 | Download all compile/export artifacts and verify hashes. |
| `fabric.runtime-binding-generate` | 14 | Generate DCP runtime bindings from signed handoff artifacts. |
| `fabric.foundry-root-assemble` | 15 | Assemble the Foundry deployment root. |
| `fabric.flow-root-assemble` | 16 | Assemble the Flow deployment root. |
| `fabric.container-image-build` | 17 | Build and validate local runtime images. |
| `fabric.swagger-ui-generate` | 18 | Generate OpenAPI documents and a static Swagger UI bundle. |

Steps 2-6 and 8-12 call the local Studio backend at `http://127.0.0.1:7878`.
