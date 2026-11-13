# HLD: Identity from the needs

Status: proposed, 2026-11-03; revised 2026-11-04; Flow's token exchange (§8) and the emitter's Flow mounts
(§7) implemented 2026-11-12; revised 2026-11-13 for both paths (§2a), Flow verification and authorization
(§8) and Foundry's service tokens (§9). Owners: Fabric (needs rule, identity binding rule, runtime
bindings), Keycloak adapter (component and provisioner), Deploy Emitter (realization), Flow (token
exchange, verification, authorization), Foundry (verification, authorization, service tokens).

A composition has authentication and authorization when its DCP configuration asks for it: its
needs require the `identity-context` capability. Every capability in the needs is mandatory, so the
composition then cannot compile, bind or deploy without a complete identity setup. A composition whose
needs leave it out runs without identity in any environment, which is how lightweight Flow and Foundry
deployments work.

The identity provider component is the Keycloak DCP adapter in `unfurl-adapter-keycloak`. This
follows the product architecture (`research/Docs/files/00-product-architecture.md` §5): open-source
Keycloak is integrated through a plain Keycloak/OIDC adapter, never rebuilt, and is distinct from the
Intelligent Keycloak product.

DCP is a general protocol and has no identity rule. It already requires every child contract of a
composition to be bound, which is all this design needs from it. The only DCP change is general: the
Java `Configuration` record gains the `config_refs` map that the DCP schema (HLD-C2 §C) defines.

## 1. Problem

- Foundry's claim declares `identity-context@v1?transport=in-process&…&owner=customer-idp`. Fabric
  treats `owner=customer-…` dependencies as externally owned and skips them, so nothing ever had to
  provide identity.
- Foundry's secured profile verifies JWTs, but Flow calls Foundry with no token and with its
  `ExecutionContext` in the request body, which the secured profile rejects (`401 TOKEN_MISSING`).
  Flow + Foundry with identity therefore cannot work at all today.
- When a needed capability could not be satisfied, `plan` and `compile` printed `no match` and
  `missing <capability> <range>` without saying why. When a selected component's own claim
  dependency was unmet, they printed only `no match`.

## 2. Decisions

| Topic | Decision |
|---|---|
| When identity is mandatory | When the composition's needs require `identity-context`. Nothing else turns it on: not the environment name, not the assembly family. |
| Unmet needs | Every capability in the needs is mandatory. One that cannot be satisfied fails `plan`, `compile`, `dry-run`, `resolve-deployment` and Studio with a message naming the cause and the fix (§3). |
| Where the rule lives | Fabric. DCP stays a general protocol with no identity-specific validation. |
| Identity carried from Flow to Foundry | On-behalf-of: Flow exchanges the workflow caller's token at the IdP for a Foundry-audience token (OAuth 2.0 Token Exchange, RFC 8693). Foundry authorizes the real user. |
| The Authentication/Authorization component | A DCP adapter for open-source Keycloak (`unfurl-adapter-keycloak`). Keycloak issues the tokens. Two shapes: a Keycloak container that Unfurl deploys (the default), and a Keycloak the customer already runs. Another IdP (Entra ID, Okta, …) federates into Keycloak. |
| Flow's inbound API | Verified by Flow, as Foundry verifies its own: offline, against the identity binding's keys, with tenant, user, roles and permissions taken only from the token, and every route authorized by a permission (§8). A gateway in front of Flow signs users in and forwards their access token, which Flow also uses as the exchange subject. |
| Service-to-service calls | A service calling another as itself, not for a user, uses its own OAuth 2.0 client-credentials token: Foundry calling Flow's tools (§9). The token carries the permissions granted to the calling service's account, and the callee authorizes them like any other. |
| Without `identity-context` | Everything works without identity (§2a). Absence is never an error. |
| Issuer transport | `https`, except plain `http` on a loopback host (`localhost`, `127.0.0.1`, `::1`) so a Keycloak container on a developer machine works (the OAuth loopback exception, RFC 8252 §8.3). |

## 2a. The contract decides: both paths

Identity is an optional component, so the engineering rule for optional components applies (root
`CLAUDE.md`, rule 6): the signed composition contract alone decides, and both paths always work. A
composition has identity exactly when its contract has an `identity-context` child contract; nothing
else turns identity on or off, and nothing downgrades it while it is on.

