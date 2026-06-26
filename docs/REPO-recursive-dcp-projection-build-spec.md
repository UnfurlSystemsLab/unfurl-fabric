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
