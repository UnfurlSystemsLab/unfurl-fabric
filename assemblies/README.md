# Reusable composite source and artifact storage

Publisher source belongs here or in another publisher-owned workspace. Generated releases and deployment bindings belong
in separately configured repositories. Do not commit credentials, private keys, generated contract closures, or
environment-specific bindings into composite source folders.

## Source layout

```text
assemblies/
  flow-only/<name>/definition.yaml
  flow-only/<name>/profiles/<profile>.yaml
  flow-only/<name>/tests/
  foundry-only/<name>/...
  flow-foundry/<name>/...
```

`definition.yaml` is the existing `claim` + `catalog` manifest, with a real publisher identity, versioned offers,
responsibility refusals, and metadata. Profile YAML uses the existing Fabric needs format. Authors supply conformance
tests; initialization does not invent a working composite, workload, or test result. Public source offers must be
realized at the exact declared versions by the selected compiled closure. New wrapper behavior requires a real adapter
and its accepted DCP contracts before publication.

Use `FabricCli` through your existing launcher (shown below as `fabric`). All file arguments are local paths.

```text
fabric assembly-init --workspace <publisher-workspace> --family flow-foundry --name <name> --definition <catalog-manifest.yaml> --needs <needs.yaml> --profile default

fabric compile --catalog <catalog-dir> --needs <publisher-workspace>/assemblies/flow-foundry/<name>/profiles/default.yaml --out <build-dir>/compiled.yaml
fabric sign --contract <build-dir>/compiled.yaml --key <private.pem> --public-key <public.pem> --out <build-dir>/signed.yaml

fabric assembly-publish --source <publisher-workspace>/assemblies/flow-foundry/<name> --profile default --version 1.0.0 --signed-contract <build-dir>/signed.yaml --substrate-profile <build-dir>/compiled.substrate-profile.yaml --catalog <catalog-dir> --repository <release-repository> --key <private.pem> --public-key <public.pem> --trust-keys <trusted-public-keys-dir>

fabric assembly-verify --repository <release-repository> --uri <logical-composite-uri> --version 1.0.0 --trust-keys <trusted-public-keys-dir>

fabric runtime-bindings --signed-contract <build-dir>/signed.yaml --out <build-dir>/runtime-bindings.yaml --tenant <tenant> --environment <environment> --flow-base-url <flow-url> --foundry-base-url <foundry-url>

fabric assembly-bind --repository <release-repository> --deployment-repository <deployment-repository> --uri <logical-composite-uri> --version 1.0.0 --tenant <tenant> --environment <environment> --revision r1 --bindings <build-dir>/runtime-bindings.yaml --key <deployment-private.pem> --public-key <deployment-public.pem> --trust-keys <trusted-public-keys-dir>

fabric assembly-verify-binding --repository <release-repository> --deployment-repository <deployment-repository> --uri <logical-composite-uri> --version 1.0.0 --tenant <tenant> --environment <environment> --revision r1 --trust-keys <trusted-public-keys-dir>
```

Use the actual `substrateProfile` path printed by `compile` (or pass `--substrate-profile-out` explicitly). Before
binding admission, review generated bindings and supply the real endpoint/config/secret references. The generator's
defaults are development conventions, not evidence of a connected production environment.

## Stored outputs

```text
<release-repository>/releases/<sha256(logical-uri)>/1.0.0/
  manifest.json                       # signed immutable Fabric index
  source/definition.yaml
  source/profiles/default.yaml
  compiled/signed.yaml                 # existing signed compiler support envelope
  compiled/substrate.yaml
  contracts/<sha256(contract-uri)>.json # frozen root/child DCP contracts
  artifacts/<sha256(artifact)>.jar      # exact selected catalog artifact bytes

<deployment-repository>/deployments/<tenant>/<environment>/<sha256(logical-uri)>/1.0.0/r1/
  manifest.json                       # signed by deployment owner
  runtime-bindings.yaml
```

Verification reloads all indexed bytes and checks pinned signatures, hashes and DCP closure validation without the
original catalog/source workspace. A complete identical retry retains the original release; changed source, artifacts,
profile or compiler output requires a new release version. Changed environment bindings require a new revision.
The publisher key, compiler key and deployment key can differ; the supplied host trust-key set must pin all keys needed
for the operation. Repository paths must be host-owned and do not provide authentication by themselves.

The index associates publisher identity with the existing compiled realization; it does not rewrite generated aggregate
provider identity or authorize new runtime actions. `verified=true` means storage/signature/schema checks passed, not
that endpoints, secrets, provider access, read-only behavior, or runtime activation were certified. Forbidden-capability
profile fields and structured dependency fields unsupported by current Java models are rejected in new source inputs.
See `docs/LLD-composite-artifact-storage.md` for boundaries and validation details.
