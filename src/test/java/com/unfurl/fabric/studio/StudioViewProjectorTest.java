package com.unfurl.fabric.studio;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.PackagedClaim;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@link StudioViewProjector}: each view reads exactly its typed claim fields, names the DCP path of every item, keeps
 * DCP's enum names, and reports a section with nothing to show as unavailable with its reason (LLD-fabric-studio-api,
 * StudioViewProjection). Claims are parsed from catalog-manifest YAML by DCP, as admission parses them.
 */
class StudioViewProjectorTest {
    private static final StudioAbsentReason REQUIRED = new StudioAbsentReason("COMPILE_REQUIRED", "no compile yet");

    /** Flow: one external owner per owner= dependency, and each external_protocol offer with its protocol. */
    @Test
    void flowProjectsExternalOwnersAndExternalProtocolOffers() {
        StudioViewProjector.Sections sections = StudioViewProjector.FLOW.project(context(node("flow", RICH)));

        assertThat(sections.externalOwners.items()).singleElement().satisfies(owner -> {
            assertThat(owner.boundaryNodeId()).isEqualTo("owner:host");
            assertThat(owner.owner()).isEqualTo("host");
            assertThat(owner.dependency()).isEqualTo("host.config@v1?owner=host");
            assertThat(owner.neededByNodeId()).isEqualTo("flow");
            assertThat(owner.source()).isEqualTo("claim.dependencies.needs[0]");
        });
        assertThat(sections.externalProtocolOffers.items()).singleElement().satisfies(offer -> {
            assertThat(offer.capability()).isEqualTo("identity-context");
            assertThat(offer.protocol()).isEqualTo("oauth2");
            assertThat(offer.source()).isEqualTo("claim.offers[1].interface.details.protocol");
        });
    }

    /** Data: owned state and decisions with DCP's enum names and their paths; documented operations with their schemas. */
    @Test
    void dataProjectsOwnershipAndOperations() {
        StudioViewProjector.Sections sections = StudioViewProjector.DATA.project(context(node("flow", RICH)));

        assertThat(sections.ownedState.items()).singleElement().satisfies(state -> {
            assertThat(state.resource()).isEqualTo("workflow-runs");
            assertThat(state.sensitivity()).isEqualTo("CONFIDENTIAL");
            assertThat(state.source()).isEqualTo("claim.domain.concerns[0].owns_state[0]");
        });
        assertThat(sections.ownedDecisions.items()).singleElement().satisfies(decision -> {
            assertThat(decision.decision()).isEqualTo("run-admission");
            assertThat(decision.authority()).isEqualTo("EXCLUSIVE");
        });
        assertThat(sections.operations.items()).singleElement().satisfies(operation -> {
            assertThat(operation.capability()).isEqualTo("workflow.execute");
            assertThat(operation.operation()).isEqualTo("start");
            assertThat(operation.requestSchema().path()).isEqualTo("dcp/schemas/start.request.schema.json");
            assertThat(operation.source()).isEqualTo("claim.offers[0].documentation.operations[0]");
        });
        assertThat(sections.dataMappings.available()).isFalse();
        assertThat(sections.dataMappings.absentReason()).isEqualTo(REQUIRED);
    }

    /** Runtime: the typed monitoring and telemetry ports; the opaque ports map is never projected. */
    @Test
    void runtimeProjectsMonitoringAndTelemetry() {
        StudioViewProjector.Sections sections = StudioViewProjector.RUNTIME.project(context(node("flow", RICH)));

        assertThat(sections.monitoring.items()).singleElement().satisfies(monitoring -> {
            assertThat(monitoring.healthEndpoints()).containsExactly("/health/live", "/health/ready");
            assertThat(monitoring.readinessRequired()).isTrue();
            assertThat(monitoring.source()).isEqualTo("claim.integration_ports.monitoring");
        });
        assertThat(sections.telemetry.items()).singleElement()
                .satisfies(telemetry -> assertThat(telemetry.signals()).containsExactly("LOGS", "AUDIT_EVENTS"));
        assertThat(sections.verification).isNotNull();
    }

    /** A claim that declares none of a view's data gives each section its own reason, never an empty list. */
    @Test
    void sectionsWithoutDataSayWhy() {
        StudioViewContext plain = context(node("plain", PLAIN));

        StudioViewProjector.Sections flow = StudioViewProjector.FLOW.project(plain);
        StudioViewProjector.Sections data = StudioViewProjector.DATA.project(plain);
        StudioViewProjector.Sections runtime = StudioViewProjector.RUNTIME.project(plain);

        assertThat(flow.externalOwners.absentReason().code()).isEqualTo("NO_EXTERNAL_OWNERS");
        assertThat(flow.externalProtocolOffers.absentReason().code()).isEqualTo("NO_EXTERNAL_PROTOCOL_OFFERS");
        assertThat(data.ownedState.absentReason().code()).isEqualTo("NO_OWNED_STATE_DECLARED");
        assertThat(data.ownedDecisions.absentReason().code()).isEqualTo("NO_OWNED_DECISIONS_DECLARED");
        assertThat(data.operations.absentReason().code()).isEqualTo("NO_OPERATIONS_DOCUMENTED");
        assertThat(runtime.monitoring.absentReason().code()).isEqualTo("NO_MONITORING_DECLARED");
        assertThat(runtime.telemetry.absentReason().code()).isEqualTo("NO_TELEMETRY_DECLARED");
        assertThat(List.of(flow.externalOwners, data.ownedState, runtime.monitoring))
                .allSatisfy(section -> assertThat(section.items()).isEmpty());
    }