**The signed set, not the files present, decides.** A service learns the composition from the DCP runtime
bundle: the signed aggregate root (`contracts/root.frozen.json`) and the signed children
(`contracts/frozen/`). It loads them with DCP's `VerifiedComposition`, which verifies every envelope and
requires the children to be exactly the root's closure. So identity is absent only when the verified
root names no `identity-context` child. Deleting the identity contract's file, or adding an unsigned one,
fails the load instead of changing the answer. No service reads a contract's contents before this load.

| | `identity-context` in the contract | Not in the contract |
|---|---|---|
| Calls into Flow | `Authorization: Bearer` required on every route but health, claim and API docs; verified offline; context from the token only; route permissions enforced (§8) | Unauthenticated, context from the request headers; Flow warns at startup that it runs without authentication |
| Flow → Foundry `agent.run` | The caller's token exchanged for Foundry's audience (§8) | No token; Flow's context in the body |
| Flow → Foundry management (creating Flow's authoring agent) | Flow's client-credentials service token for Foundry's audience (§8) | No token; the tenant and user in `X-Unfurl-Tenant`/`X-Unfurl-User` |
| Foundry → Flow tools | Foundry's client-credentials service token for Flow's audience (§9) | No token |
| Calls into Foundry | The secured profile (`authentication: required`) is mandatory, and its token settings must equal the identity binding (§9) | The profile must have `authentication: disabled` |

A component that cannot take the identity path while identity is in the contract refuses to start; it
never runs a weaker mode instead. Every example and test that exercises identity covers both columns.

## 3. Unmet needs fail with the cause and the fix

Fabric selects components only for the capabilities the needs require. For each required capability
that no allowed catalog entry satisfies, the matcher reports one `UnmetCapabilityRequirement` with
the most specific reason that applies:

| Reason | When | Message (shape) |
|---|---|---|
| `OFFER_DETAILS_UNSATISFIED` | An entry offers the capability at a matching version, but not with the required offer details | `the needs require X ^1 with offer details {…}, but X 1.0.0 (coords) does not offer those details` |
| `VERSION_NOT_OFFERED` | Entries offer the capability only at other versions | `the needs require X ^7, but the catalog offers only X 1.0.0 (coords); change the version range in the needs, or add a component that offers a matching version` |
| `REJECTED_BY_TRUST_POLICY` | Only entries the trust policy rejected offer it | `the needs require X ^1, but only components the trust policy rejected offer it: coords (reasons); change the trust policy, or add an allowed component` |
| `NOT_IN_CATALOG` | Nothing offers it | `the needs require X ^1, but no catalog entry offers X; add a component that offers it to the catalog, or remove it from the needs` |

When every required capability has a provider but no candidate composition is valid, the result
carries the candidates' distinct conflicts instead. Each names its cause:

- `MissingCapabilityConflict`: no selected component offers a needed capability (Studio drafts, where
  the operator chose the components).
- `VersionConflict`: the selected component offers it at another version.
- `DependencyConflict`: a selected component's claim needs a capability nothing selected offers; the
  message names the component and says to add the capability to the needs, because Fabric does not
  add components to satisfy other components' claims.
- Offer-detail, artifact, refusal and binding conflicts, as before.

`NoMatchExplanation` renders these as one line each, `unmet need: …` then `conflict: …`. The CLI
prints `no match` (or `NO_MATCH` for `plan`) followed by those lines, and `compile`, `dry-run` and
`resolve-deployment` exit non-zero with `<operation> failed: no valid composition: <first reason>`.
Studio's deployment resolution returns `NO_MATCH` with the same lines in `details`.

## 4. The identity provider component

The Keycloak adapter, claim `urn:unfurl:adapter:keycloak`, artifact
`com.unfurl.adapters.keycloak:unfurl-adapter-keycloak`:

- `ComponentKind.INFRASTRUCTURE`. It offers one capability, `identity-context@v1`: an authenticated
  subject plus its tenant, roles and permissions claims, in access tokens that Keycloak issues, with
  RFC 8693 token exchange for downstream audiences. Authentication is the verified token, and
  authorization is the permissions in it, which Foundry's existing
  `execution-context-permissions-policy` enforces.
- Keycloak owns user authentication, sessions, federation of upstream identity providers, and token
  issuance. The claim refuses authorization decisions (policy engines and the consuming runtimes
  own them), audit-log storage and workflow orchestration.
- **Shapes.** `CONTAINERIZED_SERVICE` (default, binding mode `CONTAINER`, deployment kind
  `container`): the emitter deploys the official Keycloak image, version-pinned, next to Flow and
  Foundry. `MANAGED_EXTERNAL_ADAPTER` (binding mode `MANAGED_ADAPTER`, deployment kind
  `external_saas`): the customer already runs Keycloak and the adapter connects to it. The deployment
  policy selects the shape, as for any component.
- **Keycloak version.** Token exchange uses Keycloak's standard token exchange (RFC 8693, supported
  from Keycloak 26.2), so the adapter requires 26.2 or later.
