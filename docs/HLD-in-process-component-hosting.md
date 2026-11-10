# HLD: Composing components hosted in-process by a selected service

Status: **proposed design, not implemented.** This is Fabric's side of DCP's in-process component host
(UnfurlSystemsLab/unfurl-dcp#20: HLD-C §6.1 and the Java LLD *In-Process Component Host*). Foundry's side is
UnfurlSystemsLab/unfurl-foundry#17. Placement belongs to `unfurl-deployment` (HLD-D §6.2a) and the emitted overlay to
`unfurl-deploy-emitter` (HLD-E §4.0b); this HLD defines what Fabric needs from them.

## 1. Problem

The Kotak Neo read adapter is the example. Its claim offers `portfolio.holdings.list`, `portfolio.mtf-holdings.list`
and `gtt.instructions.list`, binds `IN_PROCESS`, and needs seven `owner=host` ports. Fabric compiles
`needs/foundry-kotak-neo.yaml` today, but a host following DCP HLD-C §6.1 would refuse the result:

| DCP host rule | What Fabric produces today | Refused with |
|---|---|---|
| Pins cover the whole runtime artifact set (rule 2) | `artifactSha256` for `kotak-neo-core` only. Its HTTP and Foundry modules and Jackson are unpinned. | `COMPONENT_DEPENDENCY_MISSING` |
| The consumer is the host (rule 7) | Every child contract's consumer is `urn:unfurl:host@0.0.0` (`HostOwnerMeta` default, CLI and Studio). | `CONSUMER_MISMATCH` |
| Signed child contracts reach the host | Only Studio writes individually frozen child contracts (`dcp-runtime-bundle.zip`); the CLI cannot. | nothing to load |
| The runtime binding carries what the component requires | A generic `endpoint_ref` and a development `base_url` (`http://foundry:7979/dcp/<capability>`), no `adapterConfig` ConfigRef. | Kotak's factory denies (`KotakInvocableFactory:41`) |
| The host receives the pinned jars, contracts and bindings | `emit` mounts the operator's Foundry root only. | nothing to load |

Two rules DCP now owns are also still private to Fabric: the canonical claim hash
(`CatalogManifestCodec.computeClaimHash`) and the `owner=` qualifier (`CandidateValidator.ownerMarker`).

## 2. Scope

**In scope:** marking which components are hosted and by what; companion pins; host placement and consumer authoring;
component configuration references in runtime bindings; signed child contracts from the CLI; overlay emission; verify,
plan and explain output; delegating to DCP's shared rules.

**Out of scope:**
- How a host loads, checks and admits a component. That is DCP's, and Fabric adds no second check.
- Components with no activation, such as the embedded substrate combinations. They compile exactly as today, with the
  `HostOwnerMeta` consumer.
- Any Fabric→Flow or Foundry→Flow edge.

## 3. Catalog: who is hosted, by what, with which artifacts

Both blocks are **catalog metadata**, not claim content: they live in `META-INF/unfurl-catalog.yaml`'s `catalog` block
next to `artifact`, `binding` and `lifecycle`, so they change the artifact hash but never the claim hash.

**A hosted component** declares how it can be hosted and its runtime closure:

```yaml
catalog:
  binding: { default_mode: IN_PROCESS, supported_modes: [IN_PROCESS] }
  hosted_activation:
    registrar_shapes:                 # registrar shapes its DCP ComponentActivation supports
      - com.unfurl.foundry.substrate.offers.ToolCapabilityRegistrar
    companions:                       # every other artifact it needs at runtime (DCP decision D1)
      - com.unfurl.adapters.kotak:kotak-neo-http:0.1.0-SNAPSHOT
      - com.unfurl.adapters.kotak:kotak-neo-foundry:0.1.0-SNAPSHOT
      - com.fasterxml.jackson.core:jackson-databind:2.17.2
      - com.fasterxml.jackson.core:jackson-core:2.17.2
      - com.fasterxml.jackson.core:jackson-annotations:2.17.2
      - com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
```

The component's build writes `companions`: its runtime dependency closure, minus what the host supplies. The host
supplies the shared APIs themselves (DCP's base `SharedApi`, `unfurl-dcp` and `unfurl-substrate`, plus each registrar
shape's API, for Kotak `foundry-substrate-*`) and everything that enters the closure only through them, because shared
classes resolve their own dependencies in the host. Kotak's Maven closure has 20 artifacts. DCP and substrate bring in
semver4j, Hibernate Validator, Jakarta Validation, JBoss Logging, SnakeYAML, classmate and the Jackson YAML module, and
the host supplies those. What remains is the six companions above: its HTTP and Foundry modules, and the four Jackson
artifacts its own code imports.

**A host** declares the registrar shape it admits components with and the extra packages it shares:

```yaml
catalog:
  component_host:
    registrar_shape: com.unfurl.foundry.substrate.offers.ToolCapabilityRegistrar
    shared_api_prefixes: [com.unfurl.foundry.substrate.]   # in addition to DCP's base SharedApi
```

Foundry's runtime `SharedApi` (Foundry LLD) must equal DCP's base set plus these prefixes, and a Foundry test checks
it. Fabric uses the declaration only to fail early. The host enforces it at runtime.

**Companion resolution (scan).** Jars in the catalog folder without a catalog manifest are indexed as companion
candidates by the Maven coordinates in their `META-INF/maven/*/*/pom.properties`, and hashed. They are not selectable
entries, and `scan` reports them as `companions=N`, not as `skipped`. Resolution never depends on scan order:

- **One coordinate set per jar.** A jar with exactly one `pom.properties` is indexed under it. A jar with none, or with
  several (a shaded jar), is not a companion candidate and is listed with that reason. It can never satisfy a companion
  by accident.
- **Identical bytes are one artifact.** Several jars with the same coordinates and the same SHA-256, such as a copy
  under another file name, are deduplicated. The index keeps one entry and lists every path.
- **Different bytes are a conflict.** Same coordinates with different SHA-256, as a rebuilt `-SNAPSHOT` can produce,
  is `CATALOG_COMPANION_CONFLICT`. The diagnostic names every conflicting file with its hash. Neither jar is chosen:
  every entry whose `hosted_activation` names those coordinates becomes not selectable, and `compile` refuses with the
  same code. The fix is to remove the stale jar, after which the scan is unambiguous again.
- **Missing.** A `hosted_activation` naming a companion the catalog lacks makes that entry not selectable, with
  `CATALOG_COMPANION_MISSING` naming the coordinates.

Both diagnostics are shown by `scan`, `plan` and Studio admission and are never dropped silently. Studio admission
accepts companion jar uploads by bytes, like component jars, and applies the same rules: uploading different bytes
under coordinates already admitted for the tenant is refused with `CATALOG_COMPANION_CONFLICT`, naming the stored
artifact and its hash.

## 4. Placement and consumer authoring (DCP decision D3)

**Placement** is part of deployment resolution (HLD-D §6.2a). After shapes are chosen, each entry with
`hosted_activation` whose shape is in-process gets a host:

1. The operator may name one in the deployment policy, with components as `groupId:artifactId`:
   `componentHosts: { com.unfurl.adapters.kotak:kotak-neo-core: com.unfurl.foundry:unfurl-foundry }`. A host outside
   the composition, such as the operator's own application, is named as
   `{ external: { claimUri, claimVersion } }`. There is no placeholder.
2. Otherwise Fabric infers the host only when exactly one selected entry has a `component_host` whose `registrar_shape`
   is among the component's `registrar_shapes`, and that entry's shape is a service shape.
3. Otherwise resolution fails, naming the component and candidates: `HOST_PLACEMENT_MISSING` (none),
   `HOST_PLACEMENT_AMBIGUOUS` (several), or `HOST_PLACEMENT_INVALID` (the policy names a non-candidate). This mirrors
   the ambiguous-compile gate: Fabric never picks silently.

`BindingPlanEntry` records the result as `host` (a component id, or an external claim identity). It is empty for every
other entry, and empty fields stay out of canonical bytes, so existing contracts keep their bytes.

**Consumer authoring (compile).** `ContractCompiler` writes a hosted component's child contracts with the host as
consumer party: the hosting entry's packaged claim identity (Foundry: `urn:unfurl:foundry@0.1.0`) or the policy's
external identity. All other child contracts keep the `HostOwnerMeta` consumer. Because DCP's
`RuntimeBindingValidator` already requires a binding's consumer instance to equal the contract's consumer party,
runtime bindings follow automatically.

## 5. Pins in the child contract (DCP decision D1)

Each hosted component's child contracts carry, beside today's `artifactCoordinates`, `artifactSha256` and
`claimHash`, the frozen companion set from the catalog:

```yaml
metadata:
  extensions:
    companionArtifacts:               # sorted by coordinates; omitted for components without hosted_activation
      - { coordinates: com.fasterxml.jackson.core:jackson-annotations:2.17.2, sha256: "…" }
      - …
```

`compile` refuses `CATALOG_COMPANION_MISSING` if any companion is absent. `compile` also refuses
`COMPANION_SHADOWS_SHARED_API` if a companion jar contains classes under the placed host's shared prefixes: the host
would refuse it as `ARTIFACT_SHADOWS_SHARED_API`, so Fabric reports it at compile time. `verify --catalog` treats a
companion's hash drift like a component's.

## 6. Runtime bindings for hosted contracts (DCP decision D2)

For a child contract whose provider is hosted, `fabric runtime-bindings` writes a binding for an in-process provider:

- `deployment_kind: IN_PROCESS`, with no `base_url` and no `endpoint_ref`, because an in-process provider has no
  endpoint. The development `base_url` is never written for these.
- `consumer_instance` names the host. `instance_name` is the host's component id slug instead of `flowfoundry-host`.
- `credentials_ref` keeps the conventional `secret://<tenant>/provider-access/<instance>`.
- `configuration.values` carries the component's declared configuration references, each as a ConfigRef the operator
  supplies:
  `--component-config <claimUri>#<key>=config://<tenant>/<path>`. Fabric never guesses one; a missing or inline value
  fails before any file is written, naming the component and key, as identity inputs do today
  (HLD-needs-driven-identity §6).

Where a component declares its keys is DCP decision D2. The recommendation is a DCP-defined offer interface detail,
`offer_interface.details.runtime_configuration_refs: [adapterConfig]`, specified in HLD-C2 before code. Until DCP
settles it, Fabric's input format above is unchanged and the declared list is the only open part.

## 7. Signed child contracts from the CLI

Studio's `dcpRuntimeBundle` (`claims/` plus one signed `contracts/frozen/*.frozen.json` per child contract, through
DCP `ContractFreezer`) moves to `compile.DcpRuntimeBundleWriter`. Studio and the CLI both call it.
`fabric sign --dcp-runtime-bundle <out.zip>` writes the bundle with the same key that signs the contract. This also
removes the guide's limit that only Studio can produce the bundle.

## 8. Emit: the admitted-component overlay

`fabric emit` passes the catalog folder to the emitter as `componentArtifactsPath` when the plan has a hosted
component. The emitter (HLD-E §4.0b) writes, per hosting service, an overlay from the DCP runtime bundle, the runtime
bindings and the catalog:

```text
foundry-admitted/                    mounted read-only at /opt/unfurl/foundry/admitted
  components/*.jar                   claim-bearing jar and companions, copied and re-hashed against the pins
  contracts/frozen/*.frozen.json     only the hosted components' child contracts
  runtime-binding.yaml               only their bindings
  trust-keys/*.pem
```

Copying from the operator's local catalog keeps `emit` offline (HLD-E §13.4). The emitter downloads nothing.

How the overlay reaches its host depends on the host:

- **Mounted.** The host is a selected service this compose target renders (Foundry above).
- **Export-only.** The host is external (the policy's `external` identity), or a selected service this emit does not
  render locally (routed to the `remote` backend, such as a `REMOTE_MICROSERVICE`). The same layout goes to
  `hosted-exports/<host-slug>/` with a `host.json` naming the identity the host must have. It is listed in the emit
  manifest as `delivery: operator` and printed by `emit`/`deploy`, so delivering it is a visible operator step. The
  receiving host's DCP consumer check still applies.
- **Refused.** A rendered host the target cannot mount into (Kubernetes in this slice) fails with
  `HOSTED_COMPONENT_TARGET_UNSUPPORTED`. Kubernetes delivery is a separate design.

## 9. Delegating to DCP's shared rules

`CatalogManifestCodec.computeClaimHash` delegates to DCP `ClaimHash.canonical`, and
`CandidateValidator.ownerMarker`/`isExternallyOwnedDependency` to DCP `Dependencies.ownerOf`/`isExternallyOwned`. A
test computes the hash of every catalog fixture both ways before the switch and requires identical results, so no
existing pin changes.

## 10. Plan, explain and diagnostics

- `plan` prints `hosted: <component> by <host>` under each candidate, or the placement failure line, and
  `companions missing:` lines.
- `explain-deployment` prints `host=` and `companionArtifacts=` per entry.
- Studio's dependency audit shows the host edge and each companion with its hash.

| Code | Step | Cause |
|---|---|---|
| `CATALOG_COMPANION_MISSING` | scan, plan, compile | A declared companion is not in the catalog. |
| `CATALOG_COMPANION_CONFLICT` | scan, plan, compile, Studio admission | Two jars share a companion's coordinates with different SHA-256; names every file and hash. |
| `COMPANION_SHADOWS_SHARED_API` | compile | A companion contains classes under the host's shared prefixes. |
| `HOST_PLACEMENT_MISSING` / `HOST_PLACEMENT_AMBIGUOUS` / `HOST_PLACEMENT_INVALID` | plan, compile | No, several, or a policy-named non-candidate host. |
| `COMPONENT_CONFIG_MISSING` / `COMPONENT_CONFIG_INLINE` | runtime-bindings | A declared configuration reference is not supplied, or is not a `config://` reference. |

## 11. Worked example: Foundry with the Kotak adapter

1. Catalog: the Foundry jar (with `component_host`), `kotak-neo-core` (with `hosted_activation`) and its six
   companions. `scan` prints `companions=6`.
2. `plan --needs needs/foundry-kotak-neo.yaml` → `EXACT`, Foundry and `kotak-neo-core`, `hosted: kotak-neo-core by
   unfurl-foundry` (inferred: Foundry is the only `ToolCapabilityRegistrar` host).
3. `compile` → the three Kotak child contracts have consumer `urn:unfurl:foundry@0.1.0` and six
   `companionArtifacts`; `sign --dcp-runtime-bundle` writes the bundle.
4. `runtime-bindings … --component-config unfurl://component/kotak-neo-adapter#adapterConfig=config://demo/kotak/adapter`
   → three `IN_PROCESS` bindings consumed by Foundry, each with `adapterConfig`.
5. `emit` → the Foundry service plus `foundry-admitted/`. Foundry's profile binds `dcp-admitted-components` to
   `/opt/unfurl/foundry/admitted` (Foundry LLD), and DCP's host admits the three operations at startup.

## 12. Companion changes in other repos

| Repo | Change |
|---|---|
| unfurl-deployment | HLD-D §6.2a placement step; `DeploymentPolicy.componentHosts`; `BindingPlanEntry.host`. |
| unfurl-deploy-emitter | HLD-E §4.0b overlay emission from `componentArtifactsPath`, bundle and bindings. |
| unfurl-dcp | D2: `runtime_configuration_refs` in HLD-C2. The rest is in UnfurlSystemsLab/unfurl-dcp#20. |
| unfurl-foundry | `component_host` block in Foundry's packaged manifest, matching its runtime `SharedApi`. |
| unfurl-adapter-kotak-neo | `hosted_activation` from its exporter; `runtime_configuration_refs: [adapterConfig]` once D2 lands; the activation in `kotak-neo-foundry`. |

## 13. Tests

- **Scan:** companion indexing by `pom.properties`; a missing companion reported on the entry; two identical copies
  deduplicated with both paths listed; two different builds of one `-SNAPSHOT` reported as `CATALOG_COMPANION_CONFLICT`
  naming both files and hashes, with the dependent entry unselectable, and the same result for every file order; a
  shaded jar with several `pom.properties` never indexed.
- **Placement:** inferred, policy-named, external, none, ambiguous, invalid; byte stability of plans without hosted
  components.
- **Compile:** consumer party for hosted children and unchanged consumers for others; `companionArtifacts` sorted and
  hashed; refusal for a missing or shadowing companion.
- **Runtime bindings:** `IN_PROCESS` without endpoints; host instance name; supplied, missing and inline configuration
  references.
- **CLI bundle:** byte-identical to Studio's for the same compiled contract and key.
- **Claim hash delegation:** identical hashes for every fixture.
- **Black box:** a Fabric-compiled Foundry + synthetic hosted component package that DCP's host admits (once
  UnfurlSystemsLab/unfurl-dcp#20 is implemented).
