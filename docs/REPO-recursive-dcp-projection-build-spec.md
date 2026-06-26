# Build Spec: Recursive DCP Projection Integration

## Status

The recursive projection capability belongs in `unfurl-dcp`, not Fabric.

DCP owns the protocol-level subtree model through `com.unfurl.dcp.projection`:

- `DcpProjectionProjector.project(DcpProjectionRequest)` walks a claim graph recursively through `CONTAINS` relationships.
- It supports arbitrary depth bounded by `maxDepth` and `maxNodes`.
- It guards cycles.
- It emits deterministic child ordering.
- It aggregates descendant claim URIs per node.
- It returns parent claim URI, depth, free-form level labels, offers, focus claim URI, edges, and warnings.
- Containment is sourced from claim `metadata.extensions`: `contains`, `children`, `containsClaimUris`, and `childClaimUris`.

Fabric owns only the Studio adapter:

- synthesize/load DCP claim docs for the current tenant and assembly;
- accept already-projected substrate claim maps from Flow/Foundry bridges;
- call `DcpProjectionProjector`;
- map `DcpProjection` to `StudioDynamicDcpProjection`;
- keep Studio-only replacement metadata separate from containment.

The frontend semantic zoom navigator is already depth-agnostic and walks `nodes` plus `CONTAINS` edges.

## Implemented Fabric Adapter

`StudioCatalogService.dynamicDcpProjection(tenantId, assemblyId)` now delegates hierarchy semantics to DCP:

1. Build a DCP claim repository from the Studio assembly/catalog read model.
2. Represent the Studio root and assembly as aggregate DCP claims.
3. Represent each catalog entry as a DCP claim.
4. Read nested child references from entry `dynamicComposition` metadata.
5. Call:

```java
new DcpProjectionProjector().project(new DcpProjectionRequest(
        currentClaim,
        claimsByUri,
        focusClaimUri,
        maxDepth,
        maxNodes));
```

6. Adapt `DcpProjection` back to `StudioDynamicDcpProjection`.

For non-catalog composition graphs, Fabric exposes the same adapter through:

```java
StudioCatalogService.dynamicDcpProjection(
        tenantId,
        assemblyId,
        rootClaimUri,
        focusClaimUri,
        claimsByUri);
```

and through the Studio HTTP route:

```text
POST /studio/tenants/{tenantId}/assemblies/{assemblyId}/dynamic-dcp/project
```

Request body:

```json
{
  "rootClaimUri": "urn:unfurl:flow:workflow:order-flow",
  "focusClaimUri": "urn:unfurl:flow:workflow:order-flow",
  "claimsByUri": {
    "urn:unfurl:flow:workflow:order-flow": { "...": "DCP Claim JSON" }
  }
}
```

This lets Flow/Foundry or any other substrate own domain-specific projection while Fabric owns only
the Studio DTO mapping.

## Mapping

| DCP projection | Studio projection |
|---|---|
| `DcpProjectionNode.claimUri` | Stable Studio `nodeId` via claim URI to node id mapping |
| `label` | `StudioDynamicDcpNode.label` |
| `dcpType` | `StudioDynamicDcpNode.dcpType` |
| `level` | `StudioDynamicDcpNode.level` |
| `parentClaimUri` | `StudioDynamicDcpNode.parentNodeId` |
| `depth` | `StudioDynamicDcpNode.depth` |
| `offers` | `StudioDynamicDcpNode.capabilities` |
| `DcpProjectionEdge` | `StudioDynamicDcpEdge` |

`compatibleDescendants` remains replacement-compatibility metadata. It must not be populated from `DcpProjectionNode.descendantClaimUris`, because that field is containment.

Substrate ports and port connection derivation remain Fabric/Studio concerns and continue to be computed from replacement-allowed catalog nodes.

## Wire Contract

`StudioDynamicDcpNode` now includes:

```java
String parentNodeId
Integer depth
```

The existing constructor remains for backward-compatible Java call sites.

The TypeScript `DynamicDcpNode` mirror is widened:

```ts
readonly level: string;
readonly parentNodeId?: string;
readonly depth?: number;
```

Existing `PARENT`, `ASSEMBLY`, and `CHILD` consumers remain valid.

## Data Prerequisite

Nesting appears only when claims declare containment.

Current compatibility bridge:

```text
ClaimMetadata.extensions.contains
ClaimMetadata.extensions.children
ClaimMetadata.extensions.containsClaimUris
ClaimMetadata.extensions.childClaimUris
```

Fabric package visual metadata can provide equivalent references through `dynamicComposition`, including catalog-entry references such as:

```json
{
  "dynamicComposition": {
    "level": "CITY",
    "dcpType": "CITY",
    "containsCatalogEntryIds": ["com.unfurl:colony:1.0.0"]
  }
}
```

Authoring the containment metadata is a data/claim-authoring prerequisite, not a frontend task.

## Verification

Focused verification:

```bash
cd unfurl-dcp
mvn -q -Dtest=DcpProjectionProjectorTest test

cd ../unfurl-fabric
mvn -q -Dtest=StudioCatalogServiceTest test
```

Expected behavior:

- flat assemblies still produce the legacy `PARENT -> ASSEMBLY -> CHILD` shape;
- nested claim metadata produces deeper `CONTAINS` edges;
- recursive nodes carry `parentNodeId` and `depth`;
- replacement candidates still use `compatibleDescendants`, not containment descendants.

