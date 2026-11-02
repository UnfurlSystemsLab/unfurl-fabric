# HLD Addendum — Reusable Composite DCP Contracts and Catalog Layout

**Status:** Identity/profile architecture includes proposed gaps; the source/release/binding storage lifecycle is implemented as specified in `LLD-composite-artifact-storage.md`. No new execution authority or DCP schema is introduced.
**Scope:** Reusable Flow-only, Foundry-only, Flow + Foundry, and capability-constrained composites.
**Related:** `docs/HLD-unfurl-fabric.md`, `docs/Flowfoundry.md`, `docs/REPO-recursive-dcp-projection-build-spec.md`, and the authoritative `unfurl-dcp` schema.

## Problem and ownership

A composite DCP is a **reusable component**, not just a customer deployment template. It has a stable identity, declared offers/needs/refusals, child compatibility constraints, release provenance, and conformance tests. It may be realized through several deployment shapes, subject to its capability and resource guarantees.

Fabric is the design-time **admission, composition, deployment resolution, compilation, signing, and verification** authority. It need not own the source of every reusable composite. Component authors own individual claims; a composite publisher owns the composite source definition and its release lifecycle. Fabric catalogs/adopts approved releases.

Existing Fabric documentation already supports deployment shape selection (`IN_PROCESS_LIBRARY`, `MODULAR_MONOLITH_MODULE`, `STANDALONE_JAVA_APP`, `SPRING_BOOT_SERVICE`, `REMOTE_MICROSERVICE`, `CONTAINERIZED_SERVICE`, `MANAGED_EXTERNAL_ADAPTER`). Do not introduce a parallel deployment taxonomy.

## Proposed identity and versioning

Keep these separate:

1. **Logical component identity:** stable DCP-compatible URI, e.g. `urn:unfurl:assembly:flow-foundry-agentic-workflow`. Verify URI format against the authoritative DCP schema before adopting.
2. **Capability compatibility version:** `offers[].version` for each externally observable capability; distinct from claim version and composition-contract version. Changing implementation dependencies alone need not imply a breaking capability change.
3. **Assembly release:** independently versioned, immutable set of component artifacts and resolved dependencies.
4. **Capability profile:** required/optional/forbidden capability constraints, e.g. `read-only-agent`, `agent-with-tools`, `workflow-with-approvals`; model with existing DCP vocabulary, not an invented schema.
5. **Deployment realization:** resolved shape, substrate profile, environment bindings, secrets references, and trust policy; not part of the stable logical name.

Do **not** encode Flow, Foundry, Substrate, and every capability version into a human-readable component name. Pin exact versions and hashes in the release manifest and frozen compiled contract. A change to externally visible guarantees requires appropriate offer and claim versioning. A release must be reproducible.

## Field mapping decision

This mapping selects existing constructs and identifies design/implementation gaps; it does not introduce a manifest format or authorize runtime changes. DCP wire paths use snake_case below; Java model names retain their existing spelling. Fabric needs files and compiler extension keys have their own codecs and must not be mechanically renamed to DCP wire spelling.

### Identity and compatibility

| Proposed concept | Existing field/model | Decision and limits |
|---|---|---|
| Logical composite identity | Claim `identity.uri` (`Identity.uri`) | Stable across versions, profiles, and deployment instances of the same logical component. The proposed assembly URN is a naming convention, not a new identity type or proof of trust. |
| Display name | Claim `identity.name` | Human-readable label; directories and display names are not lookup identities. |
| Component kind | Claim `identity.kind` | Use an existing kind according to actual behavior. Containing Foundry does not by itself make the composite an `intelligent_component`; that kind requires a negotiation surface. |
| Publisher | Claim `identity.publisher`, `identity.publisher_uri` | Source publisher identity; admission and verified signatures determine trust. These strings grant no authority. |
| Claim revision | Claim `identity.version`, `metadata.claim_version` | Same exact SemVer value. Versions the complete claim, including offers and dependencies; separate from the package release. |
| Protocol revision | Claim `metadata.dcp_version` | DCP version only; never a Flow, Foundry, assembly, or capability version. |
| Public capability compatibility | Claim `offers[].capability`, `offers[].version` | Version each public offer independently. Breaking observable behavior requires the corresponding offer's major-version change and a claim revision; compatible implementation changes need not change offer versions. |
| Consumer compatibility | Fabric `Need.requiredCapabilities` / `optionalCapabilities`, containing `CapabilityRequirement.capabilityVersion` and `requiredOfferDetails` | Resolve offer name, SemVer range, and interface-detail constraints. Design-time input, not an additional DCP claim field. |
| Frozen composition identity/version | `CompositionContract.contractId` / `contractVersion` (`contract_id`, `contract_version`) | Identifies a governed edge or aggregate contract, not the public capability version. Leaf `parties.*.claim_version` pins claims; `binding.provider_capability_version` pins the resolved offer. |
| Recursive compiled closure | Root `metadata.extensions.contains`, `CompiledContract.childContracts` | Reuse aggregate/leaf DCP contracts. The compiler emits child references as maps containing `contractId`; preserve that extension convention. Claim containment for inspection is not the compiled contract closure. |

