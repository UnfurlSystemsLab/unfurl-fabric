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
 * Black-box CLI tests for optional claim dependencies (HLD-unfurl-fabric, Core Flow steps 3 and 7), using a Foundry
 * stand-in whose claim lists {@code workflow.execute} as optional, as the real Foundry claim does, and a Flow stand-in.
 * The operator's needs decide whether Flow is included: needs with {@code agent.run} alone give a Foundry-only
 * composition, and needs that also ask for {@code workflow.execute} bind Foundry to Flow.
 */
class FabricCliOptionalDependencyTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String FOUNDRY_URI = "urn:unfurl:test:agent.run";

    /** Without workflow.execute in the needs, Foundry compiles alone, and plan says what it runs without. */
    @Test
    void foundryComposesWithoutFlowWhenTheNeedsDoNotAskForIt(@TempDir Path dir) throws Exception {
        Path catalog = catalog(dir);
        Path needs = needs(dir, "agent.run");

        CliTestFixtures.CliRun plan = CliTestFixtures.run("plan", "--catalog", catalog.toString(), "--needs", needs.toString());

        assertThat(plan.exitCode()).isZero();
        List<String> lines = plan.stdout().lines().toList();
        assertThat(lines.get(0)).isEqualTo("EXACT");
        assertThat(lines).contains("optional, not provided: com.unfurl:agent.run:1.0.0 can use workflow.execute"
                + " but runs without it; add workflow.execute to the needs to include a provider");

        JsonNode children = compile(dir, catalog, needs);
        assertThat(capabilities(children)).containsExactly("agent.run");
    }

    /** With workflow.execute in the needs, Flow is selected and its contract records Foundry as the consumer. */
    @Test
    void providedOptionalDependencyIsAnOrdinaryEdge(@TempDir Path dir) throws Exception {
        Path catalog = catalog(dir);
        Path needs = needs(dir, "agent.run", "workflow.execute");

        CliTestFixtures.CliRun plan = CliTestFixtures.run("plan", "--catalog", catalog.toString(), "--needs", needs.toString());

        assertThat(plan.exitCode()).isZero();
        assertThat(plan.stdout()).startsWith("EXACT").doesNotContain("optional, not provided");

        JsonNode children = compile(dir, catalog, needs);
        assertThat(capabilities(children)).containsExactlyInAnyOrder("agent.run", "workflow.execute");
        JsonNode workflow = StreamSupport.stream(children.spliterator(), false)
                .filter(child -> "workflow.execute".equals(child.at("/binding/providerCapability").asText()))
                .findFirst().orElseThrow();
        assertThat(workflow.at("/metadata/extensions/neededBy")).hasSize(1);
        assertThat(workflow.at("/metadata/extensions/neededBy/0").asText()).isEqualTo(FOUNDRY_URI);
    }

    /** Fixture: a catalog with the Foundry stand-in and a Flow stand-in offering workflow.execute. */
    private static Path catalog(Path dir) throws Exception {
        Path catalog = dir.resolve("catalog");
        CliTestFixtures.writeFoundryJar(catalog, "foundry.jar");
        CliTestFixtures.writeCatalogJar(catalog, "flow.jar", "flow", "workflow.execute");
        return catalog;
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