## End-To-End Check

1. Author or seed claims with containment metadata, for example `city -> colony -> home -> component`.
2. Point Studio at that tenant and assembly.
3. Confirm the semantic zoom breadcrumb drills past `ASSEMBLY` and `CHILD` into deeper aggregates.
4. Confirm zoom-out and Top walk back up.
5. Confirm leaves show leaf state.
6. With `VITE_STUDIO_DEMO_FALLBACKS` off, confirm all depth comes from projection data.

## Other Composition Sources: Foundry Agents and Flow Workflows

Current status:

- `foundry-substrate-offers/FoundryClaimProjector` turns `AgentDefinition`, `SkillDefinition`, `ToolDefinition`, prompt refs, RAG refs, and model refs into DCP claims with `metadata.extensions.contains`.
- `foundry-substrate-offers/FlowClaimProjector` turns `WorkflowDefinition` and `NodeDefinition` into DCP claims with containment.
- Flow workflow claims contain node claims.
- Flow node claims contain their `uses` component, except `SUBGRAPH` nodes contain and recurse into sub-workflows.
- Flow nodes whose `uses` starts with `agent:` bridge to the shared `urn:unfurl:foundry:agent:<id>` URI.
- Merging Flow and Foundry claim maps gives one recursive graph:

```text
Flow Workflow -> Node -> Agent -> Skill -> Tool/Prompt/RAG/Model
```

Fabric does not need a compile-time dependency on either substrate projector. It accepts the merged DCP
claim map through the `dynamic-dcp/project` route, runs `DcpProjectionProjector`, and maps the result to
the existing Studio DTO.

### Historical Foundry-only notes

The following notes describe the design gap before `FoundryClaimProjector` and `FlowClaimProjector`
were added. They are retained only as rationale for why the projectors live in the substrate bridge.

A second composition graph exists in `foundry-substrate` and should drill in the same navigator:

```text
Foundry -> Agent -> { Skill -> {Tool, Prompt, RAG, Model}, Tool, Prompt, Model } -> ...
```

The reference data is present in the domain model:

- `AgentDefinition` -> `toolRefs`, `skillRefs`, `defaultModelRef`, `phases[]`
- `AgentPhase` -> `promptTemplateRef`, `modelRef`, `allowedToolRefs`, `ragQueryRef`, `skillRefs`
- `SkillDefinition` -> `toolRefs`, `promptFragmentRef`, `ragSourceRefs`, `defaultModelRef`

### Claim-metadata status (confirmed): NOT present today

These types are **not** DCP claims and carry **no** claim/containment metadata:

- `AgentDefinition` / `ToolDefinition` expose only a free-form `Map<String,Object> metadata`; `SkillDefinition` / `AgentPhase` hold string refs. None have DCP `ClaimMetadata`, none populate `extensions.contains`.
- The only existing DCP bridge is `foundry-substrate-offers/AiOffers`, which expresses capabilities as DCP **Offers** (`agent.run`, `tool.call`, `rag.search`, `provider.call`, `skill.invoke`) — the capability surface, not containment.
- `ClaimProjector.toClaim(...)` emits `ClaimMetadata(..., Map.of())` — empty extensions; it does not derive containment.

So the containment **mechanism** exists, but **no substrate populates it**. The references must be turned into claim `contains` metadata by an adapter.

### Approach (option 1): synthesize claims with containment from the refs

Mirror the existing catalog adapter (`StudioCatalogService.claimForEntry` + `EXT_CONTAINS`):

1. Build a claim per Foundry node — Agent, Skill, Tool, Prompt, Model — keyed by a stable URI
   (e.g. `urn:unfurl:foundry:agent:<id>`, `:tool:<name>`, `:skill:<id>`, `:prompt:<ref>`, `:model:<ref>`).
2. Set each claim's `metadata.extensions`:
   - `level` / `dcpType` = `FOUNDRY` / `AGENT` / `SKILL` / `TOOL` / `PROMPT` / `MODEL`;
   - `contains` (i.e. `DcpProjectionProjector.EXT_CONTAINS`) = the resolved child URIs from the refs:
     Agent contains its `toolRefs` + `skillRefs` + `defaultModelRef` + per-phase `promptTemplateRef` /
     `modelRef` / `allowedToolRefs` / `ragQueryRef`; Skill contains its `toolRefs` / `promptFragmentRef` /
     `ragSourceRefs` / `defaultModelRef`.
   - Keep `AiOffers` capabilities as the claim's `offers` (capability surface, separate from containment).
3. A `FOUNDRY` root claim contains the Agent claims it deploys.
4. Feed those claims to the **existing** `DcpProjectionProjector` (resolve refs through the foundry
   tool/skill/prompt registries to confirm they're loaded, then add the URIs). Map the resulting
   `DcpProjection` to `StudioDynamicDcpProjection` exactly as the catalog adapter does.

Result: Foundry agent composition and catalog/DCP composition share **one** recursive projection and the
**same** depth-agnostic navigator — **no frontend change**. Guards (cycle / `maxDepth` / `maxNodes`) and
ordering come for free from the projector. This is a backend adapter + the per-type ref→`contains`
mapping; the domain types themselves need no change (the adapter reads their existing refs).
