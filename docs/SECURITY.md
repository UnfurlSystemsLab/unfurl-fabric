# Security Notes: Unfurl Fabric

`unfurl-fabric` handles design-time contract compilation and Studio API workflows. It should be treated as a control-plane component because it reads component claims, produces signed deployment handoff artifacts, and can expose draft composition state through Studio.

## Trust Boundaries

- Component artifacts and catalog manifests are untrusted inputs until scanned, hashed, and classified.
- Operator needs, deployment policies, and trust policies are control-plane inputs.
- Studio HTTP clients are not trusted unless protected by the surrounding deployment environment.
- Authoring AI output is advisory until converted into validated catalog-backed intents and compiled.

## Studio Server Exposure

`StudioServer` is intended for local/dev Studio workflows by default.

- Default bind address is loopback: `127.0.0.1`.
- Binding to non-loopback addresses prints a warning.
- Built-in CORS only allows loopback HTTP origins.
- No production authentication provider is built into `StudioServer`.

If the Studio API is deployed beyond local development, put it behind an authenticated gateway and enable tenant checks:

```text
UNFURL_STUDIO_REQUIRE_TENANT_HEADER=true
```

Clients should send:

```text
X-Unfurl-Tenant
X-Unfurl-User
X-Unfurl-Tenant-Memberships
```

## Secrets

Fabric must not embed secret values in generated substrate profiles or contract metadata.

`SubstrateProfileDeriver` excludes secret-like environment keys such as key, token, secret, password, API key, and access key names. Keep this behavior covered when adding new substrate metadata sources.

## Contract Integrity

- Catalog entries and claim hashes are content-pinned.
- Compiled contracts include the selected binding plan and substrate profile hash.
- Signed contracts are the deployment handoff artifact.
- Verification should check signatures, trust keys, and catalog drift before accepting a contract.

## Authoring AI

Authoring requests can delegate to Foundry over `UNFURL_FOUNDRY_DCP_ENDPOINT`.

Treat generated authoring proposals as untrusted suggestions. They must flow back through catalog-backed validation, intent application, candidate compilation, deployment resolution, and signing before deployment.

## Operational Guidance

- Do not expose `StudioServer` directly on a public network.
- Keep state and asset roots scoped per environment.
- Avoid placing credentials in catalog metadata, needs files, deployment policies, or layout state.
- Use signed contracts for deployment handoff, not raw Studio session state.
- Re-run verification after catalog updates or trust policy changes.

