package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StudioDeploymentEnvironments}: revision-checked create, replace and delete, persistence on every change, and the
 * save-time check, which names every problem with its field path (LLD-fabric-studio-api, <i>Deployment Environments</i>).
 */
class StudioDeploymentEnvironmentsTest {
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    private final Map<String, Map<String, StudioDeploymentEnvironment>> state = new ConcurrentHashMap<>();
    private final AtomicInteger saves = new AtomicInteger();
    private final StudioDeploymentEnvironments environments = new StudioDeploymentEnvironments(state, saves::incrementAndGet,
            Clock.fixed(NOW, ZoneOffset.UTC), () -> List.of("k8s", "local-compose"));

    /** Revision 0 creates; the stored revision replaces; anything else is a conflict naming the stored revision. */
    @Test
    void savesAreRevisionChecked() {
        StudioDeploymentEnvironment created = environments.save("tenant-a", "local", request(0, target("local-compose")));
        assertThat(created.revision()).isEqualTo(1);
        assertThat(created.updatedAt()).isEqualTo(NOW);
        StudioDeploymentEnvironment replaced = environments.save("tenant-a", "local", request(1, target("k8s")));
        assertThat(replaced.revision()).isEqualTo(2);
        assertThat(replaced.target().kind()).isEqualTo("k8s");

        assertThatThrownBy(() -> environments.save("tenant-a", "local", request(0, target("local-compose"))))
                .isInstanceOfSatisfying(StudioDeploymentEnvironments.Conflict.class,
                        conflict -> assertThat(conflict.currentRevision()).isEqualTo(2))
                .hasMessageStartingWith("ENVIRONMENT_REVISION_CONFLICT");
        assertThatThrownBy(() -> environments.save("tenant-a", "staging", request(3, target("local-compose"))))
                .isInstanceOfSatisfying(StudioDeploymentEnvironments.Conflict.class,
                        conflict -> assertThat(conflict.currentRevision()).isZero());
        assertThat(environments.list("tenant-a")).containsExactly(replaced);
        assertThat(environments.list("tenant-b")).as("tenants are separate").isEmpty();
        assertThat(saves).hasValue(2);
    }

    /** A delete names the revision it expects; an unknown name is ENVIRONMENT_UNKNOWN. */
    @Test
    void deletesAreRevisionChecked() {
        environments.save("tenant-a", "local", request(0, target("local-compose")));
        assertThatThrownBy(() -> environments.delete("tenant-a", "local", 2))
                .isInstanceOf(StudioDeploymentEnvironments.Conflict.class);
        environments.delete("tenant-a", "local", 1);
        assertThat(environments.find("tenant-a", "local")).isEmpty();
        assertThatThrownBy(() -> environments.delete("tenant-a", "local", 1))
                .isInstanceOf(StudioDeploymentEnvironments.Unknown.class)
                .hasMessageStartingWith("ENVIRONMENT_UNKNOWN");
    }

    /**
     * Every problem is reported at once with its path: a bad name, an emitter the server does not install, a credential
     * ref that is a value, a malformed base URL, a malformed disabled binding, a non-positive timeout, a malformed
     * documentation default, and identity settings that are values rather than references. Nothing is stored.
     */
    @Test
    void refusesEveryProblemWithItsPath() {
        StudioDeploymentEnvironmentRequest bad = new StudioDeploymentEnvironmentRequest(0,
                new StudioDeploymentEnvironment.Target("nomad", "local", null, null, Map.of(), List.of("sk-live-0123456789")),
                new StudioDeploymentEnvironment.Bindings(Map.of("urn:unfurl:test:flow", "ftp://flow"), 0, null,
                        new StudioDeploymentEnvironment.Identity("https://idp.example", Map.of(), "tenant", "roles",
                                "permissions", "https://idp.example/token", "config://idp/keys", "s3cr3t",
                                Map.of("urn:unfurl:test:foundry", "plain-secret")),
                        List.of(), new StudioDeploymentEnvironment.Documentation("everyone", null, null, null),
                        List.of("workflow.execute")));

        assertThatThrownBy(() -> environments.save("tenant-a", "Local_1", bad))
                .isInstanceOfSatisfying(StudioDeploymentEnvironments.Invalid.class, invalid ->
                        assertThat(invalid.problems()).extracting(StudioDeploymentEnvironments.Problem::path)
                                .containsExactlyInAnyOrder("name", "target.kind", "target", "bindings.timeoutMs",
                                        "bindings.identity.tokenEndpointRef", "bindings.identity.clientCredentialsRef",
                                        "bindings.identity.serviceCredentials.urn:unfurl:test:foundry",
                                        "bindings.serviceBaseUrls", "bindings.disabledBindings", "bindings.documentation"))
                .hasMessageStartingWith("ENVIRONMENT_INVALID");
        assertThat(state).isEmpty();
        assertThat(saves).hasValue(0);
    }

