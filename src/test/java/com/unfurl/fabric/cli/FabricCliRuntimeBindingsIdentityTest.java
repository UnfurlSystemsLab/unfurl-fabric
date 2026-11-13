package com.unfurl.fabric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specification (HLD-needs-driven-identity §6): {@code fabric runtime-bindings} emits the identity-provider binding from
 * explicit operator inputs whenever the composition's needs required identity-context, in every environment, and never
 * fabricates or silently drops identity settings. Each test compiles and signs a real composition through the CLI and
 * inspects the emitted DCP YAML.
 */
class FabricCliRuntimeBindingsIdentityTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String FOUNDRY_URI = "urn:unfurl:test:agent.run";

    /**
     * Complete inputs give a complete, reference-only identity binding. By default Fabric selects the Keycloak adapter's
     * container shape, so the binding's deployment kind is container.
     */
    @Test
    void identityInputsEmitTheIdentityBinding(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);
        Path out = dir.resolve("bindings.yaml");

        assertOk(bindings(signed, out, "production", identityFlags()));

        JsonNode set = YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set");
        assertThat(set.path("root_binding_id").asText()).endsWith(":acme:production");
        JsonNode identity = identityBinding(set);
        assertThat(identity.at("/target_environment/name").asText()).isEqualTo("production");
        assertThat(identity.at("/provider_instance/component_uri").asText()).isEqualTo(CliTestFixtures.IDENTITY_PROVIDER_URI);
        assertThat(identity.at("/provider_instance/deployment_kind").asText()).isEqualToIgnoringCase("container");
        assertThat(identity.at("/provider_instance/base_url").isMissingNode()).isTrue();
        assertThat(identity.at("/provider_instance/base_url_ref/uri").asText()).isEqualTo("config://acme/identity/token-endpoint");
        assertThat(identity.at("/provider_instance/credentials_ref/uri").asText()).isEqualTo("secret://acme/identity/flow-client");
        JsonNode values = identity.at("/configuration/values");
        assertThat(values.path("issuer").asText()).isEqualTo("https://idp.example.com/realms/acme");
        assertThat(values.path("audiences").path(FOUNDRY_URI).asText()).isEqualTo("foundry");
        assertThat(values.at("/claims/tenant").asText()).isEqualTo("tenant_id");
        assertThat(values.at("/claims/roles").asText()).isEqualTo("roles");
        assertThat(values.at("/claims/permissions").asText()).isEqualTo("permissions");
        JsonNode refs = identity.at("/configuration/config_refs");
        assertThat(refs.at("/token_endpoint/uri").asText()).isEqualTo("config://acme/identity/token-endpoint");
        assertThat(refs.at("/verification_keys/uri").asText()).isEqualTo("config://acme/identity/verification-keys");
    }

    /**
     * When the deployment policy selects the external-Keycloak shape, the binding follows the binding plan rather than the
     * catalog's default mode: the deployment kind is external_saas.
     */
    @Test
    void externalKeycloakShapeBindsAsExternalSaas(@TempDir Path dir) throws Exception {
        Path policy = dir.resolve("deployment-policy.yaml");
        Files.writeString(policy, """
                preferredShapes: [MANAGED_EXTERNAL_ADAPTER, IN_PROCESS_LIBRARY]
                disallowedShapes: [CONTAINERIZED_SERVICE]
                requireIsolationForCapabilityPatterns: []
                runtime:
                  springBoot: true
                  kubernetes: true
                  serviceMesh: true
                """);
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true, policy);
        Path out = dir.resolve("bindings.yaml");

        assertOk(bindings(signed, out, "production", identityFlags()));

        JsonNode identity = identityBinding(YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set"));
        assertThat(identity.at("/provider_instance/deployment_kind").asText()).isEqualToIgnoringCase("external_saas");
    }

    /**
     * Regression: number-like identity values (a Keycloak client id used as an audience, a numeric claim name) are written
     * quoted, so they are read back as strings and the emitted set still passes the identity binding rule on re-read.
     */
    @Test
    void numberLikeIdentityValuesStayStrings(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);
        List<String> flags = new ArrayList<>(identityFlags());
        flags.set(flags.indexOf("--identity-audiences") + 1, FOUNDRY_URI + "=1234");
        flags.set(flags.indexOf("--identity-tenant-claim") + 1, "26.2");
        Path out = dir.resolve("bindings.yaml");

        assertOk(bindings(signed, out, "production", flags));

        JsonNode values = identityBinding(YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set"))
                .at("/configuration/values");
        assertThat(values.path("audiences").path(FOUNDRY_URI).isTextual()).isTrue();
        assertThat(values.path("audiences").path(FOUNDRY_URI).asText()).isEqualTo("1234");
        assertThat(values.at("/claims/tenant").isTextual()).isTrue();
    }

    /**
     * Identity follows the needs, not the environment: a composition whose needs do not require identity-context binds to
     * production with no identity inputs and gets no identity binding.
     */
    @Test
    void compositionWithoutIdentityInItsNeedsBindsAnyEnvironment(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, false);
        Path out = dir.resolve("bindings.yaml");

        assertOk(bindings(signed, out, "production", List.of()));

        JsonNode set = YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set");
        assertThat(StreamSupport.stream(set.path("runtime_bindings").spliterator(), false)
                .noneMatch(binding -> "identity-context".equals(binding.at("/configuration/values/capability").asText())))
                .isTrue();
    }

    /**
     * Identity in the needs is mandatory in every environment, local ones included: with no inputs the run fails, names
     * every missing input and says why, and writes nothing.
     */
    @Test
    void identityInTheNeedsRequiresEveryInputEvenLocally(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);
        Path out = dir.resolve("bindings.yaml");

        CliTestFixtures.CliRun run = bindings(signed, out, "local-dev", List.of());

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.stderr()).contains("needs require identity-context", "identity binding is mandatory")
                .contains("issuer", "audiences", "tenantClaim", "rolesClaim", "permissionsClaim", "tokenEndpointRef",
                        "verificationKeysRef", "clientCredentialsRef");
        assertThat(out).doesNotExist();
    }

    /** A partial set of identity inputs reports exactly the missing ones rather than defaulting any of them. */
    @Test
    void missingIdentityInputsAreEachReported(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);
        Path out = dir.resolve("bindings.yaml");

        CliTestFixtures.CliRun run = bindings(signed, out, "production",
                List.of("--identity-issuer", "https://idp.example.com/realms/acme"));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.stderr()).contains("audiences", "tenantClaim", "rolesClaim", "permissionsClaim", "tokenEndpointRef",
                "verificationKeysRef", "clientCredentialsRef").doesNotContain("issuer,");
        assertThat(out).doesNotExist();
    }

    /** A plain-http issuer on a real host is refused in every environment. */
    @Test
    void plainHttpIssuerIsRefused(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);
        List<String> flags = new ArrayList<>(identityFlags());
        flags.set(flags.indexOf("--identity-issuer") + 1, "http://idp.example.com/realms/acme");

        CliTestFixtures.CliRun run = bindings(signed, dir.resolve("bindings.yaml"), "local", flags);

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.stderr()).contains("https");
    }

    /** The loopback exception: a Keycloak container on the developer's machine may use plain http on localhost. */
    @Test
    void plainHttpIssuerOnLocalhostIsAccepted(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);
        List<String> flags = new ArrayList<>(identityFlags());
        flags.set(flags.indexOf("--identity-issuer") + 1, "http://localhost:8080/realms/acme");
        Path out = dir.resolve("bindings.yaml");

        assertOk(bindings(signed, out, "local", flags));

        JsonNode identity = identityBinding(YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set"));
        assertThat(identity.at("/configuration/values/issuer").asText()).isEqualTo("http://localhost:8080/realms/acme");
    }

    /** Identity inputs for a composition with no identity contract are refused instead of being silently dropped. */
    @Test
    void identityInputsWithoutIdentityComponentFail(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, false);

        CliTestFixtures.CliRun run = bindings(signed, dir.resolve("bindings.yaml"), "local", identityFlags());

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.stderr()).contains("no identity-context contract");
    }

    /**
     * The compiler records the protected services on the identity contract: the components whose claims depend on
     * identity-context (the Foundry stand-in), not the identity provider that offers it.
     */
    @Test
    void compilerRecordsTheProtectedServicesOnTheIdentityContract(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);

        JsonNode children = YAML.readTree(Files.readAllBytes(signed.compiled())).path("childContracts");
        JsonNode identity = StreamSupport.stream(children.spliterator(), false)
                .filter(child -> "identity-context".equals(child.at("/binding/providerCapability").asText()))
                .findFirst().orElseThrow();
        assertThat(identity.at("/metadata/extensions/neededBy")).hasSize(1);
        assertThat(identity.at("/metadata/extensions/neededBy/0").asText()).isEqualTo(FOUNDRY_URI);
    }

    /**
     * Regression: an audience for the identity provider alone, and none for Foundry, used to pass because only the map's
     * shape was checked. Every protected service needs an audience, and nothing else may have one.
     */
    @Test
    void audiencesMustCoverEveryProtectedServiceAndNothingElse(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);
        List<String> flags = new ArrayList<>(identityFlags());
        flags.set(flags.indexOf("--identity-audiences") + 1, CliTestFixtures.IDENTITY_PROVIDER_URI + "=keycloak");
        Path out = dir.resolve("bindings.yaml");

        CliTestFixtures.CliRun run = bindings(signed, out, "production", flags);

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.stderr())
                .contains("no audience for " + FOUNDRY_URI + ", which needs identity-context")
                .contains(CliTestFixtures.IDENTITY_PROVIDER_URI + " does not need identity-context, so it takes no audience");
        assertThat(out).doesNotExist();
    }

    /** Audience keys must name the protected services, so a typo cannot produce an unusable audience map. */
    @Test
    void audienceForAnUnknownServiceFails(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);
        List<String> flags = new ArrayList<>(identityFlags());
        flags.set(flags.indexOf("--identity-audiences") + 1, "urn:unfurl:test:agent.rnu=foundry");

        CliTestFixtures.CliRun run = bindings(signed, dir.resolve("bindings.yaml"), "production", flags);

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.stderr()).contains("urn:unfurl:test:agent.rnu");
    }

    /**
     * Service credentials (HLD-needs-driven-identity §5, §9): a service that calls others as itself gets a secret:// reference
     * in values.service_credentials. It must be a service that needs identity, and the value must be a secret:// reference.
     */
    @Test
    void serviceCredentialsAreEmittedForAServiceThatNeedsIdentity(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);
        Path out = dir.resolve("bindings.yaml");
        List<String> flags = new ArrayList<>(identityFlags());
        flags.addAll(List.of("--identity-service-credentials", FOUNDRY_URI + "=secret://acme/identity/foundry-service"));

        assertOk(bindings(signed, out, "production", flags));

        JsonNode values = identityBinding(YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set")).at("/configuration/values");
        assertThat(identityBinding(YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set")).at("/configuration/secret_refs").path(FOUNDRY_URI).path("uri").asText()).isEqualTo("secret://acme/identity/foundry-service");
        assertThat(values.has("service_credentials")).isFalse();
    }

    /** Without the flag the binding has no service_credentials value at all. */
    @Test
    void serviceCredentialsAreOptional(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);
        Path out = dir.resolve("bindings.yaml");

        assertOk(bindings(signed, out, "production", identityFlags()));

        assertThat(identityBinding(YAML.readTree(Files.readAllBytes(out)).path("runtime_binding_set"))
                .at("/configuration/secret_refs").isMissingNode()).isTrue();
    }

    /** A service that does not need identity, or a value that is not a secret:// reference, is refused. */
    @Test
    void serviceCredentialsMustNameAProtectedServiceAndASecret(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);
        List<String> unknown = new ArrayList<>(identityFlags());
        unknown.addAll(List.of("--identity-service-credentials", "urn:unfurl:test:nobody=secret://acme/identity/x"));
        List<String> notSecret = new ArrayList<>(identityFlags());
        notSecret.addAll(List.of("--identity-service-credentials", FOUNDRY_URI + "=config://acme/identity/x"));
        List<String> malformed = new ArrayList<>(identityFlags());
        malformed.addAll(List.of("--identity-service-credentials", FOUNDRY_URI));

        CliTestFixtures.CliRun unknownRun = bindings(signed, dir.resolve("a.yaml"), "production", unknown);
        CliTestFixtures.CliRun notSecretRun = bindings(signed, dir.resolve("b.yaml"), "production", notSecret);
        CliTestFixtures.CliRun malformedRun = bindings(signed, dir.resolve("c.yaml"), "production", malformed);

        assertThat(unknownRun.exitCode()).isNotZero();
        assertThat(unknownRun.stderr()).contains("urn:unfurl:test:nobody does not need identity-context");
        assertThat(notSecretRun.exitCode()).isNotZero();
        assertThat(notSecretRun.stderr()).contains("must be a secret:// reference");
        assertThat(malformedRun.exitCode()).isNotZero();
        assertThat(malformedRun.stderr()).contains("claimUri=secret://");
    }

    /** A malformed audiences flag is a usage error naming the bad entry. */
    @Test
    void malformedAudiencesFlagIsAUsageError(@TempDir Path dir) throws Exception {
        CliTestFixtures.SignedPaths signed = CliTestFixtures.compileAndSignFoundry(dir, true);
        List<String> flags = new ArrayList<>(identityFlags());
        flags.set(flags.indexOf("--identity-audiences") + 1, FOUNDRY_URI);

        CliTestFixtures.CliRun run = bindings(signed, dir.resolve("bindings.yaml"), "production", flags);

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.stderr()).contains("claimUri=audience");
    }

    /** Complete identity inputs for tenant acme; the audience names the fixture's Foundry provider. */
    private static List<String> identityFlags() {
        return List.of(
                "--identity-issuer", "https://idp.example.com/realms/acme",
                "--identity-audiences", FOUNDRY_URI + "=foundry",
                "--identity-tenant-claim", "tenant_id",
                "--identity-roles-claim", "roles",
                "--identity-permissions-claim", "permissions",
                "--identity-token-endpoint-ref", "config://acme/identity/token-endpoint",
                "--identity-verification-keys-ref", "config://acme/identity/verification-keys",
                "--identity-client-credentials-ref", "secret://acme/identity/flow-client");
    }

    /** Command helper: runs {@code fabric runtime-bindings} for tenant acme with extra flags. */
    private static CliTestFixtures.CliRun bindings(CliTestFixtures.SignedPaths signed, Path out, String environment,
                                                   List<String> extra) {
        List<String> argv = new ArrayList<>(List.of("runtime-bindings", "--signed-contract", signed.signed().toString(),
                "--out", out.toString(), "--tenant", "acme", "--environment", environment));
        argv.addAll(extra);
        return CliTestFixtures.run(argv.toArray(String[]::new));
    }

    /** Lookup helper: the single binding of the identity-context capability. */
    private static JsonNode identityBinding(JsonNode set) {
        List<JsonNode> identity = StreamSupport.stream(set.path("runtime_bindings").spliterator(), false)
                .filter(binding -> "identity-context".equals(binding.at("/configuration/values/capability").asText()))
                .toList();
        assertThat(identity).hasSize(1);
        return identity.get(0);
    }

    /** Assertion helper: surfaces the command's diagnostics on failure. */
    private static void assertOk(CliTestFixtures.CliRun run) {
        assertThat(run.exitCode()).withFailMessage(run.stderr()).isZero();
    }
}
