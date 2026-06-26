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
deploy
apply
explain-deployment
resolve-deployment
```

## Build Outputs

Typical contract flow outputs:

```text
compiled contract YAML
compiled substrate profile YAML
signed contract YAML
deployment plan output
```

The substrate profile hash is embedded in the compiled contract. Signed contracts cover the resolved binding plan.

## Test Expectations

Tests cover:

- CLI compile, dry-run, sign, verify, explain, diff, emit, deploy, and apply flows.
- Ambiguous candidate behavior.
- Substrate profile determinism and secret exclusion.
- Studio API record round trips and route behavior.
- Dynamic DCP projection, layout, catalog, and session behavior.
- Deployment policy and shape resolution.
- Fabric-to-Flow integration scenarios.

When changing API records, run Fabric tests and the UI validation client tests if route or JSON shape changes cross into `unfurl-ui`.