- **Dependency.** In production mode, the containerized Keycloak needs a relational database, declared
  as the externally owned `relational-database@v1?transport=jdbc&owner=customer-database`. The external
  shape leaves the database to the customer's Keycloak.

**What the adapter does.** The jar carries the claim plus a provisioner, an Adapter over the Keycloak
Admin REST API. It idempotently ensures, for the bound realm:

- a confidential client for Flow with standard token exchange enabled;
- one client per protected service, whose client id is that service's audience in the identity
  binding (`values.audiences`);
- for each service that calls another as itself (§9), a confidential client with a service account
  holding the callee permissions that service needs, and an audience mapper for the callee: Foundry's
  account holds Flow's tool permissions, with `flow` in its tokens' audience;
- protocol mappers that put tenant, roles and permissions into access tokens under the claim names
  bound in `values.claims`;
- the realm's active signing public keys, exported as PEM files for Foundry's `identity/keys/`.

With the container shape it runs as a one-shot job after Keycloak is healthy. With the external
shape it runs in verify-only mode by default: it checks that the customer realm already meets the
identity binding and reports every difference, and it changes the customer's Keycloak only when the
operator asks for provisioning. Admin credentials always arrive through a secret reference.

A needs file selects the component by requiring `identity-context ^1`. Fabric compiles every selected
offer into a child contract from the host to the provider, so the composition then has an
`identity-context` child contract, and its runtime binding is where the settings in §5 live. The
component also satisfies Foundry's own `identity-context@v1` claim dependency.

## 5. The identity binding (Fabric `IdentityBindingRule`)

Whenever a composition has an `identity-context` contract, its runtime binding, the identity binding,
must carry, with no inline secrets:

| Field | Meaning |
|---|---|
| `configuration.values.issuer` | Issuer URI; `https`, or `http` on a loopback host only. |
| `configuration.values.audiences` | Map from each protected service's claim URI to its token audience. Its keys must be exactly the protected services (below). |
| `configuration.values.claims` | Names of the `tenant`, `roles` and `permissions` claims. |
| `configuration.config_refs.token_endpoint` | Token endpoint used for the exchange. |
| `configuration.config_refs.verification_keys` | The IdP's public signing keys as PEM files, so verification stays offline and needs no JWKS fetch. |
| `provider_instance.credentials_ref` | The host's OAuth 2.0 client credentials at the IdP, which Flow presents when exchanging tokens. |
| `configuration.secret_refs` | One typed `secret://` reference per service that calls others as itself (§8, §9), keyed by its claim URI: its client credentials. Absent when no service does. Never in `values`, which DCP's inline-secret rule refuses. |

**Protected services.** A protected service is a selected component whose claim declares a dependency
on `identity-context`, at any owner marker: it receives tokens and verifies them, so it needs an
audience. Foundry and Flow are both protected services; each claim declares
`identity-context@v1?transport=in-process&shape=com.unfurl.substrate.policy.ExecutionContext&owner=customer-idp`.
The `owner=customer-idp` marker keeps the dependency optional: a composition whose needs leave out
`identity-context` still compiles, and then has no identity (§2a). The Keycloak adapter, which offers
the capability, is not a protected service. The compiler
records this as a general fact on every child contract: the metadata extension `neededBy` lists, sorted,
the claim URIs of the other selected components whose claims depend on that contract's capability. The
signed identity contract therefore carries the protected services, and generation and admission read
them from it rather than from the catalog. A contract without `neededBy` was compiled before this rule
and is refused with a request to compile again; it is never treated as having no protected services.

