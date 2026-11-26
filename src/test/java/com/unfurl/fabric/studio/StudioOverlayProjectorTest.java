package com.unfurl.fabric.studio;

import com.unfurl.dcp.claim.PackagedClaim;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@link StudioOverlayProjector}: Security & Trust in both identity paths (root CLAUDE.md rule 6), Compliance's
 * always-present trust-policy finding and audit-events annotations, Performance without a compile, and the overlay ids
 * (LLD-fabric-studio-api, StudioOverlayAnnotations). Claims are parsed from catalog-manifest YAML by DCP.
 */
class StudioOverlayProjectorTest {
    private static final StudioAbsentReason REQUIRED = new StudioAbsentReason("COMPILE_REQUIRED", "no compile yet");

    /** Without an identity-context provider, the declared ports are annotated as not enforced (trusted network). */
    @Test
    void securityWithoutIdentityIsTrustedNetwork() {
        StudioOverlayProjector.Result result = StudioOverlayProjector.SECURITY.project(context(node("flow", SERVICE)), session(""));

        assertThat(result.annotations()).extracting(StudioOverlayAnnotations.Annotation::kind)
                .containsExactly("AUTHENTICATION", "AUTHORIZATION");
        assertThat(result.annotations()).allSatisfy(annotation -> {
            assertThat(annotation.elementId()).isEqualTo("flow");
            assertThat(annotation.label()).endsWith("trusted network");
            assertThat(annotation.severity()).isEqualTo("WARNING");
            assertThat(annotation.contractHash()).isNull();
        });
        assertThat(result.annotations().get(1).detail()).contains("SELF", "flow., dcp.", "no permission is checked");
        assertThat(result.findings()).extracting(StudioOverlayAnnotations.Finding::label)
                .containsExactly("Identity", "Identity in the needs");
        assertThat(result.findings().get(0).value()).startsWith("not in the composition");
        assertThat(result.findings().get(1).value()).startsWith("not required");
        assertThat(result.provenance()).as("security reads claims only, never a compile").isNull();
    }