    /**
     * A save or delete whose state write fails changes nothing: the previous environments are restored, so a retry meets
     * the revision it expects (LLD, Deployment Environments, Saving).
     */
    @Test
    void aFailedWriteChangesNothing() {
        AtomicInteger failing = new AtomicInteger();
        StudioDeploymentEnvironments flaky = new StudioDeploymentEnvironments(state, () -> {
            if (failing.get() > 0) throw new IllegalStateException("unable to write Studio state");
        }, Clock.fixed(NOW, ZoneOffset.UTC), () -> List.of("k8s", "local-compose"));

        failing.set(1);
        assertThatThrownBy(() -> flaky.save("tenant-a", "local", request(0, target("local-compose"))))
                .hasMessageContaining("unable to write Studio state");
        assertThat(flaky.list("tenant-a")).as("a failed create leaves no environment").isEmpty();
        assertThat(state).doesNotContainKey("tenant-a");

        failing.set(0);
        StudioDeploymentEnvironment created = flaky.save("tenant-a", "local", request(0, target("local-compose")));
        failing.set(1);
        assertThatThrownBy(() -> flaky.save("tenant-a", "local", request(1, target("k8s")))).isInstanceOf(IllegalStateException.class);
        assertThat(flaky.find("tenant-a", "local")).as("a failed replace keeps the stored one").contains(created);
        assertThatThrownBy(() -> flaky.delete("tenant-a", "local", 1)).isInstanceOf(IllegalStateException.class);
        assertThat(flaky.find("tenant-a", "local")).as("a failed delete keeps it").contains(created);

        failing.set(0);
        assertThat(flaky.save("tenant-a", "local", request(1, target("k8s"))).revision())
                .as("the retry meets the revision it expected").isEqualTo(2);
    }

    /**
     * The content hash identifies what an emit reads: equal settings hash equally whatever the revision or the order
     * they were given in, and any change to the target or bindings changes it.
     */
    @Test
    void theContentHashFollowsTheSettingsNotTheRevision() {
        StudioDeploymentEnvironment first = new StudioDeploymentEnvironment("local", 1, NOW, target("local-compose"), bindings(Map.of(
                "urn:unfurl:test:flow", "http://flow:8080", "urn:unfurl:test:foundry", "http://foundry:7979")));
        StudioDeploymentEnvironment recreated = new StudioDeploymentEnvironment("local", 1, NOW.plusSeconds(60), target("local-compose"),
                bindings(new java.util.LinkedHashMap<>(Map.of("urn:unfurl:test:foundry", "http://foundry:7979",
                        "urn:unfurl:test:flow", "http://flow:8080"))));
        StudioDeploymentEnvironment moved = new StudioDeploymentEnvironment("local", 1, NOW, target("local-compose"), bindings(Map.of(
                "urn:unfurl:test:flow", "http://flow:9090", "urn:unfurl:test:foundry", "http://foundry:7979")));

        assertThat(first.contentSha256()).hasSize(64).isEqualTo(recreated.contentSha256());
        assertThat(moved.contentSha256()).as("same revision, other settings").isNotEqualTo(first.contentSha256());
        assertThat(new StudioDeploymentEnvironment("local", 1, NOW, "forged", target("k8s"), bindings(Map.of())).contentSha256())
                .as("never taken from the caller").isNotEqualTo("forged").hasSize(64);
    }

