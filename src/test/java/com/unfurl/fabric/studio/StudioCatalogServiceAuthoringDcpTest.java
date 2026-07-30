package com.unfurl.fabric.studio;

import com.unfurl.substrate.composition.ContractInvocable;
import com.unfurl.substrate.composition.ContractInvocation;
import com.unfurl.substrate.composition.ContractInvocationResult;
import com.unfurl.substrate.policy.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Fabric's authoring endpoint routes over the neutral DCP {@link ContractInvocable}
 * seam when one is injected (the foundry agent over {@code agent.run}), and falls back to the
 * deterministic bridge when none is. Fabric depends only on the neutral composition types.
 */
class StudioCatalogServiceAuthoringDcpTest {
    private static final StudioAuthoringConverseRequest REQUEST = new StudioAuthoringConverseRequest(
            "tenant-local", "assembly-demo", "session-1", List.of(), "a chatbot that answers questions from our docs");

    @Test
    void routesProposalThroughInjectedDcpInvocable() {
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(invocable(Map.of(
                        "kind", "proposal",
                        "assistantMessage", "Proposed by the agent.",
                        "proposal", Map.of(
                                "needsYaml", "targetApplicationName: bot\n",
                                "intents", List.of(Map.of("type", "ADD_COMPONENT",
                                        "catalogEntryId", "com.unfurl:validation-service:1.1.0")),
                                "deploymentPolicy", Map.of()))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(REQUEST);

        assertThat(response.kind()).isEqualTo("proposal");
        assertThat(response.assistantMessage()).isEqualTo("Proposed by the agent.");
        assertThat(response.proposal().needsYaml()).contains("bot");
        assertThat(response.proposal().intents()).hasSize(1);
    }

    @Test
    void requestsHarnessModeWhenDelegatingToFoundry() {
        AtomicReference<ContractInvocation> seen = new AtomicReference<>();
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(new ContractInvocable() {
                    @Override
                    public String contractId() {
                        return "urn:unfurl:fabric:authoring";
                    }

                    @Override
                    public String contractVersion() {
                        return "1.0.0";
                    }

                    @Override
                    public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
                        seen.set(invocation);
                        return new ContractInvocationResult(true, Map.of(
                                "kind", "gap",
                                "assistantMessage", "captured",
                                "unmet", List.of()), null, null, Map.of());
                    }
                });

        service.converseAuthoring(REQUEST);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().metadata()).containsEntry("executionMode", "harness");
    }

    @Test
    void passesCatalogFilesAndSessionHistoryToFoundryContext() {
        AtomicReference<ContractInvocation> seen = new AtomicReference<>();
        StudioCatalogService service = new StudioCatalogService();
        StudioFileRecord catalogFile = service.listFiles("tenant-local", "CATALOG", "", "").getFirst();
        for (int index = 0; index < 10; index++) {
            service.createDraftSession("tenant-local", "assembly-demo", new StudioCreateDraftCompositionRequest(
                    "tenant-local",
                    "assembly-demo",
                    "",
                    "",
                    "",
                    "",
                    "alice",
                    "Alice",
                    catalogFile.fileId(),
                    "Older Flowfoundry Draft " + index));
        }
        service.createDraftSession("tenant-local", "assembly-demo", new StudioCreateDraftCompositionRequest(
                "tenant-local",
                "assembly-demo",
                "",
                "",
                "",
                "",
                "alice",
                "Alice",
                catalogFile.fileId(),
                "Historical Flowfoundry Draft"));
        service.useAuthoringInvocable(new ContractInvocable() {
            @Override
            public String contractId() {
                return "urn:unfurl:fabric:authoring";
            }

            @Override
            public String contractVersion() {
                return "1.0.0";
            }

            @Override
            public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
                seen.set(invocation);
                return new ContractInvocationResult(true, Map.of(
                        "kind", "gap",
                        "assistantMessage", "captured",
                        "unmet", List.of()), null, null, Map.of());
            }
        });

        service.converseAuthoring(new StudioAuthoringConverseRequest(
                "tenant-local",
                "assembly-demo",
                "",
                catalogFile.fileId(),
                "Authoring Flowfoundry Draft",
                List.of(),
                "create the Flowfoundry environment"));