    /** Whether the needs require identity is its own finding (P2d), read from the need compile uses. */
    @Test
    void securityStatesWhetherTheNeedsRequireIdentity() {
        StudioOverlayProjector.Result required = StudioOverlayProjector.SECURITY.project(
                context(true, node("flow", SERVICE), node("idp", IDENTITY_PROVIDER)), session(""));

        assertThat(required.findings()).filteredOn(finding -> finding.label().equals("Identity in the needs"))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.value()).startsWith("required");
                    assertThat(finding.source()).isEqualTo("needs.required_capabilities[].capability");
                });
    }

    /** With an identity-context provider in the draft, the same ports are annotated as enforced. */
    @Test
    void securityWithIdentityIsEnforced() {
        StudioOverlayProjector.Result result = StudioOverlayProjector.SECURITY.project(
                context(node("flow", SERVICE), node("idp", IDENTITY_PROVIDER)), session(""));

        assertThat(result.annotations()).hasSize(2).allSatisfy(annotation -> {
            assertThat(annotation.label()).endsWith("enforced");
            assertThat(annotation.severity()).isEqualTo("INFO");
        });
        assertThat(result.annotations().get(0).detail()).contains("callers must authenticate (JWT)");
        assertThat(result.findings().get(0).value()).startsWith("in the composition");
    }

    /** A draft whose claims declare no auth ports has nothing to show and says why. */
    @Test
    void securityWithoutAuthPortsSaysWhy() {
        StudioOverlayProjector.Result result = StudioOverlayProjector.SECURITY.project(context(node("idp", IDENTITY_PROVIDER)), session(""));

        assertThat(result.annotations()).isEmpty();
        assertThat(result.findings()).isEmpty();
        assertThat(result.emptyReason().code()).isEqualTo("NO_AUTH_PORTS_DECLARED");
    }

    /** Compliance always states the trust policy, never as applied; it annotates audit-event emitters; without a compile it lists the audit as stale. */
    @Test
    void complianceStatesTrustPolicyAndAuditEmitters() {
        StudioOverlayProjector.Result permissive = StudioOverlayProjector.COMPLIANCE.project(context(node("flow", SERVICE)), session(""));
        StudioOverlayProjector.Result named = StudioOverlayProjector.COMPLIANCE.project(context(node("flow", SERVICE)), session("trust-acme"));

        assertThat(permissive.findings().get(0).value()).startsWith("none set");
        assertThat(named.findings().get(0).value()).contains("trust-acme", "does not apply named trust policies");
        assertThat(permissive.annotations()).singleElement().satisfies(annotation -> {
            assertThat(annotation.kind()).isEqualTo("AUDIT_EVENTS");
            assertThat(annotation.source()).isEqualTo("claim.integration_ports.telemetry.signals");
        });
        assertThat(permissive.staleSources()).singleElement().satisfies(stale -> {
            assertThat(stale.source()).isEqualTo("contract.audit");
            assertThat(stale.reason()).isEqualTo(REQUIRED);
        });
    }

    /** Performance reads only the compile; without one its reason is the compile's, never an empty layer. */
    @Test
    void performanceNeedsACurrentCompile() {
        StudioOverlayProjector.Result result = StudioOverlayProjector.PERFORMANCE.project(context(node("flow", SERVICE)), session(""));

        assertThat(result.annotations()).isEmpty();
        assertThat(result.emptyReason()).isEqualTo(REQUIRED);
        assertThat(result.staleSources()).singleElement()
                .satisfies(stale -> assertThat(stale.source()).isEqualTo("contract.child_contracts[].expectations.timeout_ms"));
    }

    /** The four overlays resolve; anything else is unknown. */
    @Test
    void resolvesTheBuiltOverlays() {
        assertThat(List.of("security", "performance", "resilience", "compliance"))
                .allSatisfy(id -> assertThat(StudioOverlayProjector.forOverlay(id).id()).isEqualTo(id));
        assertThatIllegalArgumentException().isThrownBy(() -> StudioOverlayProjector.forOverlay("cost"))
                .withMessageStartingWith("OVERLAY_UNKNOWN");
    }

    /**
     * Resilience & Faults (P2e): every declared fault is on its node with the DCP fields in its detail; a fault whose
     * parent_impact is BLOCKED or DEGRADED marks the CONTAINS edge from its parent and the parent, with propagates_when;
     * NONE marks nothing more. It reads no compile.
     */
    @Test
    void resilienceDrawsFaultsAndTheirPropagationToTheParent() {
        StudioOverlayProjector.Result result = StudioOverlayProjector.RESILIENCE.project(
                context(new StudioViewContext.GroundedNode("flow", claim(FAULTY), "Flow", "assembly", "Checkout assembly")),
                session(""));

        assertThat(result.annotations()).filteredOn(a -> a.kind().equals("FAULT")).extracting(
                StudioOverlayAnnotations.Annotation::label, StudioOverlayAnnotations.Annotation::severity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("fault workflow.execute.failed", "ERROR"),
                        org.assertj.core.groups.Tuple.tuple("fault workflow.trigger.unavailable", "WARNING"),
                        org.assertj.core.groups.Tuple.tuple("fault workflow.observe.noted", "INFO"));
        assertThat(result.annotations().get(0).detail()).contains("RUNTIME, BLOCKING", "affects offers workflow.execute",
                "evidence INVOCATION_ERROR", "allowed actions retry_workflow");
        List<StudioOverlayAnnotations.Annotation> paths = result.annotations().stream()
                .filter(a -> a.kind().equals("FAULT_PROPAGATION")).toList();
        assertThat(paths).extracting(StudioOverlayAnnotations.Annotation::elementId, StudioOverlayAnnotations.Annotation::elementKind,
                StudioOverlayAnnotations.Annotation::label).containsExactly(
                org.assertj.core.groups.Tuple.tuple("CONTAINS:assembly->flow", "EDGE", "blocked by workflow.execute.failed"),
                org.assertj.core.groups.Tuple.tuple("assembly", "NODE", "blocked by workflow.execute.failed"),
                org.assertj.core.groups.Tuple.tuple("CONTAINS:assembly->flow", "EDGE", "degraded by workflow.trigger.unavailable"),
                org.assertj.core.groups.Tuple.tuple("assembly", "NODE", "degraded by workflow.trigger.unavailable"));
        assertThat(paths.get(0).severity()).isEqualTo("ERROR");
        assertThat(paths.get(2).detail()).contains("leaves Checkout assembly degraded when the parent needs triggers",
                "suppressed when manual start is acceptable");
        assertThat(result.findings()).extracting(StudioOverlayAnnotations.Finding::value)
                .containsExactly("3 declared, 2 propagating to a parent");
        assertThat(result.provenance()).as("claims only").isNull();
        assertThat(result.staleSources()).isEmpty();
    }

    /** A propagating fault on a node without a parent draws no path, and a finding names it; no faults says why. */
    @Test
    void resilienceWithoutAParentOrWithoutFaults() {
        StudioOverlayProjector.Result unparented = StudioOverlayProjector.RESILIENCE.project(
                context(new StudioViewContext.GroundedNode("flow", claim(FAULTY), "Flow", null, null)), session(""));
        assertThat(unparented.annotations()).noneSatisfy(a -> assertThat(a.kind()).isEqualTo("FAULT_PROPAGATION"));
        assertThat(unparented.findings()).extracting(StudioOverlayAnnotations.Finding::label)
                .containsExactly("Declared faults", "Propagation not drawn");
        assertThat(unparented.findings().get(1).value()).contains("Flow (workflow.execute.failed)");

        StudioOverlayProjector.Result none = StudioOverlayProjector.RESILIENCE.project(context(node("flow", SERVICE)), session(""));
        assertThat(none.annotations()).isEmpty();
        assertThat(none.emptyReason().code()).isEqualTo("NO_FAULTS_DECLARED");
    }

    /** DCP fault severities map onto overlay severities. */
    @Test
    void mapsFaultSeverities() {
        assertThat(java.util.Arrays.stream(com.unfurl.dcp.fault.FaultSeverity.values())
                .map(StudioOverlayProjector::faultSeverity).toList())
                .containsExactly("INFO", "WARNING", "WARNING", "ERROR", "ERROR");
    }

    private static StudioViewContext context(StudioViewContext.GroundedNode... nodes) {
        return context(false, nodes);
    }

    /** A context without a compile, whose session's need does or does not require identity-context. */
    private static StudioViewContext context(boolean needsIdentity, StudioViewContext.GroundedNode... nodes) {
        return new StudioViewContext(List.of(nodes), () -> new StudioViewContext.CompiledView(null, null, REQUIRED, Map.of()),
                () -> new StudioVerificationResult(false, new StudioAbsentReason("NOT_SIGNED", "unsigned"), null, false,
                        null, null, null, List.of()),
                () -> needsIdentity);
    }

    private static StudioDraftSession session(String trustPolicyId) {
        return new StudioDraftSession("tenant-a", "assembly-a", "s1", "hash", "DYNAMIC", "", trustPolicyId, "", 2,
                List.of(), List.of(), List.of());
    }

    private static StudioViewContext.GroundedNode node(String nodeId, String claimYaml) {
        return new StudioViewContext.GroundedNode(nodeId, claim(claimYaml));
    }

    private static com.unfurl.dcp.claim.Claim claim(String claimYaml) {
        return PackagedClaim.parse(("claim:\n" + claimYaml.indent(2)).getBytes(StandardCharsets.UTF_8));
    }

    /** A service declaring a blocking, a degrading and a non-propagating fault, in the A.10 shape products ship. */
    private static final String FAULTY = SERVICE_HEAD() + """
            faults:
              emitted:
                - code: workflow.execute.failed
                  category: RUNTIME
                  severity: BLOCKING
                  description: Workflow execution failed.
                  affects: { needs: [], offers: [workflow.execute], constraints: [] }
                  evidence: { signals: [INVOCATION_ERROR] }
                  propagation: { parent_impact: BLOCKED, propagates_when: the parent depends on workflow.execute }
                  remediation: { allowed_actions: [retry_workflow] }
                - code: workflow.trigger.unavailable
                  category: CAPABILITY
                  severity: DEGRADED
                  description: Trigger ingress is unavailable.
                  affects: { needs: [], offers: [workflow.execute], constraints: [] }
                  evidence: { signals: [HEALTH] }
                  propagation: { parent_impact: DEGRADED, propagates_when: the parent needs triggers, suppresses_when: manual start is acceptable }
                  remediation: { allowed_actions: [] }
                - code: workflow.observe.noted
                  category: HEALTH
                  severity: INFO
                  description: Observation is lagging.
                  affects: { needs: [], offers: [workflow.execute], constraints: [] }
                  evidence: { signals: [METRIC_THRESHOLD] }
                  propagation: { parent_impact: NONE }
                  remediation: { allowed_actions: [] }
            metadata: { dcp_version: 0.2.0, claim_version: 1.0.0, created_at: "1970-01-01T00:00:00Z" }
            """;

    /** The service claim up to its faults: everything but the faults and metadata lines. */
    private static String SERVICE_HEAD() {
        return SERVICE.lines().filter(line -> !line.startsWith("faults:") && !line.startsWith("metadata:"))
                .map(line -> line + "\n").collect(java.util.stream.Collectors.joining());
    }

    /** A service that declares authentication, authorization and audit events, enforced only with identity in the contract. */
    private static final String SERVICE = """
            identity: { uri: "urn:unfurl:test:flow", name: flow, kind: COMPONENT, version: 1.0.0, publisher: Unfurl }
            domain:
              summary: flow component
              concerns: [{ concern: workflow-execution, description: Runs workflows }]
              boundary_principles: [owns only workflow execution]
            refusals: [{ concern: unrelated.concern, rationale: This component deliberately owns only workflows., owned_by: host }]
            dependencies: { needs: [] }
            offers:
              - { capability: workflow.execute, description: Runs workflows, consumer_access: ANY, offer_interface: { interface_kind: IN_PROCESS, details: {} }, stability: STABLE, version: 1.0.0, metered: false }
            integration_ports:
              authentication: { required: false, accepted_modes: [JWT], notes: enforced with identity in the contract }
              authorization: { required: false, decision_owner: SELF, permission_prefixes: [flow., dcp.] }
              telemetry: { required: false, signals: [LOGS, AUDIT_EVENTS], correlation_id_required: false }
            faults: { emitted: [] }
            metadata: { dcp_version: 0.2.0, claim_version: 1.0.0, created_at: "1970-01-01T00:00:00Z" }
            """;

    /** An identity provider: offers identity-context over its own protocol, declares no auth ports itself. */
    private static final String IDENTITY_PROVIDER = """
            identity: { uri: "urn:unfurl:test:idp", name: idp, kind: COMPONENT, version: 1.0.0, publisher: Unfurl }
            domain:
              summary: identity provider
              concerns: [{ concern: identity, description: Issues tokens }]
              boundary_principles: [owns only identity]
            refusals: [{ concern: unrelated.concern, rationale: This component deliberately owns only identity., owned_by: host }]
            dependencies: { needs: [] }
            offers:
              - { capability: identity-context, description: Issues tokens, consumer_access: ANY, offer_interface: { interface_kind: EXTERNAL_PROTOCOL, details: { protocol: oauth2 } }, stability: STABLE, version: 1.0.0, metered: false }
            integration_ports: { ports: {} }
            faults: { emitted: [] }
            metadata: { dcp_version: 0.2.0, claim_version: 1.0.0, created_at: "1970-01-01T00:00:00Z" }
            """;
}
