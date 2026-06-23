package com.unfurl.fabric.studio.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unfurl.foundry.substrate.agent.AgentDefinition;
import com.unfurl.foundry.substrate.agent.AgentPhase;
import com.unfurl.foundry.substrate.engine.EmbeddedAgentRuntime;
import com.unfurl.foundry.substrate.ports.AgentRuntime;
import com.unfurl.foundry.substrate.ports.ModelProvider;
import com.unfurl.foundry.substrate.ports.ToolExecutor;
import com.unfurl.foundry.substrate.runstate.AgentPhaseState;
import com.unfurl.foundry.substrate.runstate.AgentPhaseStatus;
import com.unfurl.foundry.substrate.runstate.AgentRunState;
import com.unfurl.foundry.substrate.runstate.AgentRunStatus;
import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs the authoring agent DAG over the neutral {@link EmbeddedAgentRuntime} and exposes it as the
 * DCP {@code agent.run} {@link ContractInvocable} Fabric injects into its Studio service.
 *
 * <p>Assembly: a host loads the {@link AgentDefinition} (the {@code deployment/agents} DAG) + the
 * client tools (via {@link DeploymentToolLoader}) + the customer {@link ModelProvider}, and calls
 * {@link #create}. On invoke, the DAG runs; the model decides clarify/propose/gap and drives the
 * grounded tools; the terminal phase's model output (a JSON {@code {kind, assistantMessage,
 * proposal|questions|unmet}}) becomes the contract result. Fabric never compiles against the tools,
 * and foundry source is untouched — this uses only the neutral foundry-substrate library.
 */
public final class AuthoringAgentInvocable implements ContractInvocable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String contractId;
    private final String contractVersion;
    private final AgentDefinition agent;
    private final AgentRuntime runtime;

    public AuthoringAgentInvocable(String contractId, String contractVersion, AgentDefinition agent, AgentRuntime runtime) {
        this.contractId = contractId;
        this.contractVersion = contractVersion;
        this.agent = Objects.requireNonNull(agent, "agent");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Assemble an invocable from the DAG, the customer model, and the reflected deployment tools. */
    public static AuthoringAgentInvocable create(
            String contractId, String contractVersion, AgentDefinition agent,
            ModelProvider model, Map<String, ToolExecutor> tools) {
        AgentRuntime runtime = new EmbeddedAgentRuntime(
                new SingleModelProviderRegistry(agent.defaultModelRef(), model),
                new MapToolRegistry(tools));
        return new AuthoringAgentInvocable(contractId, contractVersion, agent, runtime);
    }

    @Override
    public String contractId() {
        return contractId;
    }

    @Override
    public String contractVersion() {
        return contractVersion;
    }

    @Override
    public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
        AgentRunState run = runtime.start(agent, invocation.input(), context);
        if (run.status() != AgentRunStatus.COMPLETED) {
            return new ContractInvocationResult(false, Map.of(),
                    run.errorCode() == null ? "AGENT_FAILED" : run.errorCode(),
                    run.errorMessage() == null ? "authoring agent ended in " + run.status() : run.errorMessage(),
                    Map.of());
        }
        return new ContractInvocationResult(true, parse(terminalContent(run)), null, null, Map.of());
    }

    /** The model output of the last completed phase in DAG order — the agent's final decision. */
    private String terminalContent(AgentRunState run) {
        List<AgentPhase> phases = agent.phases();
        for (int i = phases.size() - 1; i >= 0; i--) {
            AgentPhaseState ps = run.phases().get(phases.get(i).id());
            if (ps != null && ps.status() == AgentPhaseStatus.COMPLETED) {
                Object content = ps.output().get("content");
                if (content != null) {
                    return String.valueOf(content);
                }
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String content) {
        if (content != null && !content.isBlank()) {
            try {
                return MAPPER.readValue(extractJson(content), Map.class);
            } catch (Exception ignored) {
                // fall through to a clarify result
            }
        }
        return Map.of(
                "kind", "clarify",
                "assistantMessage", "I couldn't interpret that — could you describe what the application should do?",
                "questions", List.of(Map.of("id", "detail", "prompt", "What should the application do?",
                        "kind", "text", "options", List.of())));
    }

    private static String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : content;
    }
}
