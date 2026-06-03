# unfurl-fabric

`unfurl-fabric` is the design-time compiler for Unfurl. It scans component catalogs, matches operator needs, resolves deployment shapes, derives the substrate profile, compiles an unsigned contract, signs it, and verifies signed output.

## Build

```bash
mvn test
```

## Deployment Shape Options

Fabric accepts an optional deployment policy on compile, dry-run, and deployment-resolution commands:

```bash
fabric compile \
  --catalog ./catalog \
  --needs ./needs.yaml \
  --deployment-policy ./deployment-policy.yaml \
  --out ./contract.yaml \
  --substrate-profile-out ./contract.substrate-profile.yaml
```

If `--deployment-policy` is omitted, Fabric uses the MVP default: prefer `IN_PROCESS_LIBRARY` and reject nothing. That keeps local development simple; production operators should supply an explicit policy.

Supported shapes:

| Shape | Meaning |
|---|---|
| `IN_PROCESS_LIBRARY` | Component runs inside the host JVM. |
| `MODULAR_MONOLITH_MODULE` | Component stays in one process but has explicit module boundaries. |
| `STANDALONE_JAVA_APP` | Component runs in a separate JVM. |
| `SPRING_BOOT_SERVICE` | Component runs as a Spring Boot HTTP service. |
| `REMOTE_MICROSERVICE` | Component binds to a remote service endpoint. |
| `CONTAINERIZED_SERVICE` | Component runs as a platform-managed container. |
| `MANAGED_EXTERNAL_ADAPTER` | Component delegates to a customer-managed external system. |

Example `deployment-policy.yaml`:

```yaml
preferredShapes:
  - CONTAINERIZED_SERVICE
  - SPRING_BOOT_SERVICE
  - IN_PROCESS_LIBRARY
disallowedShapes:
  - REMOTE_MICROSERVICE
requireIsolationForCapabilityPatterns:
  - ai.*
runtime:
  javaVersion: "21"
  springBoot: true
  kubernetes: true
  serviceMesh: false
  maxServices: 5
```

## Deployment Commands

Preview without writing files:

```bash
fabric dry-run --catalog ./catalog --needs ./needs.yaml --deployment-policy ./deployment-policy.yaml
```

Resolve only the deployment shapes for the selected candidate:

```bash
fabric resolve-deployment --catalog ./catalog --needs ./needs.yaml --deployment-policy ./deployment-policy.yaml
```

Explain the deployment plan embedded in a signed contract:

```bash
fabric explain-deployment --contract ./contract.signed.yaml
```

Compare deployment shape changes between two signed contracts:

```bash
fabric diff --left ./before.signed.yaml --right ./after.signed.yaml
```

The resolved `BindingPlan` is embedded inline in `CompiledContract` and covered by the signature. Fabric never asks the UI or operator layout state to define validity; deployment shape is resolved from catalog claims, needs, trust policy, substrate support, and deployment policy.