    /** A draft without components says so on every claim section. */
    @Test
    void anEmptyDraftHasNoComponents() {
        StudioViewContext empty = new StudioViewContext(List.of(), () -> compileRequired(), () -> notSigned(), () -> false);

        assertThat(StudioViewProjector.FLOW.project(empty).externalOwners.absentReason().code()).isEqualTo("NO_COMPONENTS");
        assertThat(StudioViewProjector.DATA.project(empty).ownedState.absentReason().code()).isEqualTo("NO_COMPONENTS");
        assertThat(StudioViewProjector.RUNTIME.project(empty).monitoring.absentReason().code()).isEqualTo("NO_COMPONENTS");
    }

    /** Topology without a current compile says why; a view never invents transports. */
    @Test
    void topologyNeedsACurrentCompile() {
        StudioViewProjector.Sections sections = StudioViewProjector.TOPOLOGY.project(context(node("flow", RICH)));

        assertThat(sections.transports.available()).isFalse();
        assertThat(sections.transports.absentReason()).isEqualTo(REQUIRED);
    }

    /** The route's view ids, and nothing else. */
    @Test
    void resolvesOnlyTheFourViews() {
        assertThat(List.of("flow", "data", "topology", "runtime")).allSatisfy(view ->
                assertThat(StudioViewProjector.forView(view).id()).isEqualTo(view));
        assertThatIllegalArgumentException().isThrownBy(() -> StudioViewProjector.forView("FLOW"))
                .withMessageStartingWith("VIEW_UNKNOWN");
    }

    private static StudioViewContext context(StudioViewContext.GroundedNode... nodes) {
        return new StudioViewContext(List.of(nodes), StudioViewProjectorTest::compileRequired, StudioViewProjectorTest::notSigned,
                () -> false);
    }

    private static StudioViewContext.CompiledView compileRequired() {
        return new StudioViewContext.CompiledView(null, null, REQUIRED, Map.of());
    }

    private static StudioVerificationResult notSigned() {
        return new StudioVerificationResult(false, new StudioAbsentReason("NOT_SIGNED", "unsigned"), null, false,
                null, null, null, List.of());
    }

    private static StudioViewContext.GroundedNode node(String nodeId, String claimYaml) {
        Claim claim = PackagedClaim.parse(("claim:\n" + claimYaml.indent(2)).getBytes(StandardCharsets.UTF_8));
        return new StudioViewContext.GroundedNode(nodeId, claim);
    }

    /** A claim with every field the views read. */
    private static final String RICH = """
            identity: { uri: "urn:unfurl:test:flow", name: flow, kind: COMPONENT, version: 1.0.0, publisher: Unfurl }
            domain:
              summary: flow component
              concerns:
                - concern: workflow-execution
                  description: Runs workflows
                  owns_state:
                    - { resource: workflow-runs, description: Run state, sensitivity: CONFIDENTIAL }
                  owns_decisions:
                    - { decision: run-admission, description: Admits runs, authority: EXCLUSIVE }
              boundary_principles: [owns only workflow execution]
            refusals:
              - { concern: unrelated.concern, rationale: This component deliberately owns only workflows., owned_by: host }
            dependencies:
              needs: ["host.config@v1?owner=host"]
            offers:
              - capability: workflow.execute
                description: Runs workflows
                consumer_access: ANY
                offer_interface: { interface_kind: IN_PROCESS, details: {} }
                stability: STABLE
                version: 1.0.0
                metered: false
                documentation:
                  operations:
                    - operation: start
                      request_schema: { path: dcp/schemas/start.request.schema.json, media_type: application/schema+json, sha256: "%s" }
                      response_schema: { path: dcp/schemas/start.response.schema.json, media_type: application/schema+json, sha256: "%s" }
              - capability: identity-context
                description: Issues tokens
                consumer_access: ANY
                offer_interface: { interface_kind: EXTERNAL_PROTOCOL, details: { protocol: oauth2 } }
                stability: STABLE
                version: 1.0.0
                metered: false
            integration_ports:
              ports: { runtime: com.example.Runtime }
              monitoring: { required: true, health_endpoints: [/health/live, /health/ready], readiness_required: true, liveness_required: true }
              telemetry: { required: false, signals: [LOGS, AUDIT_EVENTS], correlation_id_required: false }
            faults: { emitted: [] }
            metadata: { dcp_version: 0.2.0, claim_version: 1.0.0, created_at: "1970-01-01T00:00:00Z" }
            """.formatted("a".repeat(64), "b".repeat(64));

    /** A claim that declares none of what the views read. */
    private static final String PLAIN = """
            identity: { uri: "urn:unfurl:test:plain", name: plain, kind: COMPONENT, version: 1.0.0, publisher: Unfurl }
            domain:
              summary: plain component
              concerns:
                - { concern: plain-work, description: Does plain work }
              boundary_principles: [owns only plain work]
            refusals:
              - { concern: unrelated.concern, rationale: This component deliberately owns only plain work., owned_by: host }
            dependencies: { needs: [] }
            offers:
              - capability: plain.work
                description: Does plain work
                consumer_access: ANY
                offer_interface: { interface_kind: IN_PROCESS, details: {} }
                stability: STABLE
                version: 1.0.0
                metered: false
            integration_ports: { ports: {} }
            faults: { emitted: [] }
            metadata: { dcp_version: 0.2.0, claim_version: 1.0.0, created_at: "1970-01-01T00:00:00Z" }
            """;
}