The audiences must cover the protected services exactly: one audience per protected service, and no
key for anything else (the identity provider, a service that does not take tokens, or a typo). When no
selected component needs identity, `audiences` is empty.

`IdentityBindingRule` (a Specification in `com.unfurl.fabric.runtime`) checks these and reports one
`field path: problem` line per violation. It runs in two places, after DCP's own `validateTree`:

- `RuntimeBindingSetGenerator`, before any YAML is written;
- binding admission (`assembly-bind`, `assembly-verify-binding`), so a hand-edited binding that drops a
  field is refused with `identity binding <id> is incomplete: <violations>`.

The environment plays no part.

## 6. Fabric

- **Needs:** §3. A composition has identity exactly when its needs require `identity-context`.
- **Families:** no assembly family forces identity. A `flow-foundry` or `foundry-only` release whose
  profile omits `identity-context` publishes and binds to any environment without identity.
- **Runtime bindings:** `fabric runtime-bindings` and Studio's binding step take the §5 values as
  explicit operator inputs and emit them on the identity binding:

  | Input | CLI flag | Studio `identity` field |
  |---|---|---|
  | `values.issuer` | `--identity-issuer` | `issuer` |
  | `values.audiences` | `--identity-audiences claimUri=audience,…`, one per protected service | `audiences` (map) |
  | `values.claims.tenant` / `roles` / `permissions` | `--identity-tenant-claim`, `--identity-roles-claim`, `--identity-permissions-claim` | `tenantClaim`, `rolesClaim`, `permissionsClaim` |
  | `config_refs.token_endpoint` | `--identity-token-endpoint-ref` | `tokenEndpointRef` |
  | `config_refs.verification_keys` | `--identity-verification-keys-ref` | `verificationKeysRef` |
  | `provider_instance.credentials_ref` | `--identity-client-credentials-ref` | `clientCredentialsRef` |
  | `secret_refs` | `--identity-service-credentials claimUri=secret://…,…`, one per service that calls others as itself | `serviceCredentials` (map) |

  Generation fails, before any YAML is written, when:
  - the composition has an `identity-context` contract and any input is missing; the message says the
    needs require `identity-context` and lists every missing input;
  - inputs are supplied but the composition has no identity contract (they are never silently
    dropped);
  - the issuer is not `https` (or `http` on a loopback host);
  - a protected service (§5) has no audience, or an audience key is not a protected service.
  - a service-credentials key is not a service that needs identity (one that calls others as itself
    authenticates at the identity provider, so it always is), or a value is not a `secret://` reference.

  All problems are reported together in one message.

  Fabric never fabricates an issuer, an audience or a reference. Studio returns these failures as
  `GAP`.
- **Deployment kind:** every child binding's deployment kind follows the shape Fabric selected in the
  binding plan, not the catalog's default binding mode, which is all the contract transport records.
  For the Keycloak adapter that is `container` for `CONTAINERIZED_SERVICE` and `external_saas` for
  `MANAGED_EXTERNAL_ADAPTER`; the transport mode is used only when the plan has no entry. The identity
  binding's `provider_instance.base_url_ref` is the token endpoint reference, and it has no `base_url`.
- The root binding id ends with the environment slug (previously always `dev`).
- **Consistency:** Fabric does not see the Foundry runtime profile, so the check that a Foundry
  deployment's `IDENTITY` binding equals the identity binding is the Deploy Emitter's (§7).

## 7. Deploy Emitter

Everything in this section applies when the composition has an identity binding; a composition without
one is emitted as today.

- **Container shape:** compose and Kubernetes targets render the Keycloak adapter as the pinned
  official Keycloak image (26.2 or later) plus the adapter's one-shot provisioning job, which runs
  after Keycloak is healthy and before Flow and Foundry start. The job writes the exported signing
  keys to the volume mounted at Foundry's `identity/keys/`. The `local-compose` target runs Keycloak
  in its development mode with an embedded database; every other target requires the
  `relational-database` binding and Keycloak's production mode.
- **External shape:** the `remote` target renders the adapter's binding configuration and a
  reachability probe for the customer Keycloak, and the verify-only adapter run (§4) before Flow and
  Foundry start.