        Map<String, Object> input = seen.get().input();
        assertThat(input).containsKeys(
                "catalogHash",
                "session",
                "catalogFile",
                "catalogFiles",
                "sessionHistory",
                "catalogSummary",
                "catalogPreview",
                "contextLimits",
                "toolHydration");
        assertThat(input).doesNotContainKey("catalog");
        assertThat(stringMap(input.get("catalogFile")))
                .containsEntry("fileId", catalogFile.fileId())
                .containsEntry("fileType", "CATALOG");
        assertThat(stringMap(input.get("session")))
                .containsEntry("catalogFileId", catalogFile.fileId())
                .containsEntry("displayName", "Authoring Flowfoundry Draft");
        assertThat((List<?>) input.get("catalogFiles"))
                .hasSizeLessThanOrEqualTo(8)
                .anySatisfy(file -> {
                    Map<String, Object> row = stringMap(file);
                    assertThat(row).containsEntry("fileId", catalogFile.fileId());
                    assertThat(row).doesNotContainKeys("filePath", "sha256");
                });
        assertThat((List<?>) input.get("sessionHistory"))
                .hasSize(8)
                .anySatisfy(session -> assertThat(stringMap(session))
                        .containsEntry("displayName", "Historical Flowfoundry Draft")
                        .containsEntry("catalogFileId", catalogFile.fileId()));
        assertThat(stringMap(input.get("contextLimits")))
                .containsEntry("sessionHistoryLimit", 8)
                .containsEntry("catalogPreviewLimit", 12)
                .containsEntry("catalogFilesLimit", 8)
                .containsEntry("conversationLimit", 8);
        assertThat((Integer) stringMap(input.get("contextLimits")).get("sessionHistoryOmitted"))
                .isGreaterThan(0);
        assertThat(stringMap(input.get("catalogSummary")))
                .containsEntry("catalogFileId", catalogFile.fileId())
                .containsEntry("previewLimit", 12);
        assertThat(stringMap(input.get("toolHydration")))
                .containsEntry("catalogQueryTool", "fabric.catalog-query");
        assertThat((List<?>) input.get("catalogPreview"))
                .anySatisfy(entry -> {
                    Map<String, Object> catalogEntry = stringMap(entry);
                    assertThat(catalogEntry.get("displayName"))
                            .as("compact preview should expose readable component labels")
                            .isNotEqualTo(catalogEntry.get("catalogEntryId"));
                    assertThat(catalogEntry).doesNotContainKey("visualManifest");
                });
    }

    @Test
    void passesActionContextToFoundryContext() {
        AtomicReference<ContractInvocation> seen = new AtomicReference<>();
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(new ContractInvocable() {
                    @Override
                    public String contractId() {
                        return "urn:unfurl:fabric:authoring";
                    }

                    @Override
                    public String contractVersion() {
                        return "1.0.0";
                    }

                    @Override
                    public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
                        seen.set(invocation);
                        return new ContractInvocationResult(true, Map.of(
                                "kind", "clarify",
                                "assistantMessage", "captured",
                                "questions", List.of()), null, null, Map.of());
                    }
                });

        service.converseAuthoring(new StudioAuthoringConverseRequest(
                "tenant-local",
                "assembly-demo",
                "",
                "",
                "",
                List.of(),
                "Help me configure this component before adding it.",
                Map.of(
                        "operation", "ADD_COMPONENT",
                        "catalogEntryId", "com.unfurl:validation-service:1.1.0",
                        "componentLabel", "Validation Service",
                        "dependencyPorts", List.of("audit.write"))));

        assertThat(stringMap(seen.get().input().get("actionContext")))
                .containsEntry("operation", "ADD_COMPONENT")
                .containsEntry("catalogEntryId", "com.unfurl:validation-service:1.1.0")
                .containsEntry("componentLabel", "Validation Service");
    }

    @Test
    void passesStructuredQuestionAnswersToFoundryContext() {
        AtomicReference<ContractInvocation> seen = new AtomicReference<>();
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(new ContractInvocable() {
                    @Override
                    public String contractId() {
                        return "urn:unfurl:fabric:authoring";
                    }

                    @Override
                    public String contractVersion() {
                        return "1.0.0";
                    }

                    @Override
                    public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
                        seen.set(invocation);
                        return new ContractInvocationResult(true, Map.of(
                                "kind", "gap",
                                "assistantMessage", "captured",
                                "unmet", List.of()), null, null, Map.of());
                    }
                });

        service.converseAuthoring(new StudioAuthoringConverseRequest(
                "tenant-local",
                "assembly-demo",
                "",
                "",
                "",
                List.of(),
                "Here are my structured answers.",
                Map.of(
                        "starting_component", "uploaded:unfurl-flow-0.1.0-SNAPSHOT.jar",
                        "recursive_capabilities", List.of("tools", "rag")),
                Map.of()));

        assertThat(stringMap(seen.get().input().get("questionAnswers")))
                .containsEntry("starting_component", "uploaded:unfurl-flow-0.1.0-SNAPSHOT.jar")
                .containsEntry("startingComponent", "uploaded:unfurl-flow-0.1.0-SNAPSHOT.jar");
        assertThat(asStringList(stringMap(seen.get().input().get("questionAnswers")).get("recursiveCapabilities")))
                .containsExactly("tools", "rag");
    }

    /**
     * Regression guard: follow-up clarification turns carry only focused prompt
     * previews and rely on Fabric tools for expansion, keeping Foundry's prompt
     * budget available for the actual proposal reasoning.
     */
    @Test
    void compactsAuthoringContextAfterStructuredAnswers() {
        AtomicReference<ContractInvocation> seen = new AtomicReference<>();
        String flow = "uploaded:unfurl-flow-0.1.0-SNAPSHOT.jar";
        String gemini = "uploaded:unfurl-fabric-advisor-gemini-0.1.0-SNAPSHOT.jar";
        String tool = "uploaded:sample-tool-using-agent-0.1.0-SNAPSHOT.jar";
        List<StudioVisualCatalogEntry> entries = new java.util.ArrayList<>(List.of(
                visualEntry(flow),
                visualEntry(gemini),
                visualEntry(tool)));
        for (int index = 0; index < 10; index++) {
            entries.add(visualEntry("uploaded:extra-component-" + index + "-0.1.0-SNAPSHOT.jar"));
        }
        StudioCatalogService service = new StudioCatalogService();
        service.loadCatalogSnapshot("tenant-local", new StudioCatalogSnapshot(
                "tenant-local",
                "sha256:focused-catalog",
                entries,
                List.of()));
        StudioFileRecord catalogFile = service.listFiles("tenant-local", "CATALOG", "", "").getFirst();
        StudioCreateDraftCompositionResponse draft = service.createDraftSession(
                "tenant-local",
                "assembly-demo",
                new StudioCreateDraftCompositionRequest(
                        "tenant-local",
                        "assembly-demo",
                        "",
                        "",
                        "",
                        "",
                        "alice",
                        "Alice",
                        catalogFile.fileId(),
                        "Focused AgentFlow Draft"));
        for (int index = 0; index < 4; index++) {
            service.createDraftSession("tenant-local", "assembly-demo", new StudioCreateDraftCompositionRequest(
                    "tenant-local",
                    "assembly-demo",
                    "",
                    "",
                    "",
                    "",
                    "alice",
                    "Alice",
                    catalogFile.fileId(),
                    "Other Draft " + index));
        }
        service.useAuthoringInvocable(new ContractInvocable() {
            @Override
            public String contractId() {
                return "urn:unfurl:fabric:authoring";
            }

            @Override
            public String contractVersion() {
                return "1.0.0";
            }

            @Override
            public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
                seen.set(invocation);
                return new ContractInvocationResult(true, Map.of(
                        "kind", "gap",
                        "assistantMessage", "captured",
                        "unmet", List.of()), null, null, Map.of());
            }
        });

        service.converseAuthoring(new StudioAuthoringConverseRequest(
                "tenant-local",
                "assembly-demo",
                draft.session().sessionId(),
                catalogFile.fileId(),
                "Focused AgentFlow Draft",
                List.of(
                        new StudioAuthoringConversationMessage("USER", "Create an AgentFlow environment."),
                        new StudioAuthoringConversationMessage("ASSISTANT", "Which catalog and model should I use?"),
                        new StudioAuthoringConversationMessage("USER", "Use the latest catalog and Gemini.")),
                "Here are the selected authoring answers.",
                Map.of(
                        "selectedCatalogFile", catalogFile.fileId(),
                        "startingComponent", flow,
                        "modelProviderCatalogEntryId", "unfurl-fabric-advisor-gemini-0.1.0-SNAPSHOT",
                        "toolImplementationCatalogEntryId", tool),
                Map.of()));

        Map<String, Object> input = seen.get().input();
        assertThat((List<?>) input.get("catalogFiles")).hasSize(1);
        assertThat((List<?>) input.get("sessionHistory")).hasSize(1);
        assertThat((List<?>) input.get("conversation")).hasSizeLessThanOrEqualTo(4);
        assertThat(stringMap(input.get("contextLimits")))
                .containsEntry("catalogFilesLimit", 1)
                .containsEntry("sessionHistoryLimit", 1)
                .containsEntry("catalogPreviewLimit", 6)
                .containsEntry("conversationLimit", 4);
        assertThat(stringMap(input.get("questionAnswers")))
                .containsEntry("catalogFileId", catalogFile.fileId());
        assertThat((List<?>) input.get("catalogPreview"))
                .hasSizeLessThanOrEqualTo(6)
                .anySatisfy(entry -> assertThat(stringMap(entry)).containsEntry("catalogEntryId", flow))
                .anySatisfy(entry -> assertThat(stringMap(entry)).containsEntry("catalogEntryId", gemini))
                .anySatisfy(entry -> assertThat(stringMap(entry)).containsEntry("catalogEntryId", tool));
    }

    @Test
    void deterministicFallbackAcceptsSnakeCaseStartingComponentAnswer() {
        StudioCatalogService service = new StudioCatalogService();

        StudioAuthoringConverseResponse response = service.converseAuthoring(new StudioAuthoringConverseRequest(
                "tenant-local",
                "assembly-demo",
                "",
                "",
                "",
                List.of(),
                "Here are my structured answers.",
                Map.of("starting_component", "com.unfurl:validation-service:1.1.0"),
                Map.of()));

        assertThat(response.kind()).isEqualTo("proposal");
        assertThat(response.proposal().intents())
                .singleElement()
                .satisfies(intent -> assertThat(intent)
                        .containsEntry("catalogEntryId", "com.unfurl:validation-service:1.1.0"));
    }

    @Test
    void mapsFoundryQuestionOptionsToLabelValuePairs() {
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(invocable(Map.of(
                        "kind", "clarify",
                        "assistantMessage", "Pick a root.",
                        "questions", List.of(Map.of(
                                "id", "startingComponent",
                                "prompt", "Which root?",
                                "kind", "SINGLE_SELECT",
                                "options", List.of(Map.of(
                                        "label", "Unfurl Flow",
                                        "value", "uploaded:unfurl-flow-0.1.0-SNAPSHOT.jar")))))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(REQUEST);

        assertThat(response.kind()).isEqualTo("clarify");
        assertThat(response.questions())
                .singleElement()
                .satisfies(question -> assertThat(question.options())
                        .singleElement()
                        .satisfies(option -> {
                            assertThat(option.label()).isEqualTo("Unfurl Flow");
                            assertThat(option.value()).isEqualTo("uploaded:unfurl-flow-0.1.0-SNAPSHOT.jar");
                        }));
    }

    /**
     * Regression guard: maps a model-normalized uploaded artifact basename back
     * to the exact tenant catalog id before returning a proposal to the UI.
     */
    @Test
    void canonicalizesUniqueUploadedArtifactAliasInProposalIntents() {
        String exactGeminiEntryId = "uploaded:unfurl-fabric-advisor-gemini-0.1.0-SNAPSHOT.jar";
        StudioCatalogService service = new StudioCatalogService();
        service.loadCatalogSnapshot("tenant-local", new StudioCatalogSnapshot(
                "tenant-local",
                "sha256:test-catalog",
                List.of(
                        new StudioVisualCatalogEntry(
                                exactGeminiEntryId,
                                "sha256:claim-gemini",
                                "sha256:artifact-gemini",
                                Map.of("ports", List.of()),
                                Map.of(),
                                List.of()),
                        new StudioVisualCatalogEntry(
                                "com.unfurl:validation-service:1.1.0",
                                "sha256:claim-validation",
                                "sha256:artifact-validation",
                                Map.of("ports", List.of()),
                                Map.of(),
                                List.of())),
                List.of()));
        service.useAuthoringInvocable(invocable(Map.of(
                "kind", "proposal",
                "assistantMessage", "Proposed.",
                "proposal", Map.of(
                        "needsYaml", "targetApplicationName: agentflow\n",
                        "intents", List.of(Map.of(
                                "type", "add_component",
                                "catalogEntryId", "unfurl-fabric-advisor-gemini-0.1.0-SNAPSHOT")),
                        "deploymentPolicy", Map.of()))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(new StudioAuthoringConverseRequest(
                "tenant-local",
                "assembly-demo",
                "",
                List.of(),
                "Create AgentFlow with Gemini."));

        assertThat(response.kind()).isEqualTo("proposal");
        assertThat(response.proposal().intents())
                .singleElement()
                .satisfies(intent -> assertThat(intent)
                        .containsEntry("type", "ADD_COMPONENT")
                        .containsEntry("catalogEntryId", exactGeminiEntryId));
    }

    /**
     * Regression guard: validates Foundry proposals against the catalog file
     * selected for the authoring session, not the tenant's mutable latest
     * catalog, so historical version runs stay reproducible.
     */
    @Test
    void validatesProposalAgainstSelectedCatalogFile() {
        String exactGeminiEntryId = "uploaded:unfurl-fabric-advisor-gemini-0.1.0-SNAPSHOT.jar";
        StudioCatalogService service = new StudioCatalogService();
        service.loadCatalogSnapshot("tenant-local", new StudioCatalogSnapshot(
                "tenant-local",
                "sha256:selected-catalog",
                List.of(
                        visualEntry(exactGeminiEntryId),
                        visualEntry("uploaded:sample-tool-using-agent-0.1.0-SNAPSHOT.jar")),
                List.of()));
        StudioFileRecord selectedCatalogFile = service.listFiles("tenant-local", "CATALOG", "", "").getFirst();
        service.loadCatalogSnapshot("tenant-local", new StudioCatalogSnapshot(
                "tenant-local",
                "sha256:active-catalog",
                List.of(visualEntry("com.unfurl:validation-service:1.1.0")),
                List.of()));
        service.useAuthoringInvocable(invocable(Map.of(
                "kind", "proposal",
                "assistantMessage", "Proposed from selected catalog.",
                "proposal", Map.of(
                        "needsYaml", "targetApplicationName: agentflow\n",
                        "intents", List.of(Map.of(
                                "type", "Add Component",
                                "catalogEntryId", "unfurl-fabric-advisor-gemini-0.1.0-SNAPSHOT")),
                        "deploymentPolicy", Map.of()))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(new StudioAuthoringConverseRequest(
                "tenant-local",
                "assembly-demo",
                "",
                selectedCatalogFile.fileId(),
                "Selected Catalog AgentFlow Draft",
                List.of(),
                "Use the selected catalog file and Gemini.",
                Map.of(),
                Map.of()));

        assertThat(response.kind()).isEqualTo("proposal");
        assertThat(response.proposal().intents())
                .singleElement()
                .satisfies(intent -> assertThat(intent)
                        .containsEntry("type", "ADD_COMPONENT")
                        .containsEntry("catalogEntryId", exactGeminiEntryId));
    }

    @Test
    void rejectsProposalWhoseComponentIsNotAdmittedInTheCatalog() {
        // The agent proposes a component the tenant's admitted catalog does not contain.
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(invocable(Map.of(
                        "kind", "proposal",
                        "assistantMessage", "Proposed.",
                        "proposal", Map.of(
                                "needsYaml", "targetApplicationName: x\n",
                                "intents", List.of(Map.of("type", "ADD_COMPONENT", "catalogEntryId", "not-admitted")),
                                "deploymentPolicy", Map.of()))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(REQUEST);

        assertThat(response.kind()).isEqualTo("gap");
        assertThat(response.unmet()).containsExactly("not-admitted");
    }

    @Test
    void routesGapThroughInjectedDcpInvocable() {
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(invocable(Map.of(
                        "kind", "gap",
                        "assistantMessage", "No component for that.",
                        "unmet", List.of("video.transcode"))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(REQUEST);

        assertThat(response.kind()).isEqualTo("gap");
        assertThat(response.unmet()).containsExactly("video.transcode");
    }

    /**
     * Regression test: rejects Foundry authoring outputs that try to execute
     * Flowfoundry runbook steps. Flow owns runbook DAG execution and should call
     * Studio tools through Flow nodes, not through `/studio/authoring/converse`.
     */
    @Test
    void rejectsExecutionResponsesFromAuthoringInvocable() {
        StudioCatalogService service = new StudioCatalogService()
                .useAuthoringInvocable(invocable(Map.of(
                        "kind", "execution",
                        "phase", "catalog-creation",
                        "step", 2,
                        "assistantMessage", "Catalog admission completed.",
                        "toolCalls", List.of(Map.of(
                                "id", "step-02-catalog-admit",
                                "toolName", "fabric.catalog-admit",
                                "status", "PASS")))));

        StudioAuthoringConverseResponse response = service.converseAuthoring(REQUEST);

        assertThat(response.kind()).isEqualTo("gap");
        assertThat(response.assistantMessage()).contains("Flow owns runbook DAG execution");
        assertThat(response.unmet()).containsExactly("flow-runbook-execution");
    }

    @Test
    void withoutInvocableFallsBackToDeterministicBridge() {
        // No invocable injected: a short prompt still produces the deterministic clarify response.
        StudioCatalogService service = new StudioCatalogService();

        StudioAuthoringConverseResponse response = service.converseAuthoring(
                new StudioAuthoringConverseRequest("tenant-local", "assembly-demo", "s", List.of(), "app"));

        assertThat(response.kind()).isEqualTo("clarify");
        assertThat(response.questions()).isNotEmpty();
    }

    @Test
    void deterministicBridgeAsksForStartingComponentOnGenericAssemblyPrompt() {
        StudioCatalogService service = new StudioCatalogService();

        StudioAuthoringConverseResponse response = service.converseAuthoring(
                new StudioAuthoringConverseRequest(
                        "tenant-local",
                        "assembly-demo",
                        "",
                        List.of(),
                        "Help me create a governed Fabric assembly from the selected tenant catalog."));

        assertThat(response.kind()).isEqualTo("clarify");
        assertThat(response.assistantMessage()).contains("tenant catalog");
        assertThat(response.questions())
                .anySatisfy(question -> {
                    assertThat(question.id()).isEqualTo("startingComponent");
                    assertThat(question.type()).isEqualTo("SINGLE_SELECT");
                    assertThat(question.options())
                            .anySatisfy(option -> {
                                assertThat(option.label()).isNotBlank();
                                assertThat(option.value()).isNotBlank();
                            });
                });
    }

    @Test
    void deterministicBridgeAsksActionScopedConfigurationQuestions() {
        StudioCatalogService service = new StudioCatalogService();

        StudioAuthoringConverseResponse response = service.converseAuthoring(
                new StudioAuthoringConverseRequest(
                        "tenant-local",
                        "assembly-demo",
                        "",
                        "",
                        "",
                        List.of(),
                        "Help me configure this component before adding it.",
                        Map.of(
                                "operation", "ADD_COMPONENT",
                                "catalogEntryId", "com.unfurl:validation-service:1.1.0",
                                "componentLabel", "Validation Service",
                                "dependencyPorts", List.of("audit.write"),
                                "substratePorts", List.of("secrets.provider"))));

        assertThat(response.kind()).isEqualTo("clarify");
        assertThat(response.questions())
                .extracting(StudioAuthoringQuestion::id)
                .contains("configurationMode", "bindingReferences", "externalConnections", "applyPreference");
        assertThat(response.questions())
                .anySatisfy(question -> assertThat(question.label())
                        .contains("audit.write")
                        .contains("secrets.provider"));
    }

    private static ContractInvocable invocable(Map<String, Object> output) {
        return new ContractInvocable() {
            @Override
            public String contractId() {
                return "urn:unfurl:fabric:authoring";
            }

            @Override
            public String contractVersion() {
                return "1.0.0";
            }

            @Override
            public ContractInvocationResult invoke(ContractInvocation invocation, ExecutionContext context) {
                return new ContractInvocationResult(true, output, null, null, Map.of());
            }
        };
    }

    /**
     * Test data factory: creates a minimal visual catalog entry with stable
     * content hashes and no ports for authoring context projection tests.
     */
    private static StudioVisualCatalogEntry visualEntry(String catalogEntryId) {
        return new StudioVisualCatalogEntry(
                catalogEntryId,
                "sha256:claim-" + catalogEntryId.hashCode(),
                "sha256:artifact-" + catalogEntryId.hashCode(),
                Map.of("ports", List.of()),
                Map.of(),
                List.of());
    }

    /**
     * Assertion helper: casts JSON-like objects produced by the context projector
     * into string-keyed maps so AssertJ can verify expected entries.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Object value) {
        return (Map<String, Object>) value;
    }

    /**
     * Assertion helper: casts JSON-like arrays produced by the context projector
     * into string lists for stable AssertJ comparisons.
     */
    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        return (List<String>) value;
    }
}
