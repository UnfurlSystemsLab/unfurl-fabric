# Build a Foundry assembly with Fabric

This guide takes you from built jars to a signed package you can deploy. You choose which parts of
Foundry you want, and Fabric checks that they fit together and records the result in a signed
contract.

There are four stages:

| Stage | What happens |
|---|---|
| **Catalog** | Collect the components you can choose from. |
| **Assembly** | Choose a combination by listing the capabilities you need. |
| **Compile** | Fabric picks the components, checks them, and writes a contract that you sign. |
| **Export** | Turn the signed contract into deployment files, such as Docker Compose. |

All commands are PowerShell. Run them from the workspace root, the folder that holds `unfurl-fabric`
and the other repos.

## What works today

| Goal | Status | Use |
|---|---|---|
| Compile and sign any combination below | Works | [Command line](#1-create-the-catalog) |
| Review deployment files for a combination | Works | [Command line](#5-export) |
| Export a Flow + Foundry package that starts | Works in development mode, without authentication | [Studio API](#export-a-package-that-starts) |
| Publish a reusable `flow-foundry` or `flow-only` assembly | Works | [Publish](#publish-a-reusable-assembly) |
| Publish a `foundry-only` assembly with real Foundry | **Not possible yet** | [Current limits](#current-limits) |
| Generate a runnable app for an embedded combination | **Not provided** | Fabric gives you the contract; your app hosts the substrates |

These steps were last run on 2 November 2026 with images built from the latest code. The exported
package started, and Flow and Foundry both reported healthy. No agent or model call was made, so a
real agent run is not covered here.

## Before you start

You need Java 21, Maven, and Git for Windows, which supplies `openssl`. To start an exported package
you also need Docker with Compose 2.24.4 or later.

Build everything once. If Fabric is running from its jar, stop it first, because the build replaces
that file.

```powershell
mvn -q package -DskipTests
```

Then set up your session. This creates a work folder and a `fabric` command:

```powershell
$work = "$env:TEMP\foundry-assembly"
New-Item -ItemType Directory -Force "$work\catalog", "$work\keys\trust" | Out-Null

$examples  = 'unfurl-fabric\docs\examples\foundry-assemblies'
$fixtures  = 'unfurl-e2e\fixtures'
$fabricJar = (Resolve-Path unfurl-fabric\target\unfurl-fabric-studio-server.jar).Path
function fabric { java -cp $fabricJar com.unfurl.fabric.cli.FabricCli @args }

function Wait-ForHealth($url) {
    foreach ($attempt in 1..60) {
        try { return Invoke-RestMethod $url -TimeoutSec 2 } catch { Start-Sleep -Seconds 1 }
    }
    throw "no response from $url"
}

function Assert-PortFree($port) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "port $port is already in use. Stop what is using it, then try again."
    }
}
```

## 1. Create the catalog

A catalog is a folder of jars. Each jar describes itself in `META-INF/unfurl-catalog.yaml`, so Fabric
can read it without running it.

```powershell
Copy-Item unfurl-foundry\target\unfurl-foundry-0.1.0-SNAPSHOT.jar, unfurl-flow\target\unfurl-flow-0.1.0-SNAPSHOT.jar "$work\catalog"
Get-ChildItem unfurl-foundry-substrate\*\target\*-0.1.0-SNAPSHOT.jar |
    Where-Object Name -NotLike 'foundry-substrate-testing*' |
    Copy-Item -Destination "$work\catalog"

fabric scan --catalog "$work\catalog"
fabric list-capabilities --catalog "$work\catalog"
```

`scan` should print `entries=16` and `skipped=0`. These are the components you'll choose from:

| Component | Capabilities | What it is |
|---|---|---|
| `unfurl-foundry` | `agent.run`, `composition.run`, `tool.call`, `rag.search`, `provider.call`, `skill.invoke` | The Foundry service |
| `unfurl-flow` | `workflow.execute`, `workflow.trigger`, `workflow.observe` | The Flow service |
| `foundry-substrate-engine` | `agent.execute` | Runs an agent inside your own app |
| `foundry-substrate-rag` | `rag.search`, `rag.embed` | Retrieval |
| `foundry-substrate-tools` | `tool.invoke`, `tool.register` | Tool calling |
| `foundry-substrate-prompt` | `prompt.compose`, `prompt.template` | Prompt building |
| `foundry-substrate-springai-adapter` | `model-provider`, `embedding-provider`, `vector-store` | Connects to models and vector stores through Spring AI |
| `sample-*-agent` | `agent.chatbot`, `agent.research`, `agent.tool-using` | Sample agents |

The domain, ports, offers, events, resolver and serialization modules are also in the catalog. They
are shared libraries, so you normally don't choose them.

## 2. Choose a combination

There are two ways to run Foundry:

- **Service:** Foundry runs as its own container, and it already includes the substrates it needs.
  Foundry's claim requires Flow, so Flow is always part of this combination.
- **Embedded:** the substrates run as libraries inside your own Java app. There is no Foundry service
  and no Flow.

A needs file lists the capabilities you want. **Fabric doesn't add dependencies for you, so the file
must list every capability the selected components require.** For example, `agent.execute` needs
`model-provider`, and `agent.run` needs `workflow.execute`.

Pick a needs file:

| Combination | Needs file | Selected components | Runs as |
|---|---|---|---|
| Chatbot | `$fixtures\chatbot-agent-needs.yaml` | engine, Spring AI adapter | Embedded |
| Research agent with RAG | `$fixtures\research-agent-needs.yaml` | engine, RAG, Spring AI adapter | Embedded |
| Agent with tools | `$fixtures\tool-using-agent-needs.yaml` | engine, tools, Spring AI adapter | Embedded |
| Agent with prompts | `$examples\needs\embedded-prompt-agent.yaml` | engine, prompt, Spring AI adapter | Embedded |
| All substrates | `$examples\needs\embedded-full.yaml` | engine, RAG, tools, prompt, Spring AI adapter | Embedded |
| Sample chatbot | `$examples\needs\embedded-sample-chatbot.yaml` | sample agent, engine, Spring AI adapter | Embedded |
| Foundry + Flow | `$examples\needs\foundry-flow.yaml` | Foundry, Flow | Service |
| Foundry + Flow + adapter | `$examples\needs\foundry-flow-spring-ai.yaml` | Foundry, Flow, Spring AI adapter | Service |
| Foundry + Flow + separate RAG | `$examples\needs\foundry-flow-separate-rag.yaml` | Foundry, Flow, RAG, Spring AI adapter | Service |
| Everything | `$fixtures\needs-all-shapes.yaml` | Two valid choices, see below | Service and embedded |

To make your own, copy one of these files. Use `^0.1` as the version, because every component is at
`0.1`.

Preview the result before compiling:

```powershell
$needs = "$examples\needs\foundry-flow.yaml"
fabric plan --catalog "$work\catalog" --needs $needs
```

The first line of the output tells you what happens next:

| Result | Meaning | What to do |
|---|---|---|
| `EXACT` | One valid set of components | Continue |
| `AMBIGUOUS` | Several valid sets, listed best first | Add `--auto-select-best` or `--select <candidate-id>` to `compile` |
| `NO_MATCH` | Nothing valid | Add the missing dependencies to the needs file |

`rag.search` is offered by both Foundry and `foundry-substrate-rag`, which is why **Everything** is
ambiguous. To force the separate RAG component, pin it under `artifactConstraints`, as
`foundry-flow-separate-rag.yaml` does.

## 3. Compile and sign

Create a signing key once. The `trust` folder holds the public keys you accept.

```powershell
$openssl = "$env:ProgramFiles\Git\mingw64\bin\openssl.exe"
& $openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$work\keys\private.pem"
& $openssl pkey -in "$work\keys\private.pem" -pubout -out "$work\keys\public.pem"
Copy-Item "$work\keys\public.pem" "$work\keys\trust\"
```

These keys are for trying things out. Keep real signing keys out of the repo.

Compile, sign and verify:

```powershell
$out = "$work\out"
fabric compile --catalog "$work\catalog" --needs $needs --out "$out\compiled.yaml"
fabric sign --contract "$out\compiled.yaml" --key "$work\keys\private.pem" --public-key "$work\keys\public.pem" --out "$out\signed.yaml"
fabric verify --contract "$out\signed.yaml" --trust-keys "$work\keys\trust" --catalog "$work\catalog"
```

`verify` should end with `overallOk=true`. It also fails if a catalog jar has changed since you
compiled.

To see what Fabric chose and why:

```powershell
fabric explain --contract "$out\signed.yaml"
fabric explain-deployment --contract "$out\signed.yaml"
```

## 4. Choose how it deploys (optional)

Without a policy, each component uses its default: Foundry and Flow run as containers, and substrates
run in-process. A policy changes that. Add it to `compile` with
`--deployment-policy $fixtures\deployment-policy-enterprise.yaml`.

| Policy | Foundry + Flow | Embedded chatbot |
|---|---|---|
| `deployment-policy-small-client.yaml` | Fails: it allows only in-process | In-process |
| `deployment-policy-mid-size.yaml` | Spring Boot services | In-process |
| `deployment-policy-enterprise.yaml` | Containers | Fails: it requires containers |

A policy failure names the component that couldn't be placed.

## 5. Export

Create the runtime bindings, which say where each part runs, then generate deployment files:

```powershell
fabric runtime-bindings --signed-contract "$out\signed.yaml" --tenant demo --environment local --out "$out\runtime-bindings.yaml"

fabric emit --contract "$out\signed.yaml" --profile "$out\compiled.substrate-profile.yaml" --trust-keys "$work\keys\trust" --target "$examples\target-local-images.yaml" --out "$out\compose"
fabric emit --contract "$out\signed.yaml" --profile "$out\compiled.substrate-profile.yaml" --trust-keys "$work\keys\trust" --target k8s-dev --out "$out\kubernetes"
```

What you get depends on the combination:

| Combination | `local` target | `k8s-dev` target |
|---|---|---|
| Embedded | `flow-runtime-profile.yaml` only, because there are no services to start | Fails: Kubernetes can't place in-process components |
| Foundry + Flow | `docker-compose.yaml` and `flow-runtime-profile.yaml` | Helm chart and manifests |
| Foundry + Flow with substrates | `docker-compose.yaml` and `flow-runtime-profile.yaml` | Fails: same reason as embedded |

`target-local-images.yaml` sets real Docker image names. Without it, the `local` preset writes catalog
coordinates into `image:`, and Docker can't use them.

These files are good for review, but they aren't a complete package. Flow needs a runtime bundle to
start, and today only Studio creates that bundle.

## Export a package that starts

This route uses the Studio API to create the bundle. It starts a separate Studio on port 7879 with its
own state file, so it doesn't touch a Fabric you already have running. It reuses `$work`, the catalog
and the keys from above.

**Start Studio with signing keys.** Without them, Studio exports unsigned files.

```powershell
Assert-PortFree 7879
$env:UNFURL_STUDIO_SIGNING_PRIVATE_KEY = "$work\keys\private.pem"
$env:UNFURL_STUDIO_SIGNING_PUBLIC_KEY  = "$work\keys\public.pem"
$studio = Start-Process java -PassThru -WindowStyle Hidden -ArgumentList '-jar', "`"$fabricJar`"", '--port', '7879', '--state-path', "`"$work\studio-state.json`""

$api = 'http://127.0.0.1:7879'
$tenant = 'demo'
$assembly = 'agent-platform'

function studio($path, $body) {
    $json = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 20))
    Invoke-RestMethod -Method Post -Uri "$api$path" -ContentType 'application/json' -Body $json
}

Wait-ForHealth "$api/health"
```

**Add components to the catalog.** This sends each jar's claim rather than the whole jar, because the
Foundry jar is too big to upload. Admission validates each claim.

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-Claim($jar) {
    $zip = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $reader = New-Object IO.StreamReader($zip.GetEntry('META-INF/unfurl-catalog.yaml').Open())
        $reader.ReadToEnd()
    } finally { $zip.Dispose() }
}

$artifacts = foreach ($name in 'unfurl-flow-0.1.0-SNAPSHOT.jar', 'unfurl-foundry-0.1.0-SNAPSHOT.jar') {
    $path = "$work\catalog\$name"
    @{ fileName = $name; sha256 = 'sha256:' + (Get-FileHash $path -Algorithm SHA256).Hash.ToLower(); claimYaml = Read-Claim $path }
}

$admission = studio "/studio/tenants/$tenant/catalog/admissions" @{ assemblyId = $assembly; artifacts = @($artifacts) }
$admission.results | Select-Object fileName, status
```

Each file should show `VERIFIED`.

**Create the assembly and add components.** The catalog also contains two built-in demo entries, so
this adds only the components you uploaded. Each change returns a new revision number that the next
change must use.

```powershell
$null = studio "/studio/tenants/$tenant/assemblies" @{ assemblyId = $assembly; targetApplicationName = 'Agent Platform'; defaultDeploymentTarget = 'containerized-local' }

$draft = studio "/studio/tenants/$tenant/assemblies/$assembly/sessions" @{ tenantId = $tenant; assemblyId = $assembly; collaboratorId = 'operator'; collaboratorName = 'Operator' }
$session = $draft.session.sessionId
$revision = [long]$draft.session.revision

$catalog = Invoke-RestMethod "$api/studio/tenants/$tenant/catalog"
foreach ($entry in $catalog.entries | Where-Object catalogEntryId -Like 'uploaded:*') {
    $result = studio "/studio/tenants/$tenant/assemblies/$assembly/sessions/$session/intents" @{
        tenantId = $tenant; assemblyId = $assembly; sessionId = $session; baseRevision = $revision
        type = 'ADD_COMPONENT'; collaboratorId = 'operator'; collaboratorName = 'Operator'; catalogEntryId = $entry.catalogEntryId
    }
    "$($entry.catalogEntryId): $($result.status)"
    $revision = [long]$result.newRevision
}
```

Each component should show `VALID`.

**Compile and download.** Each download is checked against the hash Studio returned.

```powershell
$compile = studio "/studio/tenants/$tenant/assemblies/$assembly/sessions/$session/compile" @{ tenantId = $tenant; assemblyId = $assembly; sessionId = $session; expectedRevision = $revision; sign = $true }
$compile.status

$export = "$work\export"
New-Item -ItemType Directory -Force $export | Out-Null

function Save-Artifact($artifact, $name) {
    Invoke-WebRequest "$api$($artifact.url)" -OutFile "$export\$name" -UseBasicParsing
    $hash = 'sha256:' + (Get-FileHash "$export\$name" -Algorithm SHA256).Hash.ToLower()
    if ($hash -ne $artifact.sha256) { throw "hash mismatch: $name" }
    "saved $name"
}

Save-Artifact $compile.contractArtifact 'contract.yaml'
Save-Artifact $compile.substrateProfileArtifact 'substrate-profile.yaml'
Save-Artifact $compile.signedContractArtifact 'signed-contract.json'
Save-Artifact ($compile.supportArtifacts | Where-Object artifactId -Like 'signed-compiled-contract*') 'signed-compiled-contract.yaml'
Save-Artifact ($compile.supportArtifacts | Where-Object artifactId -Like 'dcp-runtime-bundle*') 'dcp-runtime-bundle.zip'

Stop-Process -Id $studio.Id
```

`$compile.status` should be `COMPILED`.

**Build the deployment package.** Foundry's deployment folder is mounted read-only, so the running
service never writes into your checkout.

```powershell
fabric runtime-bindings --signed-contract "$export\signed-compiled-contract.yaml" --tenant demo --environment local --flow-base-url http://flow:8080 --foundry-base-url http://foundry:7979 --out "$export\runtime-binding.yaml"

fabric emit --contract "$export\signed-compiled-contract.yaml" --profile "$export\substrate-profile.yaml" --runtime-binding "$export\runtime-binding.yaml" --dcp-runtime-bundle "$export\dcp-runtime-bundle.zip" --flow-workflows "$examples\workflows" --foundry-deployment-root unfurl-foundry\deployment --trust-keys "$work\keys\trust" --target "$examples\target-local-images.yaml" --out "$export\deploy"
```

`deploy\` now holds `docker-compose.yaml` and a `flow-deployment` folder with the signed contract,
frozen contracts, bindings, workflows and trust keys. The Compose file mounts each Foundry deployment
folder read-only and keeps Foundry's state in a named volume. `workflows\workflow.yaml` is a boot check, so
replace it with your own workflow.

**Build the images.** This builds them from your checkout, using the tags in `target-local-images.yaml`.

```powershell
foreach ($name in 'unfurl-foundry', 'unfurl-flow') {
    docker build -t "${name}:assembly-guide" $name
    if ($LASTEXITCODE -ne 0) { throw "could not build the $name image" }
}
```

**Start it.** The exported file uses Foundry's secured profile, which needs JWT keys, and Flow can't
send JWTs yet. `compose-development.yaml` therefore runs Foundry in development mode with **no
authentication**, publishes both ports on this machine only, and adds health checks for both services. Foundry also needs a key for encrypting stored credentials.

```powershell
$key = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($key)
$env:UNFURL_FOUNDRY_CREDENTIAL_KEY = [Convert]::ToBase64String($key)

function compose {
    docker compose -p foundry-assembly -f "$export\deploy\docker-compose.yaml" -f "$examples\compose-development.yaml" @args
}

Assert-PortFree 8080
Assert-PortFree 7979

compose up -d --pull never --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) {
    compose ps --all
    compose logs --tail 40
    compose down
    throw 'the package did not start'
}
compose ps
```

Both services should show `healthy`. Compose returns an error if a port can't be used, a container
stops, or a service isn't healthy after 3 minutes. Any failure prints the logs and removes the
containers, so you can fix the problem and run this block again. To stop the package:

```powershell
compose down
```

Foundry's state stays in the `foundry-dev-state` volume until you run `compose down -v`.

If you see `flow deployment has unbound workflow capabilities`, a workflow uses something the contract
doesn't provide. Flow does this on purpose, so it can't start with work it can't run. Check your
`--flow-workflows` folder.

## Publish a reusable assembly

Publishing stores a finished combination as a versioned, signed release. Anyone can verify it without
the original source or catalog, and each environment gets its own signed bindings.

Start with `agent-platform.definition.yaml` and replace its identity and publisher with your own. Each
offer in it must exist at that exact version in the compiled contract.

```powershell
$uri = 'urn:example:assembly:agent-platform'
$a = "$work\publish"
$source = "$a\workspace\assemblies\flow-foundry\agent-platform"
$keys = @('--key', "$work\keys\private.pem", '--public-key', "$work\keys\public.pem", '--trust-keys', "$work\keys\trust")

fabric assembly-init --workspace "$a\workspace" --family flow-foundry --name agent-platform --definition "$examples\agent-platform.definition.yaml" --needs "$examples\needs\foundry-flow.yaml" --profile default

fabric compile --catalog "$work\catalog" --needs "$source\profiles\default.yaml" --out "$a\build\compiled.yaml"
fabric sign --contract "$a\build\compiled.yaml" --key "$work\keys\private.pem" --public-key "$work\keys\public.pem" --out "$a\build\signed.yaml"

fabric assembly-publish --source $source --profile default --version 1.0.0 --signed-contract "$a\build\signed.yaml" --substrate-profile "$a\build\compiled.substrate-profile.yaml" --catalog "$work\catalog" --repository "$a\releases" @keys
fabric assembly-verify --repository "$a\releases" --uri $uri --version 1.0.0 --trust-keys "$work\keys\trust"

fabric runtime-bindings --signed-contract "$a\build\signed.yaml" --tenant demo --environment local --out "$a\build\bindings.yaml"
fabric assembly-bind --repository "$a\releases" --deployment-repository "$a\deployments" --uri $uri --version 1.0.0 --tenant demo --environment local --revision r1 --bindings "$a\build\bindings.yaml" @keys
fabric assembly-verify-binding --repository "$a\releases" --deployment-repository "$a\deployments" --uri $uri --version 1.0.0 --tenant demo --environment local --revision r1 --trust-keys "$work\keys\trust"
```

Each publish, bind and verify step should end with `verified=true`. That confirms the files and
signatures. It doesn't check that endpoints are reachable or that secrets exist.

Releases can't be changed. If the source or output changes, publish a new version. If only the
bindings change, use a new revision.

| Family | Must contain | Status |
|---|---|---|
| `flow-foundry` | `workflow.execute` and `agent.run` | Works |
| `flow-only` | `workflow.execute`, no `agent.run` | Works |
| `foundry-only` | `agent.run`, no `workflow.execute` | Blocked with real Foundry |

## Current limits

**These stop a path entirely:**

| Limit | Why | What to do |
|---|---|---|
| The `foundry-only` family can't be published | Foundry's claim requires `workflow.execute`, but this family rejects it | Use `flow-foundry` |
| Studio's `needs/extract` results can't compile | It asks for `^1`, but the components are `0.1.0`, so compile returns `NO_MATCH` | Leave `needsId` out of the session, as this guide does. Compile then checks the selected components' dependencies, not a needs list |
| The Foundry jar can't be uploaded as `artifactBase64` | The request is too large and returns `400 malformed json body` | Send `claimYaml` |
| The command line can't create `dcp-runtime-bundle.zip` | Only Studio's compile produces it | Use the Studio route |
| Flow can't call a secured Foundry | Flow sends no JWT and puts its context in the request body, which Foundry's secured profile rejects | Use `compose-development.yaml` on a trusted machine |

**These are easy to trip over:**

- `NO_MATCH` from `plan` or `compile` often gives no reason. Check that the needs file lists every
  dependency. The sample claims and needs fixtures say dependencies are pulled in automatically, but
  they aren't today.
- `fabric scan --out` crashes while writing the index. Run `scan` without `--out`.
- `assembly-init` says only `invalid composite claim` when a claim has a problem. Compare yours with
  `agent-platform.definition.yaml`, which has every required section.
- The exported `docker-compose.yaml` contains the full path to your Foundry deployment folder, so it
  won't work unchanged on another machine.
- The exported `docker-compose.yaml` uses Foundry's secured profile, the image default. On its own it
  needs JWT verification keys in the deployment's `identity/keys/`, and Flow can't call it until Flow
  sends JWTs. Add `compose-development.yaml` for local use.

**Before production:**

- This package runs Flow and Foundry without authentication. It isn't safe to expose beyond your own
  machine.
- Generated bindings contain development defaults. Review them and supply real endpoints and secret
  references.
- Provider, identity, audit and secret bindings aren't covered by this guide.

## Learn more

- [Flowfoundry runbook](../../Flowfoundry.md): the full 18-step export
- [Fabric Studio API](../../LLD-fabric-studio-api.md): every route
- [Reusable assemblies](../../../assemblies/README.md): source layout and storage
- [Foundry deployment](../../../../unfurl-foundry/deployment/README.md): agents, models and tools