Current `ContractCompiler` generates contract IDs using selected inputs and the compile timestamp, emits contract version `0.1.0`, and synthesizes the aggregate provider URI from the candidate ID. Neither generated ID is currently the stable reusable publisher identity. A future compiler change must explicitly bind the published composite claim to the compiled realization and validate the parties; simply renaming the aggregate URI is insufficient.

### Release and deployment realization

| Proposed concept | Existing field/model | Decision and limits |
|---|---|---|
| Assembly package release | Fabric `catalog.artifact.coordinates` (`AuthoredArtifact.coordinates`); signed `AssemblyIndex.releaseVersion` | Reuse package coordinates; the Fabric storage index separately identifies an immutable release of exact payloads. This index is not a DCP schema or execution contract. Remote promotion policy remains publisher/host-owned; do not overload `contract_version`. |
| Artifact integrity | `ArtifactDescriptor.coordinates`, `sha256`, `source`, `signature`; `SelectionRecord.artifact`, `claimHash` | Exact artifact bytes and canonical claim hash are distinct pins. Coordinates alone, particularly SNAPSHOT coordinates, do not establish release immutability. |
| Release dependency closure | `CompiledContract.selections`, `childContracts`, `substrateProfileHash`, `bindingPlan`; `AssemblyIndex.files` | The signed index pins source definition, selected profile, retained JARs, substrate profile, signed compiler envelope and frozen root/child contracts. Storage verifies hashes and closure; it does not certify workload behavior. |
| Compiler provenance | Contract `provenance` (`Provenance`) | Compiler/mode/time information, not a complete source-release attestation. Define missing attestation semantics before adding extensions. |
| Signer | `FrozenContract.signedContract`, `SignedContract.signerKeyId`, signature and algorithm | Distinct from source publisher. Verify against deployment-owner trust policy. Root signing covers canonical root bytes, not arbitrary sidecars; required child/support artifacts need verified closure coverage. |
| Supported deployment shapes | Fabric `catalog.component_shape_profile` (`ComponentShapeProfile`) | Reuse `defaultShape`, `supportedShapes`, `shapeRuntime`. A declaration is not evidence of conformance for every shape. |
| Selected realization | `SelectionRecord.deploymentShape`, `CompiledContract.bindingPlan` | Resolved shape and wiring, separate from logical identity and offer versions. Different shapes can require different transports and frozen contracts. |
| Substrate requirements | `SubstrateProfile.portRequirements`, `claims`, `profileHash`; `CompiledContract.substrateProfileHash` | Required runtime substrate services, not a named capability profile. Preserve the hash relationship and bind real ports before readiness. |
| Environment/instances | Runtime binding `binding_id`, `contract_id`, `contract_version`, `target_environment`, `provider_instance`, `consumer_instance` | Environment, instance names, endpoints, and deployment kind. Component URI/version must match the corresponding contract party. HLD-C2 describes tenant/region/namespace, but Java `TargetEnvironment` currently contains only `name`; richer target fields require schema/model reconciliation. |
| Secrets/configuration | Runtime binding `provider_instance.credentials_ref`, `base_url_ref`, `configuration`; `SecretRef` / `ConfigRef` | Credentials are references. Configuration cannot override frozen capability semantics or broaden authority. |
| Trust/governance | Fabric trust policy; contract `trust`; runtime binding `runtime_policy`, `deployment_controls`; DCP integration ports | Keep admission trust, signing trust, authorization, audit, and telemetry distinct. Bind customer systems through owning ports/adapters. A trust-tier label does not replace verification or authorization. |

