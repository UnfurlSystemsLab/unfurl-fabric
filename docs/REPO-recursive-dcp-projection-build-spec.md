# Build Spec: Recursive DCP Projection

## Context

Unfurl Studio's redesign adds a semantic-zoom navigator that lets an operator drill into aggregates to arbitrary depth. The product vision is:

```text
Smart City -> Smart Colony -> Smart Home -> components in a home
```

That means aggregates of aggregates with no fixed level count.

The frontend is already depth-agnostic. `SemanticZoomNavigator.tsx` derives the tree from the projection's `nodes` plus `CONTAINS` edges and renders exactly the depth the data provides. It has honest leaf and empty states and does not fabricate nodes. It will support deeper composition as soon as the backend emits deeper projection data.

This document is the backend gap analysis and build spec to make the data recursive. Nothing here is implemented yet.

## Gap Analysis

The dynamic DCP projection is fixed at exactly three levels and is non-recursive.

`StudioCatalogService.dynamicDcpProjection(tenantId, assemblyId)` currently builds:

- one `PARENT` node with `dcpType=COMPANY`;
- one `ASSEMBLY` node with `dcpType=MODULE`;
- many `CHILD` nodes for catalog components;
- `CONTAINS` edges only from `PARENT` to `ASSEMBLY` and from `ASSEMBLY` to `CHILD`.

Current model limits:

- `StudioDynamicDcpNode.level` defaults to `CHILD`.
- Consumers treat `level` as the closed set `PARENT`, `ASSEMBLY`, `CHILD`.
- The TypeScript mirror `DynamicDcpNode.level` in `fabric-validation-client/src/index.ts` is currently a three-value union.
- `compatibleDescendants` is replacement-compatibility metadata used by `replacementCandidates`; it is not containment and must not be reused for nesting.
- There is no parent pointer, depth value, or notion of an aggregate that contains aggregates.

Net result: a `CHILD` can never contain anything, so the semantic-zoom navigator bottoms out at depth 3 with real data.

## Target

Support a recursive composition model where any node may contain child nodes to arbitrary depth, expressed as a DAG of `CONTAINS` edges.

The projection should be able to represent:

```text
City -> Colony -> Home -> component -> port -> claim
```

or any other operator-defined nesting.

## Build Spec

### 1. Node Model

Update `StudioDynamicDcpNode` and the TypeScript `DynamicDcpNode`.

- Generalize `level` from the fixed enum to a free-form `String` depth label, such as `ROOT`, `AGGREGATE`, `LEAF`, or a domain label like `CITY`, `COLONY`, `HOME`.
- Keep `PARENT`, `ASSEMBLY`, and `CHILD` accepted for backward compatibility.
- Add nullable `parentNodeId`.
- Add optional integer `depth` for fast breadcrumb and indent rendering without re-walking edges.
- In TypeScript, widen `level` to `string`.
- In TypeScript, add `readonly parentNodeId?: string` and `readonly depth?: number`.

The navigator can use `parentNodeId` and `depth`, but does not require them because it can walk `CONTAINS` edges.

### 2. Projection Builder

Replace the hardcoded one-parent, one-assembly, many-children emission in `StudioCatalogService.dynamicDcpProjection`.

The builder should:

- walk the real composition source recursively;
- emit a node for each aggregate or component;
- emit one `CONTAINS` edge from parent to child;
- recurse when a child is itself a composition;
- use `rootNodeId` for the top of the requested scope;
- use `focusNodeId` for the requested focus, which may be at any depth.

The composition source should be assemblies that reference sub-assemblies or nested DCPs. Where a component is itself a composition, the builder should recurse into it.

### 3. Guards

The recursive builder must protect the endpoint.

- Track a visited set to prevent cycles.
- Cap maximum depth.
- Cap total node count.
- Make bounds configurable.
- Emit deterministic ordering with stable sorts so layouts remain stable.

### 4. Containment Versus Compatibility

Keep responsibilities separate:

- `CONTAINS` edges are the only nesting signal.
- `compatibleDescendants` remains replacement-candidate metadata.
- `replacementAllowed` and replacement-candidate logic should resolve per node regardless of depth.

Do not overload `compatibleDescendants` for hierarchy.

### 5. API And Wire Compatibility

The response shape stays mostly unchanged:

```text
StudioDynamicDcpProjection -> DynamicDcpProjectionResponse
```

Only node typing changes:

- `level` widens from a three-value enum to string.
- `parentNodeId` is added as optional.
- `depth` is added as optional.

Existing three-level consumers keep working because `PARENT`, `ASSEMBLY`, and `CHILD` remain valid values.

The 3D `ThreeAssemblyScene` is not required to change for this backend slice. It reads `CHILD` nodes for modules; deeper nodes are additional projection entries it may ignore.

The semantic-zoom navigator is the recursive consumer.

### 6. Tests

Add or update tests for:

- nested composition producing `nodes` and `CONTAINS` edges deeper than 3 levels;
- cycle guard behavior;
- depth cap behavior;
- total node cap behavior;
- deterministic ordering;
- JSON round trip with `parentNodeId` and `depth`;
- flat assembly backward compatibility that still yields the legacy `PARENT` -> `ASSEMBLY` -> `CHILD` shape.

## Verification

Once the backend lands:

1. Point Studio at a tenant and assembly with nested composition.
2. Confirm the semantic-zoom breadcrumb drills past `ASSEMBLY` and `CHILD` into deeper aggregates.
3. Confirm zoom out walks back up.
4. Confirm leaf nodes show the leaf state.
5. Confirm the Component Hierarchy card lists deeper nodes.
6. Run with `VITE_STUDIO_DEMO_FALLBACKS` off and confirm all depth comes only from real projection data.

No frontend changes should be required for the semantic-zoom navigator if the backend emits the recursive shape described above.
