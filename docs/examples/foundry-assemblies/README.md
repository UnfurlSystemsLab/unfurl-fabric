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
| Export a Foundry-only package that starts | Works in development mode, without authentication | [Command line](#start-a-foundry-only-package) |
| Publish a reusable `flow-foundry`, `flow-only` or `foundry-only` assembly | Works | [Publish](#publish-a-reusable-assembly) |
| Generate a runnable app for an embedded combination | **Not provided** | Fabric gives you the contract; your app hosts the substrates |
| Compile and sign Foundry with the Kotak Neo adapter | Works | [Kotak Neo example](#example-add-the-kotak-neo-adapter) |
| Run the Kotak Neo reads inside Foundry | Works up to Foundry admitting the adapter; a live read needs your session secret | [Kotak Neo example](#example-add-the-kotak-neo-adapter) |
| Export Flow + Foundry + Kotak Neo + Keycloak from the command line | Works. Foundry checks Keycloak's tokens, and Flow calls it with a token exchanged for the caller | [End to end](#end-to-end-from-the-command-line-flow-foundry-kotak-neo-and-keycloak) |
| The same package composed and signed in Studio | Works, with the same limits | [End to end with Studio](#end-to-end-with-studio-flow-foundry-kotak-neo-and-keycloak) |

These steps were last run on 2 November 2026 with images built from the latest code. The exported
package started, and Flow and Foundry both reported healthy. The Foundry-only steps were run on 4
November 2026, and Foundry on its own reported healthy and ready. The Kotak Neo steps were run on 12
November 2026 through `emit`, and Foundry's admitted-component host loaded the result. The
command-line end-to-end steps were run the same day: both containers became healthy, Foundry
admitted the three Kotak reads, and Flow called the secured Foundry with a token exchanged for a
signed-in user. No model or broker call was made, so a real agent run is not covered here.

## Before you start

You need Java 21, Maven, and Git for Windows, which supplies `openssl`. To start an exported package
you also need Docker with Compose 2.24.4 or later.

Build everything once. The catalog needs the Foundry, Flow, adapter and substrate jars, not just
Fabric's. If Fabric is running from its jar, stop it first, because the build replaces that file.

```powershell
mvn -q package -DskipTests
```

Then set up your session. This creates a work folder and loads `guide-helpers.ps1` from this folder,
which defines:

| Helper | What it does |
|---|---|
| `fabric` | Runs a Fabric CLI command through Fabric's launch script, `unfurl-fabric\launch.ps1`, which checks for Java 21 and rebuilds Fabric's jar if it's missing |
| `Wait-ForHealth <url>` | Waits up to a minute for a service to answer |
| `Assert-PortFree <port>` | Stops with a clear message if a port is already in use |
| `$launch`, `$shutdown` | The full paths of Fabric's launch and shutdown scripts |

```powershell
$work = "$env:TEMP\foundry-assembly"
New-Item -ItemType Directory -Force "$work\catalog", "$work\keys\trust" | Out-Null

$examples = 'unfurl-fabric\docs\examples\foundry-assemblies'
$fixtures = 'unfurl-e2e\fixtures'
. "$examples\guide-helpers.ps1"
```

Load the helpers again whenever you open a new PowerShell window. The dot and space at the start of
the last line matter: without them the helpers disappear as soon as the line finishes.

If PowerShell says that running scripts is disabled, allow local scripts for your account with
`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`, then try again. From `cmd.exe`, use
`unfurl-fabric\launch.bat` instead, which takes the same arguments.

## 1. Create the catalog

A catalog is a folder of jars. Each jar describes itself in `META-INF/unfurl-catalog.yaml`, so Fabric
can read it without running it.

```powershell
Copy-Item unfurl-foundry\target\unfurl-foundry-0.1.0-SNAPSHOT.jar, unfurl-flow\target\unfurl-flow-0.1.0-SNAPSHOT.jar, unfurl-adapter-keycloak\target\unfurl-adapter-keycloak-0.1.0-SNAPSHOT.jar "$work\catalog"
Get-ChildItem unfurl-foundry-substrate\*\target\*-0.1.0-SNAPSHOT.jar |
    Where-Object Name -NotLike 'foundry-substrate-testing*' |
    Copy-Item -Destination "$work\catalog"

fabric scan --catalog "$work\catalog"
fabric list-capabilities --catalog "$work\catalog"
```

`scan` should print `entries=17`, `companions=0` and `skipped=0`. These are the components you'll
choose from:

| Component | Capabilities | What it is |
|---|---|---|
| `unfurl-foundry` | `agent.run`, `composition.run`, `tool.call`, `rag.search`, `provider.call`, `skill.invoke` | The Foundry service |
| `unfurl-flow` | `workflow.execute`, `workflow.trigger`, `workflow.observe` | The Flow service |
| `unfurl-adapter-keycloak` | `identity-context` | Open-source Keycloak as the identity provider, either a container Unfurl deploys (the default) or a Keycloak you already run. Other providers, such as Entra ID or Okta, federate into Keycloak. Include it when you want authentication |
| `foundry-substrate-engine` | `agent.execute` | Runs an agent inside your own app |
| `foundry-substrate-rag` | `rag.search`, `rag.embed` | Retrieval |
| `foundry-substrate-tools` | `tool.invoke`, `tool.register` | Tool calling |
| `foundry-substrate-prompt` | `prompt.compose`, `prompt.template` | Prompt building |
| `foundry-substrate-springai-adapter` | `model-provider`, `embedding-provider`, `vector-store` | Connects to models and vector stores through Spring AI |
| `sample-*-agent` | `agent.chatbot`, `agent.research`, `agent.tool-using` | Sample agents |

The domain, ports, offers, events, resolver and serialization modules are also in the catalog. They
are shared libraries, so you normally don't choose them.

The [Kotak Neo example](#example-add-the-kotak-neo-adapter) adds one more component, `kotak-neo-core`,
which offers `portfolio.holdings.list`, `portfolio.mtf-holdings.list` and `gtt.instructions.list`, and
the six companion jars Foundry needs to run it.

## 2. Choose a combination

There are two ways to run Foundry:

- **Service:** Foundry runs as its own container, and it already includes the substrates it needs.
  Flow is optional. The dependency runs one way: Flow calls Foundry (`agent.run`, `tool.call`), and
  Foundry never calls Flow, so Foundry's claim declares nothing on Flow and Foundry runs on its own.
  Flow is added only when the needs ask for `workflow.execute`.
- **Embedded:** the substrates run as libraries inside your own Java app. There is no Foundry service
  and no Flow.

**The claims come from the jars, and the choice comes from you.** Each component's claim, in its
jar's `META-INF/unfurl-catalog.yaml`, says what the component offers and what it depends on. Fabric
always uses the claim as the jar ships it, so you never write or edit one. What you provide is the
choice of components, and there are two ways to make it:

| Route | How you choose | Needs file |
|---|---|---|
| Command line (`plan`, `compile`) | List the capabilities you want, and Fabric picks the components that offer them | Required (`--needs`) |
| Studio | Add the components yourself, as in [Export a package that starts](#export-a-package-that-starts) | Not used |

Either way, compile checks the chosen components against their claims. A required peer-component
dependency that no chosen component offers fails with `NO_MATCH`, naming the component and what it
needs. An optional peer dependency is bound when a chosen component offers it. Externally owned
dependencies (such as `owner=host` or a customer owner) are runtime/profile bindings, including
optional ones; required `substrate=true` dependencies are derived into the substrate profile.
These do not require peer catalog components, and successful compilation does not prove their
runtime bindings are ready. The rest of this section is about the command line.

A needs file lists the capabilities you want. **Fabric doesn't add dependencies for you, so the file
must list every peer capability the selected components require.** For example, `agent.execute` needs
`model-provider`. A component's optional dependencies are different: they are never required, and
the component runs without them, so you list one only when you want its provider included.

Pick a needs file:

| Combination | Needs file | Selected components | Runs as |
|---|---|---|---|
| Chatbot | `$fixtures\chatbot-agent-needs.yaml` | engine, Spring AI adapter | Embedded |
| Research agent with RAG | `$fixtures\research-agent-needs.yaml` | engine, RAG, Spring AI adapter | Embedded |
| Agent with tools | `$fixtures\tool-using-agent-needs.yaml` | engine, tools, Spring AI adapter | Embedded |
| Agent with prompts | `$examples\needs\embedded-prompt-agent.yaml` | engine, prompt, Spring AI adapter | Embedded |
| All substrates | `$examples\needs\embedded-full.yaml` | engine, RAG, tools, prompt, Spring AI adapter | Embedded |
| Sample chatbot | `$examples\needs\embedded-sample-chatbot.yaml` | sample agent, engine, Spring AI adapter | Embedded |
| Foundry only | `$examples\needs\foundry-only.yaml` | Foundry | Service, without Flow |
| Foundry + Flow | `$examples\needs\foundry-flow.yaml` | Foundry, Flow | Service |
| Foundry + Flow + adapter | `$examples\needs\foundry-flow-spring-ai.yaml` | Foundry, Flow, Spring AI adapter | Service |
| Foundry + Flow + separate RAG | `$examples\needs\foundry-flow-separate-rag.yaml` | Foundry, Flow, RAG, Spring AI adapter | Service |
| Foundry + Flow + identity provider | `$examples\needs\foundry-flow-identity.yaml` | Foundry, Flow, identity provider | Service, with authentication through Keycloak |
| Foundry + Kotak Neo adapter | `$examples\needs\foundry-kotak-neo.yaml` | Foundry, Kotak Neo adapter | Service, with the adapter in-process. Needs the [Kotak Neo jar](#example-add-the-kotak-neo-adapter) in the catalog |
| Everything | `$fixtures\needs-all-shapes.yaml` | Two valid choices, see below | Service and embedded |

To make your own, copy one of these files. Use `^0.1` as the version, because every component is at
`0.1`. The exception is `identity-context`, which is at `1.0`, so ask for it with `^1`.

**Identity comes from the needs.** Every capability in a needs file is mandatory. A needs file that
requires `identity-context` gets the identity provider: `compile` fails if no catalog entry offers it,
and the runtime bindings need its settings in every environment, `local` included. A needs file
without it runs with no authentication in any environment, which suits a lightweight deployment on a
trusted network, not one exposed to users.

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
| `NO_MATCH` | Nothing valid | Read the lines that follow: each names one cause and what to change |

After `NO_MATCH`, each `unmet need:` line is a capability in the needs that the catalog can't
satisfy, and each `conflict:` line is a problem with the components that were tried. For example,
asking for identity with no Keycloak jar in the catalog gives:

```text
NO_MATCH
unmet need: the needs require identity-context ^1, but no catalog entry offers identity-context; add a component that offers it to the catalog, or remove it from the needs
```

`compile` prints the same lines and exits with an error.

After `EXACT` or `AMBIGUOUS`, each candidate line can be followed by `optional, not provided:` lines.
They are information, not errors: the component runs without that capability. For example, needs
with `workflow.execute` alone select Flow without Foundry and give:

```text
EXACT
cand-… entries=com.unfurl.flow:unfurl-flow:0.1.0
optional, not provided: com.unfurl.flow:unfurl-flow:0.1.0 can use agent.run but runs without it; add agent.run to the needs to include a provider
optional, not provided: com.unfurl.flow:unfurl-flow:0.1.0 can use tool.call but runs without it; add tool.call to the needs to include a provider
```

`foundry-only.yaml` prints no such line, because Foundry depends on nothing in the catalog.

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
| Foundry only | `docker-compose.yaml` with the Foundry service alone, and `flow-runtime-profile.yaml` | Helm chart and manifests |
| Foundry + Flow | `docker-compose.yaml` and `flow-runtime-profile.yaml` | Helm chart and manifests |
| Foundry + Flow with substrates | `docker-compose.yaml` and `flow-runtime-profile.yaml` | Fails: same reason as embedded |
| Foundry + Kotak Neo adapter | `docker-compose.yaml` with the Foundry service, plus `foundry-admitted/` when you pass `--catalog` ([example](#example-add-the-kotak-neo-adapter)) | Fails: Kubernetes can't yet deliver the adapter to Foundry (`HOSTED_COMPONENT_TARGET_UNSUPPORTED`) |

`target-local-images.yaml` sets real Docker image names. Without it, the `local` preset writes catalog
coordinates into `image:`, and Docker can't use them.

**With the identity provider,** Keycloak is a container by default, so `emit` adds it to
`docker-compose.yaml` next to Flow and Foundry. That service isn't runnable yet: the emitter doesn't
yet set the Keycloak image or provision its realm (see [Current limits](#current-limits)).

To use a Keycloak you already run, compile with `deployment-policy-external-keycloak.yaml`. Flow and
Foundry stay containers, and Keycloak becomes an external system that Compose can't place, so use
`deploy` with two targets. It writes the Compose files to `local-compose\` and Keycloak's binding
configuration and reachability probe to `remote\`:

```powershell
fabric compile --catalog "$work\catalog" --needs "$examples\needs\foundry-flow-identity.yaml" --deployment-policy "$examples\deployment-policy-external-keycloak.yaml" --out "$out\compiled.yaml"
fabric sign --contract "$out\compiled.yaml" --key "$work\keys\private.pem" --public-key "$work\keys\public.pem" --out "$out\signed.yaml"
fabric deploy --contract "$out\signed.yaml" --profile "$out\compiled.substrate-profile.yaml" --trust-keys "$work\keys\trust" --targets "$examples\target-local-images.yaml,remote" --out "$out\deploy"
```

These files are good for review, but they aren't a complete package. Flow needs a runtime bundle to
start. `fabric sign --dcp-runtime-bundle <zip> --catalog <catalog>` writes one from the command line,
the same bundle Studio's compile produces; [Export a package that starts](#export-a-package-that-starts)
uses Studio's. Foundry on its own needs no bundle, so a Foundry-only package can be built from the
command line, as the next section shows.

## Start a Foundry-only package

Foundry needs only its deployment folder, so this route uses the command line alone. Compile and sign
`foundry-only.yaml` as in [Compile and sign](#3-compile-and-sign), then emit with Foundry's deployment
folder:

```powershell
$needs = "$examples\needs\foundry-only.yaml"
fabric compile --catalog "$work\catalog" --needs $needs --out "$out\compiled.yaml"
fabric sign --contract "$out\compiled.yaml" --key "$work\keys\private.pem" --public-key "$work\keys\public.pem" --out "$out\signed.yaml"
fabric verify --contract "$out\signed.yaml" --trust-keys "$work\keys\trust" --catalog "$work\catalog"

fabric emit --contract "$out\signed.yaml" --profile "$out\compiled.substrate-profile.yaml" --foundry-deployment-root unfurl-foundry\deployment --trust-keys "$work\keys\trust" --target "$examples\target-local-images.yaml" --out "$out\foundry-only"
```

Leave out `--runtime-binding`, `--dcp-runtime-bundle` and `--flow-workflows`. They build Flow's
deployment folder, and `emit` refuses them for a composition without Flow.

Build the Foundry image if you haven't already, then start it. `compose-development-foundry-only.yaml`
runs Foundry in development mode with **no authentication**, on this machine only. Don't use
`compose-development.yaml` here: it also configures a Flow service, which this package doesn't have.

```powershell
docker build -t unfurl-foundry:assembly-guide unfurl-foundry
if ($LASTEXITCODE -ne 0) { throw 'could not build the unfurl-foundry image' }

$key = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($key)
$env:UNFURL_FOUNDRY_CREDENTIAL_KEY = [Convert]::ToBase64String($key)

function foundry-compose {
    docker compose -p foundry-only -f "$out\foundry-only\docker-compose.yaml" -f "$examples\compose-development-foundry-only.yaml" @args
}

Assert-PortFree 7979
foundry-compose up -d --pull never --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) {
    foundry-compose ps --all
    foundry-compose logs --tail 40
    foundry-compose down
    throw 'the package did not start'
}
Invoke-RestMethod http://127.0.0.1:7979/health/ready
```

`/health/ready` should report `UP` for Foundry and each of its stores. Stop it with
`foundry-compose down`; add `-v` to also delete Foundry's state. This package and the Flow + Foundry
package below both use port 7979, so run one at a time.

## Export a package that starts

This route starts Flow and Foundry together. It uses the Studio API to create the bundle Flow needs. It starts a separate Studio on port 7879 with its
own state file, so it doesn't touch a Fabric you already have running. It reuses `$work`, the catalog
and the keys from above.

**Start Studio with signing keys.** Without them, Studio exports unsigned files. The launch script
runs in a hidden window, and its output goes to `$work\studio.log` and `$work\studio-error.log`; look
there if Studio doesn't respond.

```powershell
Assert-PortFree 7879
$env:UNFURL_STUDIO_SIGNING_PRIVATE_KEY = "$work\keys\private.pem"
$env:UNFURL_STUDIO_SIGNING_PUBLIC_KEY  = "$work\keys\public.pem"
$studio = Start-Process powershell.exe -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$work\studio.log" -RedirectStandardError "$work\studio-error.log" `
    -ArgumentList '-NoProfile', '-File', "`"$launch`"", '--port', '7879', '--state-path', "`"$work\studio-state.json`""

$api = 'http://127.0.0.1:7879'
$tenant = 'demo'
$assembly = 'agent-platform'

function studio($path, $body) {
    $json = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 20))
    Invoke-RestMethod -Method Post -Uri "$api$path" -ContentType 'application/json' -Body $json
}

Wait-ForHealth "$api/health"
```

**Add components to the catalog.** This uploads each jar. Studio reads the claim out of the jar, hashes the jar
itself, and checks that hash against the `sha256` you send. A claim sent on its own, without its jar, is refused
(`CLAIM_DETACHED`), because nothing would tie it to the component that runs. The JSON body is built as text here
because the Foundry jar is about 25 MB, and base64 contains no characters that need escaping.

```powershell
$artifacts = foreach ($name in 'unfurl-flow-0.1.0-SNAPSHOT.jar', 'unfurl-foundry-0.1.0-SNAPSHOT.jar') {
    $path = "$work\catalog\$name"
    $sha = 'sha256:' + (Get-FileHash $path -Algorithm SHA256).Hash.ToLower()
    '{"fileName":"' + $name + '","sha256":"' + $sha + '","artifactBase64":"' +
        [Convert]::ToBase64String([IO.File]::ReadAllBytes($path)) + '"}'
}
$body = '{"assemblyId":"' + $assembly + '","artifacts":[' + ($artifacts -join ',') + ']}'

$admission = Invoke-RestMethod -Method Post -Uri "$api/studio/tenants/$tenant/catalog/admissions" `
    -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$admission.results | Select-Object fileName, status
```

Each file should show `VERIFIED`. Studio keeps the manifest it read from each jar, so the claim it compiles after a
restart is still the jar's.

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

& $shutdown --port 7879
```

`$compile.status` should be `COMPILED`. The shutdown script stops only a Fabric Studio server, and the
launch script that started it exits with it. Don't use `Stop-Process -Id $studio.Id`: that stops only
the launch script and leaves Studio holding port 7879.

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

Before starting anything, the block checks that the package from **Build the deployment package**
exists and that every image it names has been built, and says which step to run if not.

```powershell
$key = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($key)
$env:UNFURL_FOUNDRY_CREDENTIAL_KEY = [Convert]::ToBase64String($key)

function compose {
    docker compose -p foundry-assembly -f "$export\deploy\docker-compose.yaml" -f "$examples\compose-development.yaml" @args
}

if (-not $export -or -not (Test-Path "$export\deploy\docker-compose.yaml")) {
    throw "there is no package in `$export\deploy. Run 'Build the deployment package' first, in this window."
}
foreach ($image in (compose config --images)) {
    docker image inspect $image *> $null
    if ($LASTEXITCODE -ne 0) { throw "the image $image isn't built. Run 'Build the images' first." }
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

## Example: add the Kotak Neo adapter

This example adds a component from outside the Foundry repos, the read-only
[Kotak Neo adapter](../../../../unfurl-adapter-kotak-neo/README.md), and shows the claims-versus-needs
split from [Choose a combination](#2-choose-a-combination) at work. The adapter reads broker
holdings, margin-funded holdings and GTT instructions. It never places trades. It runs in-process in
the app that admits it, and Foundry exposes its reads to agents as tools.

The adapter is its own Maven build, outside the root reactor. Its core jar carries the claim. Six
more jars are its **companions**: the HTTP and Foundry modules of the adapter, and the four Jackson
jars it uses. Foundry loads the adapter from exactly these seven jars, so all of them go in the
catalog:

```powershell
mvn -q -f unfurl-adapter-kotak-neo\pom.xml package -DskipTests
$m2 = "$env:USERPROFILE\.m2\repository\com\fasterxml\jackson"
Copy-Item unfurl-adapter-kotak-neo\kotak-neo-core\target\kotak-neo-core-0.1.0-SNAPSHOT.jar,
          unfurl-adapter-kotak-neo\kotak-neo-http\target\kotak-neo-http-0.1.0-SNAPSHOT.jar,
          unfurl-adapter-kotak-neo\kotak-neo-foundry\target\kotak-neo-foundry-0.1.0-SNAPSHOT.jar,
          "$m2\core\jackson-core\2.17.2\jackson-core-2.17.2.jar",
          "$m2\core\jackson-databind\2.17.2\jackson-databind-2.17.2.jar",
          "$m2\core\jackson-annotations\2.17.2\jackson-annotations-2.17.2.jar",
          "$m2\datatype\jackson-datatype-jsr310\2.17.2\jackson-datatype-jsr310-2.17.2.jar" "$work\catalog"
fabric scan --catalog "$work\catalog"
```

`scan` should now print `entries=18` and `companions=6`. A companion is a jar without a claim that
another jar's catalog block names; `scan` identifies each one by its `pom.properties`. The adapter
build takes `unfurl-dcp` and the Foundry substrate jars from your local Maven cache. If Maven says it
can't find them, run the root build with `install` instead of `package` first.

**What the jar's claim says.** It offers the three read capabilities, and it needs seven host ports:
identity and account authorization, the Kotak session, runtime configuration, the read port, audit,
telemetry and read admission. Each is declared like `host.audit@v1?owner=host`. The `owner=host`
qualifier makes it a runtime binding the host supplies, so no catalog component has to offer it and
the needs file doesn't list it.

**What you write.** [needs/foundry-kotak-neo.yaml](needs/foundry-kotak-neo.yaml) asks for
`agent.run` and the three reads:

```powershell
$needs = "$examples\needs\foundry-kotak-neo.yaml"
fabric plan --catalog "$work\catalog" --needs $needs
```

```text
EXACT
cand-… entries=com.unfurl.adapters.kotak:kotak-neo-core:0.1.0-SNAPSHOT,com.unfurl.foundry:unfurl-foundry:0.1.0-SNAPSHOT
hosted: com.unfurl.adapters.kotak:kotak-neo-core:0.1.0-SNAPSHOT by com.unfurl.foundry:unfurl-foundry:0.1.0-SNAPSHOT
deployment keys: unfurl://component/kotak-neo-adapter requires accountRef (string), sessionRef (secret-ref); may change maxBytes (tighten), maxRows (tighten), timeoutMs (tighten)
```

The `hosted:` line says Foundry will run the adapter inside its own process. Fabric chose Foundry
because it is the only selected component that hosts this kind of component. The `deployment keys:`
line lists what each deployment must supply and what it may change.

**Compile and sign.** Compile as in [Compile and sign](#3-compile-and-sign), then sign with two more
options. `--dcp-runtime-bundle` writes the signed contracts Foundry loads, and `--catalog` names the
catalog they were compiled from:

```powershell
fabric compile --catalog "$work\catalog" --needs $needs --out "$out\compiled.yaml"
fabric sign --contract "$out\compiled.yaml" --key "$work\keys\private.pem" --public-key "$work\keys\public.pem" --out "$out\signed.yaml" --dcp-runtime-bundle "$out\dcp-runtime-bundle.zip" --catalog "$work\catalog"
fabric verify --contract "$out\signed.yaml" --trust-keys "$work\keys\trust" --catalog "$work\catalog"
```

`compile` places Foundry as a container and the adapter as an in-process library. Each of the three
Kotak contracts names Foundry as its consumer and pins all seven jars by SHA-256. `verify` ends with
`overallOk=true`. It also checks that the companions haven't changed since you compiled.
`fabric explain-deployment --contract "$out\signed.yaml"` prints `host=` and
`companionArtifacts=` for each entry.

**Create the runtime bindings.** Supply the two deployment values: the account alias, and a
reference to the secret that holds the Kotak session. Never put the session itself here.

```powershell
$kotak = "unfurl://component/kotak-neo-adapter"
fabric runtime-bindings --signed-contract "$out\signed.yaml" --out "$out\runtime-bindings.yaml" --catalog "$work\catalog" `
  --component-config "$kotak#accountRef=primary-equity" `
  --component-config "$kotak#sessionRef=secret://demo/kotak/primary-equity-session"
```

Each Kotak read gets an `IN_PROCESS` binding consumed by Foundry. The endpoints, host allowlist,
currency and limits come from the adapter's jar. A deployment may lower a limit, for example
`--component-config "$kotak#maxRows=500"`. Changing an endpoint is refused:

```text
hosted component configuration is invalid: unfurl://component/kotak-neo-adapter CONFIG_KEY_FIXED [portfolioBase]: the component fixes this key; a deployment cannot change it
```

**Export.** Pass the runtime bindings, the bundle and the catalog:

```powershell
fabric emit --contract "$out\signed.yaml" --profile "$out\compiled.substrate-profile.yaml" --trust-keys "$work\keys\trust" --target local `
  --runtime-binding "$out\runtime-bindings.yaml" --dcp-runtime-bundle "$out\dcp-runtime-bundle.zip" --catalog "$work\catalog" --out "$out\compose"
```

The output lists `foundry-admitted kind=ADMITTED_COMPONENT_OVERLAY`. That folder holds the seven
jars, the three Kotak contracts, their bindings and your public key. `docker-compose.yaml` mounts it
read-only into the Foundry service at `/opt/unfurl/foundry/admitted`. Foundry's own contracts aren't
copied into it.

**Tell Foundry to load it.** Add a binding to your Foundry deployment profile that reads the folder
(Foundry's [admitted component host LLD](../../../../unfurl-foundry/docs/LLD-admitted-component-host.md)
lists every option). `hostPorts` names the Foundry services the adapter uses: authorization policy,
secrets, audit, metrics and read admission.

```yaml
- kind: TOOL_REGISTRY_SOURCE
  name: admitted
  adapter: dcp-admitted-components
  config:
    path: admitted
    trustKeys: dcp-keys
    tenant: demo
    hostPorts: { policy: policy, secrets: secrets, audit: audit, metrics: metrics, admission: admission }
```

If you pass `--foundry-deployment-root` to `emit`, it checks for this binding and fails if it's
missing, so you don't ship an overlay that nothing reads. When Foundry starts, it verifies each
contract and admits the three reads as agent tools. If any check fails, it refuses to start.

**What was checked.** On 12 November 2026, these steps were run on the full catalog from
[Create the catalog](#1-create-the-catalog) through `emit`, and Foundry's admitted-component host loaded
the emitted folder and listed the three Kotak tools. No Foundry
container was started, and no Kotak or agent call was made. A live read needs a real session secret
and policy bindings in your Foundry deployment.

For a host that doesn't run Foundry's admitted-component host, the adapter's
[host connection guide](../../../../unfurl-adapter-kotak-neo/docs/HOST-CONNECTION.md) still applies.

## End to end from the command line: Flow, Foundry, Kotak Neo and Keycloak

This example builds one package from the command line alone, with no Studio. Flow and Foundry run as
containers, Foundry runs the Kotak Neo adapter inside its own process, and Keycloak is an existing
Keycloak bound as the identity provider. A local Keycloak container stands in for yours, so you can
check that Foundry accepts its tokens. It reuses `$work`, `$examples` and the signing
keys from [Compile and sign](#3-compile-and-sign), and needs the catalog from
[Create the catalog](#1-create-the-catalog) plus the seven Kotak jars from the
[Kotak Neo example](#example-add-the-kotak-neo-adapter).

**What you write.** [needs/flow-foundry-kotak-identity.yaml](needs/flow-foundry-kotak-identity.yaml)
asks for `agent.run`, `workflow.execute`, the three Kotak reads and `identity-context`.
[deployment-policy-external-keycloak.yaml](deployment-policy-external-keycloak.yaml) keeps Flow and
Foundry as containers and binds Keycloak to the one you run. The Kotak adapter supports neither
shape in that policy, so it keeps its own in-process shape, and Fabric places it in Foundry.

```powershell
$needs = "$examples\needs\flow-foundry-kotak-identity.yaml"
$policy = "$examples\deployment-policy-external-keycloak.yaml"
$e2e = "$work\e2e"
fabric plan --catalog "$work\catalog" --needs $needs
```

```text
EXACT
cand-… entries=com.unfurl.adapters.keycloak:unfurl-adapter-keycloak:0.1.0,com.unfurl.adapters.kotak:kotak-neo-core:0.1.0-SNAPSHOT,com.unfurl.flow:unfurl-flow:0.1.0,com.unfurl.foundry:unfurl-foundry:0.1.0-SNAPSHOT
hosted: com.unfurl.adapters.kotak:kotak-neo-core:0.1.0-SNAPSHOT by com.unfurl.foundry:unfurl-foundry:0.1.0-SNAPSHOT
deployment keys: unfurl://component/kotak-neo-adapter requires accountRef (string), sessionRef (secret-ref); may change maxBytes (tighten), maxRows (tighten), timeoutMs (tighten)
```

**Compile and sign.** `--dcp-runtime-bundle` writes the signed contracts that Flow and Foundry load,
so this route needs no Studio:

```powershell
fabric compile --catalog "$work\catalog" --needs $needs --deployment-policy $policy --out "$e2e\compiled.yaml"
fabric sign --contract "$e2e\compiled.yaml" --key "$work\keys\private.pem" --public-key "$work\keys\public.pem" --out "$e2e\signed.yaml" --dcp-runtime-bundle "$e2e\dcp-runtime-bundle.zip" --catalog "$work\catalog"
fabric verify --contract "$e2e\signed.yaml" --trust-keys "$work\keys\trust" --catalog "$work\catalog"
```

`compile` prints where each component runs:

```text
deploymentShapes={com.unfurl.adapters.kotak:kotak-neo-core:0.1.0-SNAPSHOT=IN_PROCESS_LIBRARY, com.unfurl.foundry:unfurl-foundry:0.1.0-SNAPSHOT=CONTAINERIZED_SERVICE, com.unfurl.flow:unfurl-flow:0.1.0=CONTAINERIZED_SERVICE, com.unfurl.adapters.keycloak:unfurl-adapter-keycloak:0.1.0=MANAGED_EXTERNAL_ADAPTER}
```

`verify` should end with `overallOk=true`.

**Start a Keycloak.** [keycloak/demo-realm.json](keycloak/demo-realm.json) is a development realm
named `demo`. Keycloak imports it at startup. It has:
- a `foundry` client, the token audience;
- a confidential `flow` client, allowed to exchange tokens, whose service account holds the three
  Kotak read permissions;
- a public `gateway` client that stands in for the app your users sign in to;
- a user `alice`, allowed to run agents.

If you already run a Keycloak with such a realm, skip this step and use its issuer below.

The example uses the `quay.io/keycloak/keycloak:26.8.2` image. Flow calls a secured Foundry by
exchanging the caller's token, which needs Keycloak 26.2 or later.

```powershell
$bytes = New-Object byte[] 18
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$kcAdminPassword = [Convert]::ToBase64String($bytes)

Assert-PortFree 8180
docker run -d --name unfurl-keycloak -p 127.0.0.1:8180:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e "KC_BOOTSTRAP_ADMIN_PASSWORD=$kcAdminPassword" `
    -v "$((Resolve-Path "$examples\keycloak").Path):/opt/keycloak/data/import:ro" quay.io/keycloak/keycloak:26.8.2 start-dev --import-realm --hostname=http://localhost:8180
$keycloak = 'http://127.0.0.1:8180'
(Wait-ForHealth "$keycloak/realms/demo/.well-known/openid-configuration").issuer
```

It prints `http://localhost:8180/realms/demo`, the issuer you bind next. `--hostname` pins it.
Without it, Keycloak builds the issuer from the address each request uses, and a token fetched from
`127.0.0.1` wouldn't match. The guide calls `127.0.0.1` because Windows PowerShell tries IPv6 first
for `localhost`, and Docker publishes the port on IPv4.

**Give Flow its identity settings.** The needs ask for `identity-context`, so Flow calls Foundry
with a token exchanged for each caller, and refuses to start without the settings for that exchange.
The identity binding names them by reference: the token endpoint as
`config://demo/identity/token-endpoint`, Flow's client credentials as
`secret://demo/identity/flow-client`. Flow reads them from two folders laid out as
`<folder>\<tenant>\<path>`, which you pass to `deploy` below.

The block reads the `flow` client's secret, which Keycloak generated on import, and writes it to the
secrets folder. It also gives `alice` a random password. Keep `$e2e\flow-secrets` out of version
control.

```powershell
$admin = Invoke-RestMethod -Method Post "$keycloak/realms/master/protocol/openid-connect/token" -Body @{ client_id = 'admin-cli'; username = 'admin'; password = $kcAdminPassword; grant_type = 'password' }
$adminHeaders = @{ Authorization = "Bearer $($admin.access_token)" }
$flowClient = (Invoke-RestMethod "$keycloak/admin/realms/demo/clients?clientId=flow" -Headers $adminHeaders)[0]
$flowSecret = (Invoke-RestMethod "$keycloak/admin/realms/demo/clients/$($flowClient.id)/client-secret" -Headers $adminHeaders).value

New-Item -ItemType Directory -Force "$e2e\flow-config\demo\identity", "$e2e\flow-secrets\demo\identity" | Out-Null
Set-Content "$e2e\flow-config\demo\identity\token-endpoint" 'http://keycloak:8080/realms/demo/protocol/openid-connect/token' -Encoding ascii -NoNewline
Set-Content "$e2e\flow-secrets\demo\identity\flow-client" (@{ clientId = 'flow'; clientSecret = $flowSecret } | ConvertTo-Json -Compress) -Encoding ascii -NoNewline

$bytes = New-Object byte[] 18
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$alicePassword = [Convert]::ToBase64String($bytes)
$alice = (Invoke-RestMethod "$keycloak/admin/realms/demo/users?username=alice&exact=true" -Headers $adminHeaders)[0]
Invoke-RestMethod -Method Put "$keycloak/admin/realms/demo/users/$($alice.id)/reset-password" -Headers $adminHeaders -ContentType 'application/json' `
    -Body (@{ type = 'password'; value = $alicePassword; temporary = $false } | ConvertTo-Json) | Out-Null
```

The token endpoint uses the host name `keycloak`, because Flow reaches Keycloak from inside the
package's network. [Have Flow call the secured Foundry](#have-flow-call-the-secured-foundry)
attaches the Keycloak container to that network under that name.

**Create the runtime bindings.** Two kinds of values are needed:
- The identity settings of the Keycloak realm, as in [Publish a reusable assembly](#publish-a-reusable-assembly).
  For your own Keycloak, use your realm's issuer.
- The Kotak account alias, and a reference to the secret that will hold its session.

```powershell
$identity = @(
    '--identity-issuer', 'http://localhost:8180/realms/demo'
    '--identity-audiences', 'urn:unfurl:foundry=foundry'
    '--identity-tenant-claim', 'tenant', '--identity-roles-claim', 'roles', '--identity-permissions-claim', 'permissions'
    '--identity-token-endpoint-ref', 'config://demo/identity/token-endpoint'
    '--identity-verification-keys-ref', 'config://demo/identity/verification-keys'
    '--identity-client-credentials-ref', 'secret://demo/identity/flow-client'
)
$kotak = 'unfurl://component/kotak-neo-adapter'
fabric runtime-bindings --signed-contract "$e2e\signed.yaml" --tenant demo --environment local `
  --flow-base-url http://flow:8080 --foundry-base-url http://foundry:7979 --out "$e2e\runtime-bindings.yaml" @identity `
  --catalog "$work\catalog" --component-config "$kotak#accountRef=primary-equity" `
  --component-config "$kotak#sessionRef=secret://demo/kotak/primary-equity-session"
```

It prints `bindings=14`, one for each contract.

**Prepare Foundry's deployment folder.** Foundry needs a profile that loads the adapter.
[foundry-kotak-development-profile.yaml](foundry-kotak-development-profile.yaml) is Foundry's
development profile with seven more bindings:

- the host services the adapter uses: authorization policy, audit, metrics, read admission, and the
  development profile's existing secrets;
- the keys that verify the adapter's contracts;
- an admitted-component tool source, combined with the deployment's own tools.

Work on a copy, so your checkout stays unchanged. The copy is made once; copying into an existing
folder would nest a second copy inside it.

```powershell
if (-not (Test-Path "$work\foundry-deployment")) { Copy-Item -Recurse unfurl-foundry\deployment "$work\foundry-deployment" }
Copy-Item "$examples\foundry-kotak-development-profile.yaml" "$work\foundry-deployment\profiles\"
```

**Export.** Flow and Foundry go to Compose, and Keycloak to the `remote` target, so use `deploy` with
two targets. Pass Flow's inputs, its two identity folders, the catalog for the adapter, and the
Foundry folder.

```powershell
fabric deploy --contract "$e2e\signed.yaml" --profile "$e2e\compiled.substrate-profile.yaml" --trust-keys "$work\keys\trust" `
  --targets "$examples\target-local-images.yaml,remote" --runtime-binding "$e2e\runtime-bindings.yaml" `
  --dcp-runtime-bundle "$e2e\dcp-runtime-bundle.zip" --flow-workflows "$examples\workflows" `
  --flow-config "$e2e\flow-config" --flow-secrets "$e2e\flow-secrets" `
  --foundry-deployment-root "$work\foundry-deployment" --catalog "$work\catalog" --out "$e2e\deploy"
```

```text
targets=2
targetKind=local-compose dir=local-compose ownedShapes=[IN_PROCESS_LIBRARY, CONTAINERIZED_SERVICE] subManifestSha256=…
targetKind=remote dir=remote ownedShapes=[MANAGED_EXTERNAL_ADAPTER] subManifestSha256=…
```

`deploy\local-compose\` holds:
- `docker-compose.yaml`, which mounts the two identity folders read-only into Flow, at
  `/opt/unfurl/flow-config` and `/opt/unfurl/flow-secrets`;
- `flow-deployment\`, the Flow folder with the signed contracts, bindings, workflows and trust keys;
- `foundry-admitted\`, the adapter's seven jars, its three contracts, their bindings and your public
  key, which Compose mounts read-only into Foundry.

`deploy\remote\` holds Keycloak's binding and a reachability probe for your Keycloak. Its
`endpointRef` reads `UNBOUND_ENDPOINT_REF`, because the Keycloak adapter's catalog entry names no
endpoint. The realm that Flow and Foundry use is the issuer you bound with `runtime-bindings`.

`deploy` checks two things before writing anything:
- If no profile in the Foundry folder binds the admitted-component source, it stops, so you can't
  ship an overlay that nothing reads.
- If the bundle has the identity contract and either identity folder is missing, it stops and names
  the flag. It copies nothing from those folders: the secret stays where you put it.

**Build the images and start it.** Build both images as in [Export a package that starts](#export-a-package-that-starts),
from code that includes the in-process hosting changes. Then start the package with one more override.
[compose-development-kotak.yaml](compose-development-kotak.yaml) selects the Kotak profile. Like
`compose-development.yaml`, this runs with **no authentication**, on this machine only.

```powershell
$key = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($key)
$env:UNFURL_FOUNDRY_CREDENTIAL_KEY = [Convert]::ToBase64String($key)

function e2e-compose {
    docker compose -p foundry-kotak -f "$e2e\deploy\local-compose\docker-compose.yaml" -f "$examples\compose-development.yaml" -f "$examples\compose-development-kotak.yaml" @args
}

Assert-PortFree 8080
Assert-PortFree 7979
e2e-compose up -d --pull never --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) {
    e2e-compose ps --all
    e2e-compose logs --tail 40
    e2e-compose down
    throw 'the package did not start'
}
Invoke-RestMethod http://127.0.0.1:7979/health/ready
e2e-compose logs foundry | Select-String 'component.admit'
```

`/health/ready` should report `UP`, including `TOOL_REGISTRY_SOURCE:admitted`. Foundry's log shows a
`component.admit` fact for each Kotak read, ending in `SUCCEEDED`. Foundry refuses to start if any
contract, pin or configuration check fails. Stop the package with `e2e-compose down`.

**Turn on authentication.** The package above runs Foundry without authentication. To have Foundry
check Keycloak's tokens:

1. Export the realm's signing key into Foundry's `identity\keys\` folder, named by its key id.
2. Switch Foundry to [foundry-kotak-secured-profile.yaml](foundry-kotak-secured-profile.yaml).
   That is Foundry's secured profile, with the same Kotak bindings and with its token check set to
   the demo realm and audience `foundry`.
3. Restart the package with [compose-secured-kotak.yaml](compose-secured-kotak.yaml).

```powershell
e2e-compose down

$jwk = (Invoke-RestMethod "$keycloak/realms/demo/protocol/openid-connect/certs").keys | Where-Object { $_.use -eq 'sig' -and $_.alg -eq 'RS256' }
$lines = ($jwk.x5c[0] -split '(.{64})' | Where-Object { $_ }) -join "`n"
Set-Content -Path "$e2e\keycloak-signing.crt" -Value "-----BEGIN CERTIFICATE-----`n$lines`n-----END CERTIFICATE-----" -Encoding ascii
& $openssl x509 -in "$e2e\keycloak-signing.crt" -pubkey -noout -out "$work\foundry-deployment\identity\keys\$($jwk.kid).pem"
Copy-Item "$examples\foundry-kotak-secured-profile.yaml" "$work\foundry-deployment\profiles\"

function e2e-compose-secured {
    docker compose -p foundry-kotak -f "$e2e\deploy\local-compose\docker-compose.yaml" -f "$examples\compose-development.yaml" -f "$examples\compose-secured-kotak.yaml" @args
}
e2e-compose-secured up -d --pull never --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { e2e-compose-secured logs --tail 40; e2e-compose-secured down; throw 'the package did not start' }
```

Now get a token as Flow's service account and ask Foundry for the tenant's tools. The admin calls
only read the `flow` client's secret, which Keycloak generated when it imported the realm.

```powershell
$admin = Invoke-RestMethod -Method Post "$keycloak/realms/master/protocol/openid-connect/token" -Body @{ client_id = 'admin-cli'; username = 'admin'; password = $kcAdminPassword; grant_type = 'password' }
$adminHeaders = @{ Authorization = "Bearer $($admin.access_token)" }
$flowClient = (Invoke-RestMethod "$keycloak/admin/realms/demo/clients?clientId=flow" -Headers $adminHeaders)[0]
$flowSecret = (Invoke-RestMethod "$keycloak/admin/realms/demo/clients/$($flowClient.id)/client-secret" -Headers $adminHeaders).value
$token = (Invoke-RestMethod -Method Post "$keycloak/realms/demo/protocol/openid-connect/token" -Body @{ grant_type = 'client_credentials'; client_id = 'flow'; client_secret = $flowSecret }).access_token

$tools = Invoke-RestMethod http://127.0.0.1:7979/management/tools -Headers @{ Authorization = "Bearer $token" }
$tools.bindings | Where-Object bindingType -eq 'dcp-admitted-component' | Select-Object name, binding
try { Invoke-RestMethod http://127.0.0.1:7979/management/tools | Out-Null } catch { 'without a token: ' + [int]$_.Exception.Response.StatusCode }
```

```text
name                        binding
----                        -------
gtt.instructions.list       unfurl://component/kotak-neo-adapter@0.1.0
portfolio.holdings.list     unfurl://component/kotak-neo-adapter@0.1.0
portfolio.mtf-holdings.list unfurl://component/kotak-neo-adapter@0.1.0

without a token: 401
```

How Foundry handled each request:
- **The token:** Foundry checked it offline against the exported key, for the issuer, the `foundry`
  audience, the `demo` tenant and the `foundry.tool.read` permission, then returned the tenant's
  tools, the three admitted Kotak reads among them.
- **Without a token, or with a changed one:** Foundry answers `401`. Its log records
  `request.authenticate` `DENIED` with `TOKEN_MISSING` or `TOKEN_SIGNATURE`.
- **Rotation:** the key is loaded at startup, so when Keycloak rotates its key, export it again and
  restart Foundry.
### Have Flow call the secured Foundry

Flow calls Foundry on behalf of whoever calls Flow. Flow takes the caller's token from
`Authorization: Bearer`, exchanges it at Keycloak for a token for Foundry, and calls Foundry with
that one. Here `alice` signs in through the `gateway` client, whose tokens name `flow` as an
audience, as Keycloak requires before Flow may exchange them.

First attach the Keycloak container to the package's network as `keycloak`, the host name in Flow's
token endpoint:

```powershell
docker network connect --alias keycloak foundry-kotak_default unfurl-keycloak

$aliceToken = (Invoke-RestMethod -Method Post "$keycloak/realms/demo/protocol/openid-connect/token" -Body @{ grant_type = 'password'; client_id = 'gateway'; username = 'alice'; password = $alicePassword; scope = 'openid' }).access_token
$start = @{ workflowId = 'agent-smoke'; workflowVersion = '1.0.0'; input = @{} } | ConvertTo-Json
$asAlice = @{ Authorization = "Bearer $aliceToken"; 'X-Unfurl-Tenant' = 'demo'; 'X-Unfurl-User' = 'alice'; 'X-Correlation-Id' = 'guide-run-1' }

$run = Invoke-RestMethod -Method Post http://127.0.0.1:8080/executions -Headers $asAlice -ContentType 'application/json' -Body $start
$run.nodes.'ask-agent' | Select-Object status, failureCode
e2e-compose-secured logs foundry | Select-String '"operation":"agent.run"' | Select-Object -Last 2
```

```text
status failureCode
------ -----------
FAILED AGENT_CONTRACT_MISMATCH

foundry-1  | {"type":"foundry.audit",…,"operation":"agent.run","outcome":"REQUESTED",…,"tenantId":"demo","userId":"945bd317-…","correlationId":"guide-run-1",…}
foundry-1  | {"type":"foundry.audit",…,"operation":"agent.run","outcome":"FAILED",…,"failureCode":"AGENT_CONTRACT_MISMATCH","tenantId":"demo","userId":"945bd317-…",…}
```

What happened:
- **The exchange:** Flow exchanged `alice`'s token for one with the `foundry` audience, using its
  client credentials from the secrets folder. It sent Foundry only that token, the correlation id
  and the trace context, not its own idea of who the caller is.
- **Foundry:** it verified the exchanged token offline, and recorded `agent.run` as `REQUESTED` for
  tenant `demo` and `alice`'s user id, after checking her `foundry.agent.run` permission.
- **The run:** Foundry then refused it with `AGENT_CONTRACT_MISMATCH`. This has nothing to do with
  identity: see [Current limits](#current-limits).
- **The `X-Unfurl-Tenant` header** names the tenant whose settings Flow reads, so it must be `demo`,
  the tenant in the binding's references.

Without a token, Flow doesn't call Foundry at all:

```powershell
$noToken = Invoke-RestMethod -Method Post http://127.0.0.1:8080/executions -Headers @{ 'X-Unfurl-Tenant' = 'demo'; 'X-Unfurl-User' = 'alice' } -ContentType 'application/json' -Body $start
$noToken.nodes.'ask-agent' | Select-Object status, failureCode
```

```text
status failureCode
------ -----------
FAILED FLOW_DCP_SUBJECT_TOKEN_UNAVAILABLE
```

Flow holds the caller's token in memory for the length of that one request. A workflow resumed
later, or run by a timer, has no caller token, and fails the same way rather than calling without
one. If Keycloak refuses the exchange, the node fails with `FLOW_DCP_TOKEN_EXCHANGE_FAILED`.

To stop everything:

```powershell
docker network disconnect foundry-kotak_default unfurl-keycloak
e2e-compose-secured down
docker rm -f unfurl-keycloak
```

**To make a real Kotak read,** Foundry needs the session that `sessionRef` names. Put the session
document at `$work\foundry-deployment\secrets\demo\kotak\primary-equity-session`. The adapter's
[integration guide](../../../../unfurl-adapter-kotak-neo/docs/INTEGRATION.md#mounted-session-document-format)
describes the document. Never commit that file. The reads are then available as tools to Foundry's agents. A
caller needs each read's permission, which the authorization policy checks.

**What was checked.** On 12 November 2026, every command above was run on the full catalog, with
images built from the current code (Docker 29.8.2, Compose 5.5.1):

- Both containers became `healthy`.
- Foundry's `/health/ready` reported `UP`, with `TOOL_REGISTRY_SOURCE:admitted` `UP`.
- Foundry's log showed `component.admit` `SUCCEEDED` for each of the three Kotak contracts.
- Flow's log had no errors, and Flow reached Foundry at `http://foundry:7979`.
- Keycloak 26.8.2 imported the realm with the issuer bound above.
- With the secured profile, Foundry accepted the realm's token and listed the three Kotak tools. It
  answered `401` without a token and with a changed one.
- Flow exchanged `alice`'s token at Keycloak, and Foundry recorded `agent.run` for tenant `demo` and
  her user id. Without a token, Flow failed the node with `FLOW_DCP_SUBJECT_TOKEN_UNAVAILABLE` and
  made no call.

Not covered:
- **A Kotak read:** it needs a real session.
- **An agent run:** Foundry refuses the composition's `agent.run` contract (see
  [Current limits](#current-limits)), and a run would also need a model provider.

## End to end with Studio: Flow, Foundry, Kotak Neo and Keycloak

This is the same package as the [command-line example](#end-to-end-from-the-command-line-flow-foundry-kotak-neo-and-keycloak),
but the composition and signing happen in Studio:
- Studio admits the jars, including the Kotak adapter's six companions.
- It places the adapter in Foundry, pins the companions, signs the contract, and writes the runtime
  bundle and an audit of the hosting.

From the runtime bindings on, the steps are the command-line example's. They run unchanged, because
the downloads below use the same file names.

**Start Studio.** Run the first block of [Export a package that starts](#export-a-package-that-starts).
It starts a Studio on port 7879, signing with your keys, and defines `$api`, `$tenant` and `studio`.
This example uses its own assembly:

```powershell
$assembly = 'kotak-platform'
$e2e = "$work\e2e-studio"
New-Item -ItemType Directory -Force $e2e | Out-Null
```

**Upload the jars.** Upload the four components and the adapter's six companions. Studio reads the
claim from each component jar. A jar without a claim and with exactly one `pom.properties` is admitted
as a companion and stored for this tenant. It doesn't appear in the catalog, because you can't choose
it on its own.

```powershell
$jars = 'unfurl-flow-0.1.0-SNAPSHOT.jar', 'unfurl-foundry-0.1.0-SNAPSHOT.jar', 'unfurl-adapter-keycloak-0.1.0-SNAPSHOT.jar',
        'kotak-neo-core-0.1.0-SNAPSHOT.jar', 'kotak-neo-http-0.1.0-SNAPSHOT.jar', 'kotak-neo-foundry-0.1.0-SNAPSHOT.jar',
        'jackson-core-2.17.2.jar', 'jackson-databind-2.17.2.jar', 'jackson-annotations-2.17.2.jar', 'jackson-datatype-jsr310-2.17.2.jar'
$artifacts = foreach ($name in $jars) {
    $path = "$work\catalog\$name"
    $sha = 'sha256:' + (Get-FileHash $path -Algorithm SHA256).Hash.ToLower()
    '{"fileName":"' + $name + '","sha256":"' + $sha + '","artifactBase64":"' +
        [Convert]::ToBase64String([IO.File]::ReadAllBytes($path)) + '"}'
}
$body = '{"assemblyId":"' + $assembly + '","artifacts":[' + ($artifacts -join ',') + ']}'
$admission = Invoke-RestMethod -Method Post -Uri "$api/studio/tenants/$tenant/catalog/admissions" `
    -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$admission.results | Select-Object fileName, status, catalogEntryId
```

All ten show `VERIFIED`. The four components get `uploaded:` entries. The companions get
`companion:<coordinates>`, for example
`companion:com.unfurl.adapters.kotak:kotak-neo-http:0.1.0-SNAPSHOT`. If you upload a different jar
under coordinates already admitted, Studio refuses it with `CATALOG_COMPANION_CONFLICT`, naming the
stored file and its hash.

**Create the assembly and add the components.**

```powershell
$null = studio "/studio/tenants/$tenant/assemblies" @{ assemblyId = $assembly; targetApplicationName = 'Kotak Platform'; defaultDeploymentTarget = 'containerized-local' }
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

Each of the four components shows `VALID`. If you uploaded only the components, compile stops later
with `CATALOG_COMPANION_MISSING`, naming the missing jars. Upload them and compile again.

**Compile and download.** Send the same policy as
[deployment-policy-external-keycloak.yaml](deployment-policy-external-keycloak.yaml), so Keycloak is
your existing one. Save the signed contract, profile and bundle under the command-line example's
names:

```powershell
$policy = @{
    preferredShapes = @('MANAGED_EXTERNAL_ADAPTER', 'CONTAINERIZED_SERVICE'); disallowedShapes = @(); requireIsolationForCapabilityPatterns = @()
    runtime = @{ springBoot = $true; kubernetes = $true; serviceMesh = $true }
}
$compile = studio "/studio/tenants/$tenant/assemblies/$assembly/sessions/$session/compile" @{ tenantId = $tenant; assemblyId = $assembly; sessionId = $session; expectedRevision = $revision; sign = $true; deploymentPolicy = $policy }
$compile.status

function Save-Artifact($artifact, $name) {
    Invoke-WebRequest "$api$($artifact.url)" -OutFile "$e2e\$name" -UseBasicParsing
    $hash = 'sha256:' + (Get-FileHash "$e2e\$name" -Algorithm SHA256).Hash.ToLower()
    if ($hash -ne $artifact.sha256) { throw "hash mismatch: $name" }
    "saved $name"
}
Save-Artifact ($compile.supportArtifacts | Where-Object artifactId -Like 'signed-compiled-contract*') 'signed.yaml'
Save-Artifact $compile.substrateProfileArtifact 'compiled.substrate-profile.yaml'
Save-Artifact ($compile.supportArtifacts | Where-Object artifactId -Like 'dcp-runtime-bundle*') 'dcp-runtime-bundle.zip'
Save-Artifact ($compile.diagnosticArtifacts | Where-Object url -Like '*hosted-components.json*') 'hosted-components.json'

$hosted = Get-Content "$e2e\hosted-components.json" -Raw | ConvertFrom-Json
$hosted | Select-Object capability, host, @{ n = 'companions'; e = { $_.companions.Count } }

& $shutdown --port 7879
```

`$compile.status` is `COMPILED`. `hosted-components.json` is Studio's audit of the hosting. For each
hosted read it records the host, the consumer named in the contract, and each companion with its
SHA-256:

```text
capability                  host                                             companions
----------                  ----                                             ----------
gtt.instructions.list       com.unfurl.foundry:unfurl-foundry:0.1.0-SNAPSHOT          6
portfolio.holdings.list     com.unfurl.foundry:unfurl-foundry:0.1.0-SNAPSHOT          6
portfolio.mtf-holdings.list com.unfurl.foundry:unfurl-foundry:0.1.0-SNAPSHOT          6
```

**Finish with the command-line steps.** Continue in the
[command-line example](#end-to-end-from-the-command-line-flow-foundry-kotak-neo-and-keycloak). Use
this `$e2e`, which now holds Studio's files.

1. **Start a Keycloak**, unless it is still running, and **Give Flow its identity settings**.
2. **Create the runtime bindings.** Studio signed with your key, so
   `fabric verify --contract "$e2e\signed.yaml" --trust-keys "$work\keys\trust" --catalog "$work\catalog"`
   ends with `overallOk=true`.
3. **Prepare Foundry's deployment folder.**
4. **Export.**
5. **Start it**, then **Turn on authentication** if you want Foundry to check Keycloak's tokens,
   and **Have Flow call the secured Foundry**.

`runtime-bindings` and `deploy` read the adapter's packaged settings and jars from your local
catalog, the same files you uploaded. Studio's agent-driven deployment tools apply the same rules
(`fabric.runtime-binding-generate` and `fabric.foundry-root-assemble`; see the
[Studio API LLD](../../LLD-fabric-studio-api.md)).

**What was checked.** On 12 November 2026, every block above was run as written, followed by the
command-line steps:
- All ten uploads were `VERIFIED`, six of them as companions, and the four components were `VALID`.
- The compile was `COMPILED`, every download matched its hash, and the audit listed the three reads
  hosted by Foundry with six companions each.
- `verify` printed `overallOk=true`, and `runtime-bindings` wrote 14 bindings.
- After `deploy`, both containers were `healthy`. Foundry reported the admitted source `UP` and logged
  `component.admit` `SUCCEEDED` for each Kotak contract.

The authentication step was checked on the command-line route; it uses the same files.

## Publish a reusable assembly

Publishing stores a finished combination as a versioned, signed release. Anyone can verify it without
the original source or catalog, and each environment gets its own signed bindings.

Start with `agent-platform.definition.yaml` and replace its identity and publisher with your own. Each
offer in it must exist at that exact version in the compiled contract. The needs file asks for
identity, so every environment's bindings need the identity settings. Locally they point at a
Keycloak on `localhost`, the one place plain `http` is accepted.

```powershell
$uri = 'urn:example:assembly:agent-platform'
$a = "$work\publish"
$source = "$a\workspace\assemblies\flow-foundry\agent-platform"
$keys = @('--key', "$work\keys\private.pem", '--public-key', "$work\keys\public.pem", '--trust-keys', "$work\keys\trust")

fabric assembly-init --workspace "$a\workspace" --family flow-foundry --name agent-platform --definition "$examples\agent-platform.definition.yaml" --needs "$examples\needs\foundry-flow-identity.yaml" --profile default

fabric compile --catalog "$work\catalog" --needs "$source\profiles\default.yaml" --out "$a\build\compiled.yaml"
fabric sign --contract "$a\build\compiled.yaml" --key "$work\keys\private.pem" --public-key "$work\keys\public.pem" --out "$a\build\signed.yaml"

fabric assembly-publish --source $source --profile default --version 1.0.0 --signed-contract "$a\build\signed.yaml" --substrate-profile "$a\build\compiled.substrate-profile.yaml" --catalog "$work\catalog" --repository "$a\releases" @keys
fabric assembly-verify --repository "$a\releases" --uri $uri --version 1.0.0 --trust-keys "$work\keys\trust"

$identityLocal = @(
    '--identity-issuer', 'http://localhost:8080/realms/demo'
    '--identity-audiences', 'urn:unfurl:foundry=foundry'
    '--identity-tenant-claim', 'tenant', '--identity-roles-claim', 'roles', '--identity-permissions-claim', 'permissions'
    '--identity-token-endpoint-ref', 'config://demo/identity/token-endpoint'
    '--identity-verification-keys-ref', 'config://demo/identity/verification-keys'
    '--identity-client-credentials-ref', 'secret://demo/identity/flow-client'
)
fabric runtime-bindings --signed-contract "$a\build\signed.yaml" --tenant demo --environment local --out "$a\build\bindings.yaml" @identityLocal
fabric assembly-bind --repository "$a\releases" --deployment-repository "$a\deployments" --uri $uri --version 1.0.0 --tenant demo --environment local --revision r1 --bindings "$a\build\bindings.yaml" @keys
fabric assembly-verify-binding --repository "$a\releases" --deployment-repository "$a\deployments" --uri $uri --version 1.0.0 --tenant demo --environment local --revision r1 --trust-keys "$work\keys\trust"
```

Each publish, bind and verify step should end with `verified=true`. That confirms the files and
signatures. It doesn't check that endpoints are reachable or that secrets exist.

**Bind another environment.** Each environment gets its own bindings, with the identity settings of
that environment's realm. Fabric never guesses them, so leaving any out is an error. The references
are names your deployment resolves; they never contain the keys or secrets themselves.

```powershell
$identity = @(
    '--identity-issuer', 'https://idp.example.com/realms/demo'
    '--identity-audiences', 'urn:unfurl:foundry=foundry'
    '--identity-tenant-claim', 'tenant', '--identity-roles-claim', 'roles', '--identity-permissions-claim', 'permissions'
    '--identity-token-endpoint-ref', 'config://demo/identity/token-endpoint'
    '--identity-verification-keys-ref', 'config://demo/identity/verification-keys'
    '--identity-client-credentials-ref', 'secret://demo/identity/flow-client'
)
fabric runtime-bindings --signed-contract "$a\build\signed.yaml" --tenant demo --environment production --out "$a\build\bindings-production.yaml" @identity
fabric assembly-bind --repository "$a\releases" --deployment-repository "$a\deployments" --uri $uri --version 1.0.0 --tenant demo --environment production --revision r1 --bindings "$a\build\bindings-production.yaml" @keys
fabric assembly-verify-binding --repository "$a\releases" --deployment-repository "$a\deployments" --uri $uri --version 1.0.0 --tenant demo --environment production --revision r1 --trust-keys "$work\keys\trust"
```

| Setting | Meaning |
|---|---|
| `--identity-issuer` | Your identity provider's issuer. It must be `https`, except on `localhost`. |
| `--identity-audiences` | `claimUri=audience` pairs, separated by commas: exactly one for each component whose claim needs `identity-context`, and none for anything else. With Flow and Foundry that is Foundry alone, `urn:unfurl:foundry`. |
| `--identity-tenant-claim`, `--identity-roles-claim`, `--identity-permissions-claim` | The token claims that hold the tenant, roles and permissions. |
| `--identity-token-endpoint-ref` | Reference to the token endpoint that Flow uses to exchange tokens. |
| `--identity-verification-keys-ref` | Reference to the identity provider's public signing keys, which Foundry checks tokens against. |
| `--identity-client-credentials-ref` | Secret reference to Flow's client credentials at the identity provider. |

Releases can't be changed. If the source or output changes, publish a new version. If only the
bindings change, use a new revision.

| Family | Must contain | Status |
|---|---|---|
| `flow-foundry` | `workflow.execute` and `agent.run` | Works |
| `flow-only` | `workflow.execute`, no `agent.run` | Works |
| `foundry-only` | `agent.run`, no `workflow.execute` | Works, with `foundry-only.yaml` as the profile |

No family requires identity. A release has it when its profile's needs ask for `identity-context`.

To publish a `foundry-only` assembly, start from `agent-only.definition.yaml`, which offers
`agent.run` alone, and use the same commands with `--family foundry-only --name agent-only`,
`--needs "$examples\needs\foundry-only.yaml"` and `$uri = 'urn:example:assembly:agent-only'`. Its
profile has no `identity-context`, so `runtime-bindings` needs no identity settings. Publishing a
compiled contract that includes Flow into this family fails, because the family rejects
`workflow.execute`.

## Current limits

**These stop a path entirely:**

| Limit | Why | What to do |
|---|---|---|
| Studio's `needs/extract` results can't compile | It asks for `^1`, but the components are `0.1.0`, so compile returns `NO_MATCH` | Leave `needsId` out of the session, as this guide does. Compile then checks the selected components' dependencies, not a needs list |
| A package with the Keycloak container can't start | The emitter writes a placeholder Keycloak service; it doesn't yet set the Keycloak image or run the adapter's realm provisioning | Leave `identity-context` out of the needs for local runs, or bind an existing Keycloak with `deployment-policy-external-keycloak.yaml` |
| Flow's `agent.run` is refused by Foundry with `AGENT_CONTRACT_MISMATCH` | Foundry's `/dcp/agent.run` serves its own agent contract, not the composition's `agent.run` child contract that Flow calls | None yet. The caller's identity reaches Foundry; the run itself doesn't start |

**These are easy to trip over:**

- Fabric doesn't add a component because another component's claim needs it. `NO_MATCH` then shows
  a `conflict:` line naming the component and what it needs; add that capability to the needs file.
- `fabric scan --out` crashes while writing the index. Run `scan` without `--out`.
- `assembly-init` says only `invalid composite claim` when a claim has a problem. Compare yours with
  `agent-platform.definition.yaml`, which has every required section.
- The exported `docker-compose.yaml` contains the full path to your Foundry deployment folder, so it
  won't work unchanged on another machine.
- The exported `docker-compose.yaml` uses Foundry's secured profile, the image default. On its own it
  needs JWT verification keys in the deployment's `identity/keys/`. Add `compose-development.yaml`
  for local use.
- With `identity-context` in the needs, every call from Flow to Foundry needs a caller token, in
  development mode too. Flow takes the tenant whose settings it reads from the `X-Unfurl-Tenant`
  header, which your gateway sets.

**Before production:**

- This package runs Flow and Foundry without authentication. It isn't safe to expose beyond your own
  machine.
- Generated bindings contain development defaults. Review them and supply real endpoints and secret
  references.
- Provider, audit and secret bindings aren't covered by this guide. The emitter doesn't yet mount
  Keycloak's verification keys into Foundry: the guide exports them by hand.

## Learn more

- [Flowfoundry runbook](../../Flowfoundry.md): the full 18-step export
- [Fabric Studio API](../../LLD-fabric-studio-api.md): every route
- [Reusable assemblies](../../../assemblies/README.md): source layout and storage
- [Foundry deployment](../../../../unfurl-foundry/deployment/README.md): agents, models and tools