    /**
     * The inputs the preview generates are reserved: an environment naming one is refused at its option's path, while
     * the operator's own deployment inputs (Flow's workflows, the Foundry root, service mounts) are accepted.
     */
    @Test
    void refusesTheOptionsThePreviewGenerates() {
        StudioDeploymentEnvironment.Target target = new StudioDeploymentEnvironment.Target("local-compose", "local", null, null,
                Map.of("runtimeBindingPath", "/operator/bindings.yaml", "trustKeysPath", "/operator/keys",
                        "flowWorkflowsPath", "/operator/workflows", "serviceConfigPath.api-catalog", "/operator/catalog-config"),
                List.of());

        assertThatThrownBy(() -> environments.save("tenant-a", "local", request(0, target)))
                .isInstanceOfSatisfying(StudioDeploymentEnvironments.Invalid.class, invalid ->
                        assertThat(invalid.problems()).extracting(StudioDeploymentEnvironments.Problem::path)
                                .containsExactly("target.options.runtimeBindingPath", "target.options.trustKeysPath"));
        assertThat(StudioDeploymentEnvironments.RESERVED_OPTIONS).containsExactlyInAnyOrder("fabricContractPath",
                "substrateProfilePath", "runtimeBindingPath", "dcpRuntimeBundlePath", "componentArtifactsPath", "trustKeysPath");
    }

    /** The bindings are handed to the binding reader with the tool's argument names. */
    @Test
    void bindingsReadAsTheToolsArguments() {
        StudioDeploymentEnvironment.Bindings bindings = new StudioDeploymentEnvironment.Bindings(
                Map.of("urn:unfurl:test:flow", "http://flow:8080"), 45000, "acme.local", null,
                List.of("urn:unfurl:test:tool#limit=3"), new StudioDeploymentEnvironment.Documentation("private", null, 1000L, 8000L),
                List.of("urn:unfurl:test:flow#workflow.execute"));

        Map<String, Object> arguments = bindings.toolArguments("tenant-a", "local", null);

        assertThat(arguments).containsEntry("tenant", "tenant-a").containsEntry("environment", "local")
                .containsEntry("timeoutMs", 45000).containsEntry("telemetryNamespace", "acme.local")
                .containsEntry("documentationVisibilityDefault", "private")
                .containsEntry("catalogObservationIntervalMs", 1000L)
                .containsEntry("catalogObservationMaxBackoffMs", 8000L)
                .containsEntry("disableBindings", List.of("urn:unfurl:test:flow#workflow.execute"))
                .containsEntry("componentConfig", List.of("urn:unfurl:test:tool#limit=3"))
                .doesNotContainKey("identity").doesNotContainKey("documentationVisibilityPath");
        var inputs = com.unfurl.fabric.runtime.RuntimeBindingInputs.fromMap(arguments, java.nio.file.Path::of);
        assertThat(inputs.timeoutMs()).isEqualTo(45000);
        assertThat(inputs.serviceBaseUrls()).containsEntry(java.net.URI.create("urn:unfurl:test:flow"), "http://flow:8080");
        assertThat(inputs.identity().absent()).as("no identity unless given").isTrue();
    }

    private static StudioDeploymentEnvironmentRequest request(long expectedRevision, StudioDeploymentEnvironment.Target target) {
        return new StudioDeploymentEnvironmentRequest(expectedRevision, target, new StudioDeploymentEnvironment.Bindings(
                Map.of(), null, null, null, List.of(), null, List.of()));
    }

    private static StudioDeploymentEnvironment.Bindings bindings(Map<String, String> serviceBaseUrls) {
        return new StudioDeploymentEnvironment.Bindings(serviceBaseUrls, null, null, null, List.of(), null, List.of());
    }

    private static StudioDeploymentEnvironment.Target target(String kind) {
        return new StudioDeploymentEnvironment.Target(kind, "local", null, null, Map.of(), List.of());
    }
}
