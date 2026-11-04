package com.unfurl.fabric.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box CLI tests for optional claim dependencies (HLD-unfurl-fabric, Core Flow steps 3 and 7), using a Flow
 * stand-in whose claim lists Foundry's {@code agent.run} as optional, as the real Flow claim does, and a Foundry stand-in
 * that, like the real Foundry claim, declares no dependency on Flow. The edge runs one way, Flow → Foundry, and the
 * operator's needs decide whether Foundry is included.
 */
class FabricCliOptionalDependencyTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String FLOW_URI = "urn:unfurl:test:flow";

    /** Without agent.run in the needs, Flow compiles alone, and plan says what it runs without. */
    @Test
    void flowComposesWithoutFoundryWhenTheNeedsDoNotAskForIt(@TempDir Path dir) throws Exception {
        Path catalog = catalog(dir);
        Path needs = needs(dir, "workflow.execute");

        CliTestFixtures.CliRun plan = CliTestFixtures.run("plan", "--catalog", catalog.toString(), "--needs", needs.toString());

        assertThat(plan.exitCode()).isZero();
        List<String> lines = plan.stdout().lines().toList();
        assertThat(lines.get(0)).isEqualTo("EXACT");
        assertThat(lines).contains("optional, not provided: com.unfurl:flow:1.0.0 can use agent.run"
                + " but runs without it; add agent.run to the needs to include a provider");

        JsonNode children = compile(dir, catalog, needs);
        assertThat(capabilities(children)).containsExactly("workflow.execute");
    }

    /** Foundry alone depends on nothing in the catalog, so it composes without Flow and plan reports nothing. */
    @Test
    void foundryComposesAloneWithNothingUnprovided(@TempDir Path dir) throws Exception {
        Path catalog = catalog(dir);
        Path needs = needs(dir, "agent.run");

        CliTestFixtures.CliRun plan = CliTestFixtures.run("plan", "--catalog", catalog.toString(), "--needs", needs.toString());

        assertThat(plan.exitCode()).isZero();
        assertThat(plan.stdout()).startsWith("EXACT").doesNotContain("optional, not provided");
        assertThat(capabilities(compile(dir, catalog, needs))).containsExactly("agent.run");
    }

    /**
     * With both in the needs, Flow's optional agent.run is provided by Foundry: the agent.run contract records Flow as the
     * component that needs it, and the workflow.execute contract records no component, because Foundry never calls Flow.
     */
    @Test
    void providedOptionalDependencyIsAnOrdinaryEdge(@TempDir Path dir) throws Exception {
        Path catalog = catalog(dir);
        Path needs = needs(dir, "agent.run", "workflow.execute");

        CliTestFixtures.CliRun plan = CliTestFixtures.run("plan", "--catalog", catalog.toString(), "--needs", needs.toString());

        assertThat(plan.exitCode()).isZero();
        assertThat(plan.stdout()).startsWith("EXACT").doesNotContain("optional, not provided");

        JsonNode children = compile(dir, catalog, needs);
        assertThat(capabilities(children)).containsExactlyInAnyOrder("agent.run", "workflow.execute");
        assertThat(child(children, "agent.run").at("/metadata/extensions/neededBy")).hasSize(1);
        assertThat(child(children, "agent.run").at("/metadata/extensions/neededBy/0").asText()).isEqualTo(FLOW_URI);
        assertThat(child(children, "workflow.execute").at("/metadata/extensions/neededBy")).isEmpty();
    }

    /** Fixture: a catalog with the Flow stand-in and the Foundry stand-in. */
    private static Path catalog(Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeFoundryJar(catalog, "foundry.jar");
        CliTestFixtures.writeFlowJar(catalog, "flow.jar");
        return catalog;
    }

    /** The child contract whose provider capability is {@code capability}. */
    private static JsonNode child(JsonNode children, String capability) {
        return StreamSupport.stream(children.spliterator(), false)
                .filter(child -> capability.equals(child.at("/binding/providerCapability").asText()))
                .findFirst().orElseThrow();
    }

    /** Fixture: a needs file requiring each capability at ^1. */
    private static Path needs(Path dir, String... capabilities) throws Exception {
        StringBuilder text = new StringBuilder("requiredCapabilities:\n");
        for (String capability : capabilities) {
            text.append("  - capability: ").append(capability).append("\n    capabilityVersion: ^1\n");
        }
        Path needs = dir.resolve("needs.yaml");
        Files.writeString(needs, text, StandardCharsets.UTF_8);
        return needs;
    }

    /** Runs compile and returns the compiled contract's child contracts, failing the test on a compile error. */
    private static JsonNode compile(Path dir, Path catalog, Path needs) throws Exception {
        Path compiled = dir.resolve("compiled.yaml");
        CliTestFixtures.CliRun compile = CliTestFixtures.run("compile", "--catalog", catalog.toString(),
                "--needs", needs.toString(), "--out", compiled.toString());
        assertThat(compile.exitCode()).as(compile.stderr()).isZero();
        return YAML.readTree(Files.readAllBytes(compiled)).path("childContracts");
    }

    /** The provider capability of each child contract. */
    private static List<String> capabilities(JsonNode children) {
        return StreamSupport.stream(children.spliterator(), false)
                .map(child -> child.at("/binding/providerCapability").asText())
                .toList();
    }
}