Reproducibility distinguishes replay of a published release from fresh compilation. Current contract IDs/provenance include time, so the same source compiled later does not produce identical bytes. Preserve exact signed release outputs and input hashes. Byte-for-byte rebuild certification additionally requires pinned compiler inputs, including time, and deterministic signing/export behavior; this is an acceptance requirement, not verified current support.

### Capability profiles and dependency constraints

A profile is a named design-time selection of constraints, not another `SubstrateProfile`, deployment shape, or version embedded in the component name. Reuse Fabric needs for supported positive constraints. No current model defines a standard profile identifier, immutable profile digest, or complete forbidden-capability policy; these remain explicit design gaps rather than arbitrary claim-extension keys.

| Constraint | Mapping | Behavior and current gap |
|---|---|---|
| Required capability | `Need.requiredCapabilities` / `CapabilityRequirement.requiredOf` | Missing name/version/detail match blocks composition. Resolve child compatibility to exact claim and offer versions in leaf contracts. |
| Optional capability | `Need.optionalCapabilities` / `CapabilityRequirement.optionalOf` | Absence accepted. A selected provider still satisfies compatibility, trust, binding, and governance checks. Optional does not mean forbidden. |
| Required/recommended/forbidden dependency | DCP schema HLD-C2 section A.5 `dependencies.required`, `.recommended`, `.forbidden` | Schema concepts. Java `Dependencies` currently contains only `List<String> needs`; Fabric checks these for peer dependencies or external/substrate ownership. Reconcile schema and implementation before authoring the structured fields as production manifests. |
| Forbidden capability | No complete current `Need` field or candidate gate | Specify typed constraints and validation in the DCP design before Fabric admission/selection/compile integration. Forbidden-dependency semantics concern dependency conflicts; they are not automatically a universal forbidden-offer filter. Current codecs can silently ignore unknown fields. |
| Responsibility refusal | Claim `refusals[]` (`concern`, `rationale`, `owned_by`) | Describes declined ownership and redirection. Does not prohibit another component from providing a capability; cannot implement no-tools/no-RAG. Fabric `refusalExpectations` is not an exclusion list either. |
| Read-only / no tools / no RAG | Accepted constraints plus host-owned permission, tool, and data-access ports | A profile name or omitted top-level offer cannot constrain behavior inside `agent.run` or a workflow. Product runtimes enforce narrowed authority through accepted contracts and bindings; pin workload/tool configuration and validate the reachable execution closure. |

Forbidden constraints must define scope: selected component/child closure, exported surface, and reachable runtime actions are different sets. A no-tools profile checks nested workflow/agent/tool dependencies and external tools, not just top-level offers. Unrelated tenant-catalog entries must not reject a valid composition. An unresolved external binding that could violate a constraint prevents readiness; it cannot be assumed safe. Required/forbidden contradictions fail before selection.

A selected optional feature changes the pinned realization. It may retain the logical identity, but its public behavior must be covered by the published offer semantics/version. Profile names grant no permissions; bindings may narrow accepted authority and must not widen it.

### Change examples and implementation gates

| Change | Version/pin consequence |
|---|---|
| Compatible Foundry implementation upgrade | New immutable package release and artifact/closure hashes; revise claims if declarations change. Public offer version need not change. Regenerate and verify affected contracts/bindings. |
| Breaking `agent.run` input/output or guarantee | Offer major-version change plus published claim revision. Consumers accept a compatible range explicitly; compile new contracts and review bindings. |
| Another endpoint/environment | New or reviewed runtime binding, same logical URI and offer version. Recompile when transport or other frozen semantics change. |
| Select a no-tools profile | New pinned realization/profile provenance after constraint validation and runtime enforcement. Do not advertise the guarantee before missing support is implemented and verified. |

Before production definitions using unsupported dependency/profile semantics, reconcile HLD-C2 fields with Java `Dependencies` and validators in the DCP LLD/build spec. Specify forbidden scope and enforcement contracts before implementation. Fabric owns profile application, candidate gates, published-composite-to-contract mapping, and export integration. Publishers own immutable releases; Flow/Foundry and customer adapters enforce their runtime ports. The signed storage-index format is specified in `LLD-composite-artifact-storage.md`; remote promotion policy remains a host/publisher decision.

Acceptance includes independent claim/offer/contract version changes, rejection of unknown fields in newly governed constraint inputs, nested forbidden capabilities, external binding checks, contradictory profiles, cross-shape differences, signature/closure tampering, and reproducibility with pinned compile inputs. Field mapping does not establish that those checks already pass.

