# Development realm for the end-to-end example

`demo-realm.json` is the realm the [foundry-assemblies guide](../README.md#end-to-end-from-the-command-line-flow-foundry-kotak-neo-and-keycloak)
imports into a local Keycloak with `--import-realm`. It matches the identity binding the guide creates:

| Binding field | Realm |
|---|---|
| issuer `http://localhost:8180/realms/demo` | realm `demo`, served on port 8180 |
| audience `foundry` for `urn:unfurl:foundry` | the `foundry` client, added to tokens by an audience mapper |
| claims `tenant`, `roles`, `permissions` | a fixed `tenant: demo` claim, realm roles, and the `foundry` client roles granted to the caller |
| client credentials `secret://demo/identity/flow-client` | the confidential `flow` client with a service account |

The `flow` service account holds `foundry.tool.read` and the three Kotak read permissions, so a token
for it can list the tenant's tools and call the reads. Keycloak generates the `flow` client secret on
import; no secret is stored in this folder. It is for development only; a production realm is set up
by the Keycloak adapter's provisioner or by your identity team.
