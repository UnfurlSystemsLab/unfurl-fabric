# REPO: unfurl-fabric Build Spec

This document captures the local build and verification contract for `unfurl-fabric`.

## Module

```text
unfurl-fabric
groupId: com.unfurl.fabric
artifactId: unfurl-fabric
java: 21+
packaging: jar
```

The root `pom.xml` is a reactor aggregator. `unfurl-fabric` is a standalone Maven module and not a child of the root POM.

## Common Commands

Run the Fabric module tests:

```bash
mvn -pl unfurl-fabric -am test
```

Run only tests from inside the module:

```bash
cd unfurl-fabric
mvn test
```

Package the Studio server jar:

```bash
mvn -pl unfurl-fabric -am package
```

The normal module jar and the shaded Studio server jar both embed the Fabric DCP catalog manifest at:

```text
META-INF/unfurl-catalog.yaml
```

This manifest is the DCP-backed catalog claim for the Fabric Studio control-plane component. JAR admission and package
scanning must be able to read it without loading any Fabric classes.

The shade plugin writes:

```text
unfurl-fabric/target/unfurl-fabric-studio-server.jar
```

## Studio Server

Run from the packaged jar:

```bash
java -jar unfurl-fabric/target/unfurl-fabric-studio-server.jar --port 7878
```

Useful flags:

```text
--bind <address>
--port <port>
--state-path <path>
--asset-root <path>
--event-bus <in-memory|redis|kafka>
--redis-url <url>
--kafka-bootstrap-servers <host:port>
--kafka-topic <topic>
```

The server prints the listening URL on startup. It warns when bound to a non-loopback address because no built-in production authentication is configured.

## CLI Surface

`com.unfurl.fabric.cli.FabricCli` dispatches these commands:

```text
scan
list-capabilities
plan
compile
dry-run
sign
verify
explain
diff
explain-rejection
explain-substrate
ask-advisor
analyze-workflow
emit
runtime-bindings
deploy
apply
explain-deployment
resolve-deployment
```

## Build Outputs

Typical contract flow outputs:

```text
root DCP contract YAML
compiled substrate profile YAML
signed/frozen root DCP handoff
support artifacts such as signed compiled envelopes and DCP runtime bundles
diagnostic artifacts such as compiler envelopes and response snapshots
deployment plan output
```

The substrate profile hash is embedded in the compiled support envelope. The default signed handoff covers the root DCP
contract; Fabric support artifacts carry the resolved binding plan when downstream Fabric tooling needs it.
The packaged Studio/CLI jar must also include the runtime deploy-emitter provider modules needed by the bundled
deployment target presets, and the shade step must merge `META-INF/services` entries so `fabric emit` can discover
`local-compose` and `k8s` backends through the deploy-emitter SPI.
For local Flowfoundry compose emits, the CLI must pass the `--trust-keys` directory through to the
target options as `trustKeysPath` so the deploy emitter can copy public DCP verification keys into
the Flow deployment root and wire `UNFURL_DCP_TRUST_KEYS_DIR` for runtime child-contract hydration.

## Studio Container

The repository also ships a dev/lab container for the Studio server:

```bash
docker build -t ghcr.io/unfurlsystemslab/unfurl-fabric-studio:0.1.0-snapshot .
docker run --rm -p 7878:7878 ghcr.io/unfurlsystemslab/unfurl-fabric-studio:0.1.0-snapshot
```

The image runs the shaded `unfurl-fabric-studio-server.jar`, binds to `0.0.0.0:7878`,
and keeps mutable Studio state at `/opt/unfurl/fabric/state/studio-state.json`. Product secrets and
cross-product endpoints stay externalized through environment variables such as
`UNFURL_FOUNDRY_DCP_ENDPOINT`; the image does not bake in Foundry, Flow, provider keys,
tenant catalogs, or deployment roots.

## Test Expectations

Tests cover:

- CLI compile, dry-run, sign, verify, explain, diff, emit, deploy, and apply flows.
- Ambiguous candidate behavior.
- Substrate profile determinism and secret exclusion.
- Studio API record round trips and route behavior.
- Foundry-compatible Studio tool gateway route behavior for HTTP authoring tools.
- Tenant file registry and session-history behavior, including catalog file version selection and tenant isolation.
- Dynamic DCP projection, layout, catalog, and session behavior.
- Deployment policy and shape resolution.
- Fabric-to-Flow integration scenarios.

When changing API records, run Fabric tests and the UI validation client tests if route or JSON shape changes cross into `unfurl-ui`.

## GitHub Packages

This repository participates in the `UnfurlSystemsLab` private Maven package chain.

- Publish: GitHub Actions verifies this repository, then dispatches `UnfurlSystemsLab/unfurl` `publish-lab-maven.yml` with `publish_scope=changed`; the root aggregator publishes this repository's Maven artifact to `https://maven.pkg.github.com/unfurlsystemslab/unfurl` using Maven server id `github`.
- Consume: this repository resolves internal `com.unfurl...` artifacts through `https://maven.pkg.github.com/unfurlsystemslab/*`.
- Credentials: local and CI Maven settings must provide server id `github`; use `CI_REPO_TOKEN` or a PAT with `repo`, `workflow`, `read:packages`, and `write:packages` for central Lab package dispatch/publish and cross-repository private dependency reads.
- Component CI must use `CI_REPO_TOKEN` for internal package reads and root workflow dispatch; it must fail before Maven verify when that token is unavailable rather than falling back to the repository-scoped `GITHUB_TOKEN`.
- Bootstrap order: publish all runtime/library dependencies through `unfurl-deploy-emitter` and `unfurl-foundry` before publishing `unfurl-fabric`; publish advisor artifacts after Fabric.
