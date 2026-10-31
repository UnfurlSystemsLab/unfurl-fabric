# HLD Addendum — Reusable Composite DCP Contracts and Catalog Layout

**Status:** Proposed architecture decision; no runtime or schema change approved by this document.
**Scope:** Reusable Flow-only, Foundry-only, Flow + Foundry, and capability-constrained composites.
**Related:** `docs/HLD-unfurl-fabric.md`, `docs/Flowfoundry.md`, `docs/REPO-recursive-dcp-projection-build-spec.md`, and the authoritative `unfurl-dcp` schema.

## Problem and ownership

A composite DCP is a **reusable component**, not just a customer deployment template. It has a stable identity, declared offers/needs/refusals, child compatibility constraints, release provenance, and conformance tests. It may be realized through several deployment shapes, subject to its capability and resource guarantees.

Fabric is the design-time **admission, composition, deployment resolution, compilation, signing, and verification** authority. It need not own the source of every reusable composite. Component authors own individual claims; a composite publisher owns the composite source definition and its release lifecycle. Fabric catalogs/adopts approved releases.

Existing Fabric documentation already supports deployment shape selection (`IN_PROCESS_LIBRARY`, `MODULAR_MONOLITH_MODULE`, `STANDALONE_JAVA_APP`, `SPRING_BOOT_SERVICE`, `REMOTE_MICROSERVICE`, `CONTAINERIZED_SERVICE`, `MANAGED_EXTERNAL_ADAPTER`). Do not introduce a parallel deployment taxonomy.

## Proposed identity and versioning

Keep these separate:

1. **Logical component identity:** stable DCP-compatible URI, e.g. `urn:unfurl:assembly:flow-foundry-agentic-workflow`. Verify URI format against the authoritative DCP schema before adopting.
2. **Contract version:** externally observable capability/behavior compatibility version, e.g. `1.0.0`; changing implementation dependencies alone need not imply a breaking contract.
3. **Assembly release:** independently versioned, immutable set of component artifacts and resolved dependencies.
4. **Capability profile:** required/optional/forbidden capability constraints, e.g. `read-only-agent`, `agent-with-tools`, `workflow-with-approvals`; model with existing DCP vocabulary, not an invented schema.
5. **Deployment realization:** resolved shape, substrate profile, environment bindings, secrets references, and trust policy; not part of the stable logical name.

Do **not** encode Flow, Foundry, Substrate, and every capability version into a human-readable component name. Pin exact versions and hashes in the release manifest and frozen compiled contract. A change to externally visible guarantees requires appropriate contract versioning. A release must be reproducible.

## Reusable combinations

- `flow-only`: Flow capabilities without a Foundry runtime requirement.
- `foundry-only`: Foundry agent capabilities without a Flow runtime requirement.
- `flow-foundry`: governed cross-product composition with explicit child contracts and ports.
- Constrained profiles: e.g. Foundry with no RAG or no tools. Distinguish **optional** (absence accepted) from **forbidden** (presence rejected); required capability absence is a validation failure.

Avoid a combinatorial proliferation of hand-authored contract files. Start with a small set of tested profiles and generate/compile immutable contract artifacts from admitted source claims. An optional feature is not automatically a separate composite identity.

## Source layout proposal (not yet an adopted storage API)

Before creating a new top-level directory, audit existing Fabric catalog storage and export layout. `docs/Flowfoundry.md` already describes catalog admission and signed export; no separate top-level `contracts/README.md` or `assemblies/README.md` was found in the paths checked.

If the Fabric repository is selected as the initial source workspace, use:

```text
assemblies/
  flow-only/
    <composite-name>/
      definition.yaml
      profiles/
      tests/
  foundry-only/
    <composite-name>/
      definition.yaml
      profiles/
      tests/
  flow-foundry/
    <composite-name>/
      definition.yaml
      profiles/
      tests/
```

The names above describe a proposed organizational layout, **not** a new DCP wire format. Schema and file names must be reconciled with current DCP tooling before creating production manifests. Compiled, signed, and environment-specific artifacts must be generated into separate release/export destinations, not manually edited in source folders. A future independent assembly package/repository can retain the same layout if justified by independent release ownership.

## Validation and acceptance criteria

1. Validate each source claim and recursive child contract against current DCP schema and trust policy.
2. Resolve required/optional/forbidden capabilities; reject unmet requirements and forbidden offers.
3. Pin dependency versions/hashes, substrate profile, and signed compiled-contract provenance.
4. Test the same logical composite against supported deployment shapes; fail admission when a shape cannot satisfy its guarantees.
5. Verify signature and drift detection, negative binding cases, and reproducibility of exports.
6. Run a real Flow–Foundry read-only Kotak Neo vertical slice as a reusable integration/regression scenario, with offline fixtures and separately authorized live smoke tests.
7. Do not claim full deployment support solely because a shape is enumerated; record evidence per shape.

## Decision gates

- Confirm existing Fabric directory/catalog conventions and whether definitions belong in Fabric source or an independent publisher package.
- Align identity, profile, and release fields with the current `unfurl-dcp` schemas; avoid parallel identity/versioning models.
- Agree who publishes composite source, who signs compiled output, and how releases are promoted.
- Only then add directories and concrete contract definitions, beginning with the minimal `flow-only`, `foundry-only`, and `flow-foundry` examples.