- The emitter gives Flow its identity configuration. Flow resolves the binding's `token_endpoint`,
  `verification_keys` and `credentials_ref` references from two operator-owned folders, which the
  emitter mounts read-only (`flowConfigPath`, `flowSecretsPath`; emitter HLD-E §4.0a). It copies
  nothing from them, and fails emission when either is missing. **Done.**
- **Foundry's part of the composition** (emitter HLD-E §4.0c): the folder the emitter mounts into
  Foundry (`UNFURL_FOUNDRY_COMPOSITION`) holds the signed root, all of its children, the runtime bindings
  and the keys that verify them, so Foundry verifies the set as §2a requires. Foundry serves the
  `agent.run` children it provides and reads identity from the `identity-context` child (§9); other
  children are verified and otherwise ignored. So the refusal of a mismatched profile and the
  consistency check are Foundry's, at startup, against signed inputs. Foundry resolves its own
  `secret_refs` entry and the binding's references from its deployment's `secrets/` and config folders.
- The emitter mounts the verification keys at Foundry's `identity/keys/` (not yet built; the guide
  exports them by hand).

## 8. Flow

These apply when Flow's runtime bindings contain an identity binding. Without one, Flow runs
unauthenticated and calls providers as before (§2a).

- **Verification:** every route but `/health`, `/claim`, `/openapi.*` and `/swagger-ui` requires
  `Authorization: Bearer`. Flow verifies the token offline with the shared verifier
  (`substrate-host-adapters`, the same code as Foundry's `signed-jwt-identity`): signature against the
  binding's `verification_keys`, exact issuer, Flow's entry in `audiences`, and expiry. Tenant, user,
  roles and permissions come only from the token, under the binding's `claims` names. An
  `X-Unfurl-Tenant` or `X-Unfurl-User` header that disagrees is refused (`400`); a missing or invalid
  token is `401`.
- **Authorization:** each route requires one permission, checked by the shared exact-permission policy,
  and every decision is audited with the route's resource:

  | Route | Permission | Resource |
  |---|---|---|
  | `POST /executions`, `POST /contracts/workflow.execute` | `flow.workflow.execute` | the workflow `id@version` |
  | `POST /resume` | `flow.execution.resume` | the execution |
  | `POST /cancel` | `flow.execution.cancel` | the execution |
  | `POST /executions/query` | `flow.execution.read` | the execution |
  | `POST /workflows` | `flow.workflow.register` | the workflow `id@version` |
  | `/triggers/*` fire, `/connectors/*` | `flow.trigger.fire`, `flow.connector.manage` | the trigger or connector |
  | `POST /tools/{name}` | `flow.tool.<name without the flow. prefix>`, for example `flow.tool.workflow-validate` | the tool |

  A missing permission is `403`. The caller may be a user (through a gateway) or a service calling as
  itself (§9); Flow authorizes both the same way.
- **Subject token:** the verified access token is also the exchange subject. Flow holds it only in
  request memory as a credential. It is never added to `ExecutionContext` attributes
  that are checkpointed, journaled, logged or emitted.
- **Exchange:** `RemoteDcpContractInvocableFactory` obtains a token for the provider's audience
  through Flow's OAuth 2.0 port. It uses a `ConnectorDefinition` with `authType: OAUTH2` derived
  from the identity binding, `OAuth2Handler`, and the port's exchange client
  (`OAuth2TokenExchangeClient`, a sibling of the refresh-oriented `OAuth2TokenClient`). Its HTTP
  adapter implements the RFC 8693 grant (`subject_token`, `subject_token_type=…:access_token`,
  `audience`, Flow's client authentication with HTTP Basic). Exchanged tokens are cached in memory by
  tenant, user, audience and subject-token digest until shortly before expiry, and are never
  persisted. The Flow build spec, *Identity: calling a provider that needs a token*, has the detail.
- **Call shape:** a call to a provider with an audience sends `Authorization: Bearer`,
  `X-Correlation-Id` and `traceparent`, and omits the body `context`, which the secured profile
  rejects. Foundry derives the context from the token.
- **No silent fallback:** a call that needs a token but has no subject token fails closed with
  `FLOW_DCP_SUBJECT_TOKEN_UNAVAILABLE`. That covers a durable workflow resumed after a restart, or a
  timer firing after the starting request ended. User tokens are never persisted to make such resumes
  work; the resuming request must carry a fresh token. An exchange the IdP refuses fails with
  `FLOW_DCP_TOKEN_EXCHANGE_FAILED`, and its response body is never echoed.

- **Flow's authoring agent.** Flow owns `flow-dag-authoring`; Foundry's deployment holds no Flow agent,
  prompt or tool. When the deployment root has `authoring.yaml` (the tenant Flow authors for and the
  base URL Foundry reaches Flow at), Flow creates the agent at startup through Foundry's management API
  (`POST /management/agents`), with its prompt and its authoring tools as HTTP tools on its own
  `/tools/<name>` endpoints, each naming `protectedService: urn:unfurl:flow:runtime`. Foundry's address
  is the composition's `agent.run` binding, so authoring needs one. With identity, Flow calls with its
  own client-credentials token for Foundry's audience (Flow's `secret_refs` entry, required,
  whose service account holds `foundry.agent.create`), and Foundry answers for the token's tenant,
  which must be the configured one. Without identity, Flow names the tenant in `X-Unfurl-Tenant`.
  Creation is idempotent, so Flow creates on every start.

## 9. Foundry

- **The contract decides.** Foundry reads identity from its part of the composition (§7): when it holds
  an `identity-context` contract, verified with the folder's keys, and its binding, Foundry requires its
  profile to have `metadata.server.operations.authentication: required` and its `IDENTITY` binding
  (`signed-jwt-identity` issuer, audience and claim names) to equal the identity binding's issuer,
  Foundry's entry in `audiences`, and `claims`; otherwise it refuses to start, naming the difference.
  When the folder has no identity contract, the profile must have `authentication: disabled`. A Foundry
  started without a composition folder is standalone, and its profile alone decides.
- **Service tokens for calls as itself.** An HTTP tool whose endpoint is a protected service, such as
  `flow.workflow-validate` on Flow, is called with Foundry's own OAuth 2.0 client-credentials token for
  that service's audience: Foundry's `secret_refs` entry, the binding's `token_endpoint`, and
  the audience from `audiences`. Foundry caches the token in memory until shortly before it expires and
  never persists it. It sends a service token only to an endpoint whose base address its profile
  allows. Without identity in the contract the same tool is called without a token.
- Verification stays offline: `signed-jwt-identity` with keys from `identity/keys/`, plus exact
  issuer and audience checks.
- Tenant, roles and permissions come only from the token, and the body `context` stays rejected, as
  today.

## 10. Delivery

1. Fabric: unmet-need reasons and conflict messages (§3), `IdentityBindingRule`, needs-driven
   runtime-binding inputs, binding admission, no family requirement. **Done.**
2. Keycloak adapter (`unfurl-adapter-keycloak`): the claim with both shapes, catalog-admissible
   (**done**); then the provisioner (Keycloak Admin REST adapter, verify-only and provision modes, PEM
   key export), tested against a real Keycloak container.
3. Foundry: identity read from its part of the composition, the profile rule and consistency check
   (§9), and client-credentials service tokens for HTTP tools on protected services.
4. Flow: subject-token carrier, the RFC 8693 exchange client, the in-memory token cache, and the
   call shape (**done**); inbound verification and route authorization (§8).
4a. Shared: the offline JWT verifier and the exact-permission policy move to `substrate-host-adapters`,
   used by Flow and Foundry.
5. Deploy Emitter: Keycloak realization for both shapes, key mounting, the disabled-authentication
   refusal, Flow's exchange configuration (**done**), and the identity consistency check.
6. End to end, both paths: a Flow + Foundry package whose needs require identity, exercising
   verification in both services, exchange, service tokens, the refusal paths and a resumed-workflow
   refusal; and the same package without identity, which runs unauthenticated.

## 11. Out of scope

- An environment-based identity rule. Identity follows the needs.
- Identity validation inside DCP. DCP stays general.
- Fabric Studio's authoring client (`DcpAuthoringClient`) calling a Foundry that requires tokens needs
  its own token, either an exchange or client credentials. That is a follow-up under the same rule.
- Online JWKS discovery. Keys stay mounted, to keep Foundry air-gap capable.
- Adapters for other identity providers. Entra ID, Okta and others federate into Keycloak.
- The Intelligent Keycloak product (Keycloak plus a reasoning layer); this is the plain adapter.
