# Fabric Studio deployment folder

This folder holds the **client-supplied** artifacts the Fabric Studio authoring agent runs over.
Nothing here is Fabric or foundry source — the client owns and versions it.

```
deployment/
  agents/
    fabric-authoring.agent.yaml   # the authoring agent DAG (a foundry-substrate AgentDefinition)
  prompts/
    fabric-authoring.md           # system prompt referenced by the DAG's promptTemplateRef
  tools/
    *.jar                         # client-supplied tool JARs, discovered by reflection
```

## Tools (`tools/*.jar`)

Each tool JAR is a **self-contained** plugin the client builds and drops in. It must:

1. Implement `com.unfurl.fabric.studio.authoring.AuthoringTool` (a no-arg constructor; `name()` +
   `executor()` returning a foundry-substrate `ToolExecutor`).
2. Declare its tools via standard Java SPI:
   `META-INF/services/com.unfurl.fabric.studio.authoring.AuthoringTool`.
3. Depend only on the `AuthoringTool` SPI and the neutral `foundry-substrate-ports` `ToolExecutor`
   port — never on Fabric or foundry internals.

Fabric's `DeploymentToolLoader` scans this folder, loads each JAR in an isolated class loader, and
discovers the tools with `ServiceLoader` — Fabric never compiles against the tool implementations.
The authoring DAG references tools by their `name()` in each phase's `allowedToolRefs`.

The authoring agent expects three tools (supply them as one or more JARs):

| Tool name | Role |
|---|---|
| `fabric.catalog-query` | Resolve an admitted catalog entry offering a requested capability (grounding) |
| `fabric.needs-emitter` | Emit the `suggestedNeedsYaml` for a chosen target + capability |
| `fabric.intent-emitter` | Emit the Studio `ADD_COMPONENT` intents for a resolved catalog entry |

A **reference implementation** of all three lives in `../sample-authoring-tools/` — copy it as the
template for your own tools. Build and drop the JAR in here with:

```bash
cd ../sample-authoring-tools && mvn -o package
cp target/fabric-authoring-tools-sample-*.jar ../deployment/tools/
```

Grounding is defence-in-depth: the `fabric.catalog-query` tool keeps the agent honest, **and** Fabric
re-validates every proposed `ADD_COMPONENT` against the admitted catalog before returning a proposal
— an unadmitted component becomes a gap regardless of what the model or tools produced.

## Agent DAG (`agents/fabric-authoring.agent.yaml`)

A foundry-substrate `AgentDefinition`: a static DAG of phases joined by conditional edges, run on
the neutral `EmbeddedAgentRuntime` with the customer-configured `ModelProvider`. The model decides
**clarify / propose / gap**; the grounded tools re-check capabilities against the catalog and emit
the needs spec + intents. A capability the catalog does not offer is forced to a gap.
