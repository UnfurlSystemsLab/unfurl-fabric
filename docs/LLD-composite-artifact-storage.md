# LLD: Composite source, release, and deployment storage

## Ownership and scope

Fabric stores published composite definitions and verified compiler output; it does not create a parallel compiler or execution authority. This implements the storage decision in `HLD-reusable-composite-dcp-contracts.md`. Capability-profile exclusion enforcement and DCP dependency-schema reconciliation remain separate work; storage must not imply those guarantees.

Pattern: Repository port plus filesystem Adapter, orchestrated by an application Service and CLI Command adapters. No new DCP wire format is introduced. Fabric release/deployment descriptors are signed indexes of existing DCP artifacts, not composition contracts.

## Locations

- Publisher workspace: `assemblies/{flow-only|foundry-only|flow-foundry}/{name}/definition.yaml` is the existing Fabric `claim` + `catalog` manifest. `profiles/{profile}.yaml` is an existing Fabric needs document. Authors keep conformance tests in `tests/`. `assembly-init` copies supplied real definition/needs documents; it does not fabricate claims or workloads.
- Release repository: `releases/{sha256(logical-uri)}/{release-version}/`. The configured repository root is independent of source. A release selects one profile and includes the source definition, selected needs, signed compiled support envelope, substrate profile, frozen root/child DCP contracts, and exact selected catalog artifacts. The signed manifest binds logical URI, claim version, family, profile, package release version, and every payload hash. Release version is strict SemVer. A different profile or artifact set requires a different release version.
- Deployment repository: `deployments/{tenant}/{environment}/{sha256(logical-uri)}/{release-version}/{revision}/`. A deployment-owner-signed manifest binds the release manifest hash and validated runtime-binding set hash. Revisions are immutable path-safe names. The same release can be bound in multiple environments without copying credentials into it.

## CLI lifecycle

`assembly-init --workspace DIR --family FAMILY --name NAME --definition FILE --needs FILE --profile NAME`

`assembly-publish --source DIR --profile NAME --version SEMVER --signed-contract FILE --substrate-profile FILE --catalog DIR --repository DIR --key PEM --public-key PEM --trust-keys DIR`

`assembly-verify --repository DIR --uri URI --version SEMVER --trust-keys DIR`

`assembly-bind --repository DIR --deployment-repository DIR --uri URI --version SEMVER --tenant NAME --environment NAME --revision NAME --bindings FILE --key PEM --public-key PEM --trust-keys DIR`

`assembly-verify-binding` takes the same release/deployment locator and trust keys without bindings or signing keys. Existing `compile`, `sign`, and `runtime-bindings` commands produce inputs. Publication is local repository admission, not deployment activation or a remote publish. Verification never silently repairs or recompiles artifacts.

## Validation and trust

Source manifests use `CatalogManifestCodec`, `ClaimValidator`, and identity/metadata/version checks. Profile needs reuse `NeedsCodec`; publication rejects unsupported/unknown top-level and requirement fields rather than silently dropping forbidden constraints. Trust-policy references in source profiles are rejected until a pinned policy-input lifecycle exists. Family constraints require Flow/Foundry offers in the compiled closure as appropriate and reject the other runtime for single-product families. The selected catalog inventory must satisfy the source profile through `CandidateValidator` and match signed artifact/claim pins through catalog drift checks. The substrate profile hash must match the signed envelope. These checks do not certify workload behavior or read-only execution.

The release service verifies the input envelope with supplied pinned keys; validates recursive closure completeness, uniqueness, and absence of cycles/orphans; freezes every contract using the existing DCP `ContractFreezer`; and signs the release index through DCP `ContractSigner` using Fabric's existing key adapter. The release signer must also be pinned. Source-to-realization association lives in the signed release descriptor; the current generated aggregate provider URI is preserved and is not falsely relabeled as a published provider claim.

Verification authenticates the index with `OfflineContractVerifier`, reads all indexed payloads, compares hashes, and checks semantic consistency again. Index parsing is strict and canonical bytes are checked. No unlisted file is treated as a release artifact. Releases retain exact source and compiled output for replay; fresh compile/sign invocations need not reproduce identical bytes.

Binding admission authenticates the release, parses the existing `runtime_binding_set` envelope, rejects duplicate IDs, foreign/orphan/missing contracts or bindings, checks target environment on every binding, and invokes `RuntimeBindingValidator.validateTree`. All contract edges must be represented exactly once; root must bind the released root contract. The deployment signature covers tenant/environment/revision, release manifest hash, and runtime-binding payload hash. Re-verification repeats binding validation; storage validity does not assert endpoint reachability, secret resolution, authorization, or readiness for activation.

## Filesystem adapter invariants

The repository port provides `read` and `put` for immutable bundles of relative paths and bytes. Adapter paths reject traversal, absolute paths, links, and unsafe locator names. Writes stage under the repository root and promote a complete directory under an exclusive repository file lock, without overwriting an existing release/revision. A conflicting write fails; a complete byte-identical retry succeeds. A failed operation cannot expose a partially written final bundle. Atomic directory move is required; unsupported filesystems fail explicitly. Staging directories are ignored by readers. Repository directories must be controlled by the host; this is not protection against a malicious local administrator racing filesystem operations.

## Validation

Run storage and CLI lifecycle tests, then the Fabric suite. Cover all families, multiple binding environments, reload/re-verification, wrong keys, artifact/source/index tampering, incomplete closure, invalid profile, foreign binding, inline secrets, immutable collisions, idempotent retries, and traversal/link rejection. No live broker, provider, or production deployment is needed for storage certification.