Source evidence checked 2026-10-31 (paths relative to each owning repository):

- `unfurl-dcp`: `docs/HLD-C2-dcp-schema-spec-updated.md` A.2/A.5/A.6/A.11/B/C; `src/main/java/com/unfurl/dcp/{claim,contract,runtimebinding,trust,versioning}`: `Identity`, `Dependencies`, `Offer`, `ClaimMetadata`, `CompositionContract`, `CompositionContractMetadata`, `FrozenContract`, `RuntimeBinding`, `SignedContract`, `VersionAxis`.
- `unfurl-fabric`: governing HLD, Studio LLD, build spec; `src/main/java/com/unfurl/fabric/{catalog,needs,matcher,compile}`: `CatalogManifestCodec`, `AuthoredArtifact`, `Need`, `CapabilityRequirement`, `CandidateValidator`, `ContractCompiler`.
- `fabric-contract`: `src/main/java/com/unfurl/fabric/{artifact,compiler}`: `ArtifactDescriptor`, `SelectionRecord`, `CompiledContract`. These support types are not defined inside the Fabric product module.
- `unfurl-deployment`: `deployment-domain/src/main/java/com/unfurl/deployment/domain/ComponentShapeProfile.java`; `unfurl-substrate-api`: `src/main/java/com/unfurl/substrate/api/SubstrateProfile.java`.

## Reusable combinations

- `flow-only`: Flow capabilities without a Foundry runtime requirement.
- `foundry-only`: Foundry agent capabilities without a Flow runtime requirement.
- `flow-foundry`: governed cross-product composition with explicit child contracts and ports.
- Constrained profiles: e.g. Foundry with no RAG or no tools. Distinguish **optional** (absence accepted) from **forbidden** (presence rejected); required capability absence is a validation failure.

Avoid a combinatorial proliferation of hand-authored contract files. Start with a small set of tested profiles and generate/compile immutable contract artifacts from admitted source claims. An optional feature is not automatically a separate composite identity.

## Source and artifact storage

The catalog scanner already reads `META-INF/unfurl-catalog.yaml`; Studio admits the same manifest envelope. `definition.yaml` reuses that envelope and profile files reuse Fabric needs. The implemented `assembly-init` command creates source in a supplied publisher workspace, independently of the generated release/deployment repositories. See `assemblies/README.md` for the command sequence.

Publisher workspaces, including Fabric when selected by its authors, use:

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

Definitions and profiles are existing formats, not a new DCP wire format. `assembly-publish` saves immutable releases under `releases/{sha256(logical-uri)}/{release-version}` in the configured repository. `assembly-bind` saves signed deployment revisions under `deployments/{tenant}/{environment}/{sha256(logical-uri)}/{release-version}/{revision}` in a separately configured repository. Source definitions are author-edited; generated contracts and binding revisions are never manually edited in place. Independent publisher repositories retain this layout.

## Validation and acceptance criteria

1. Validate each source claim and recursive child contract against current DCP schema and trust policy.
2. Resolve required/optional/forbidden capabilities; reject unmet requirements and forbidden offers.
3. Pin dependency versions/hashes, substrate profile, and signed compiled-contract provenance.
4. Test the same logical composite against supported deployment shapes; fail admission when a shape cannot satisfy its guarantees.
5. Verify signature and drift detection, negative binding cases, and reproducibility of exports.
6. Run a real Flow–Foundry read-only Kotak Neo vertical slice as a reusable integration/regression scenario, with offline fixtures and separately authorized live smoke tests.
7. Do not claim full deployment support solely because a shape is enumerated; record evidence per shape.

## Decision gates

- Storage/layout is selected: publisher-owned workspaces reuse Fabric catalog/needs formats; signed immutable release and deployment indexes live in configured repositories, as specified in the storage LLD.
- Resolve remaining dependency/profile enforcement and richer environment-model gaps before using those unsupported semantics. Published identity is currently associated through the signed release index; runtime aggregate identity is preserved.
- Source publishers, compiler signers, and deployment owners provide explicit keys pinned by host policy. Remote registry publication/promotion and production workload conformance remain deployment-owner decisions.
- Concrete product definitions must use real admitted claims and actual conformance tests. The storage lifecycle is tested for all three families; it does not itself certify a deployed Flow/Foundry/Kotak workload.
