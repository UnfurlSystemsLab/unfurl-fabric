package com.unfurl.fabric.authoring.tools.sample;

import com.unfurl.fabric.studio.authoring.AuthoringTool;
import com.unfurl.foundry.substrate.ports.ToolCallRequest;
import com.unfurl.foundry.substrate.ports.ToolCallResult;
import com.unfurl.substrate.policy.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the reference tool JAR's SPI: the three tools are declared in
 * {@code META-INF/services/...AuthoringTool} and their executors are grounded and well-formed.
 * (This is what Fabric's {@code DeploymentToolLoader} discovers once the built JAR is dropped in
 * {@code deployment/tools/}.)
 */
class SampleAuthoringToolsTest {
    private static final Map<String, AuthoringTool> TOOLS = load();

    @Test
    void declaresTheThreeAuthoringToolsViaServiceLoader() {
        assertThat(TOOLS.keySet())
                .containsExactlyInAnyOrder("fabric.catalog-query", "fabric.needs-emitter", "fabric.intent-emitter");
    }

    @Test
    void catalogQueryResolvesAnAdmittedCapabilityAndMissesUnknownOnes() {
        var catalog = List.of(Map.of("catalogEntryId", "chatbot-core", "offeredCapabilities", List.of("chat.respond")));

        ToolCallResult hit = run("fabric.catalog-query", Map.of("capability", "chat.respond", "catalog", catalog));
        assertThat(hit.output()).containsEntry("found", true).containsEntry("catalogEntryId", "chatbot-core");

        ToolCallResult miss = run("fabric.catalog-query", Map.of("capability", "quantum.entangle", "catalog", catalog));
        assertThat(miss.output()).containsEntry("found", false);
    }

    @Test
    void needsAndIntentEmittersProduceWellFormedOutput() {
        ToolCallResult needs = run("fabric.needs-emitter", Map.of("capability", "chat.respond", "targetName", "bot"));
        assertThat(String.valueOf(needs.output().get("needsYaml"))).contains("bot").contains("chat.respond");

        ToolCallResult intents = run("fabric.intent-emitter", Map.of("catalogEntryId", "chatbot-core"));
        assertThat((List<?>) intents.output().get("intents")).hasSize(1);
    }

    private static ToolCallResult run(String tool, Map<String, Object> args) {
        return TOOLS.get(tool).executor()
                .execute(new ToolCallRequest("c", tool, args, Map.of()), ExecutionContext.empty());
    }

    private static Map<String, AuthoringTool> load() {
        Map<String, AuthoringTool> tools = new LinkedHashMap<>();
        for (AuthoringTool tool : ServiceLoader.load(AuthoringTool.class)) {
            tools.put(tool.name(), tool);
        }
        return tools;
    }
}
