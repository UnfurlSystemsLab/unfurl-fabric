package com.unfurl.fabric.studio;

import com.unfurl.dcp.claim.AuthenticationPort;
import com.unfurl.dcp.claim.AuthorizationPort;
import com.unfurl.dcp.claim.IntegrationPorts;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.TelemetrySignal;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.fault.FaultDeclaration;
import com.unfurl.dcp.fault.FaultSeverity;
import com.unfurl.dcp.fault.ParentImpact;
import com.unfurl.fabric.compiler.DecisionAudit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The perspective overlays Fabric annotates (LLD-fabric-studio-api, <i>StudioOverlayAnnotations</i>): Security & Trust,
 * Performance & Load and Compliance & Governance. Resilience & Faults is P2e.
 *
 * <p>Pattern: <b>Strategy</b>, one constant per overlay, over the same {@link StudioViewContext} the views use: the
 * grounded claims of the draft's nodes and the current compile under the P2a rules. Every value is read from the
 * {@code unfurl-dcp} model or the compiled contract, never parsed from text. An overlay only annotates nodes the context
 * holds, so it can never introduce an element, and it never gates.
 */
enum StudioOverlayProjector {
    /**
     * Security & Trust: each claim's authentication and authorization ports, paired with whether identity is in the
     * composition (root CLAUDE.md rule 6). With a provider of {@code identity-context} they are enforced; without one the
     * components run unauthenticated on a trusted network. Both paths are stated, never inferred.
     */
    SECURITY("security") {
        @Override
        Result project(StudioViewContext context, StudioDraftSession session) {
            boolean identity = identityInComposition(context);
            String mode = identity ? "enforced" : "trusted network";
            List<StudioOverlayAnnotations.Annotation> annotations = new ArrayList<>();
            for (StudioViewContext.GroundedNode node : context.nodes()) {
                IntegrationPorts ports = node.claim().integrationPorts();
                if (ports == null) {
                    continue;
                }
                AuthenticationPort authentication = ports.authentication();
                if (authentication != null) {
                    String modes = authentication.acceptedModes() == null || authentication.acceptedModes().isEmpty()
                            ? "no listed mode" : authentication.acceptedModes().stream().map(Enum::name).collect(Collectors.joining(", "));
                    annotations.add(claimAnnotation(node, "AUTHENTICATION", identity ? "INFO" : "WARNING",
                            "authentication " + mode,
                            (identity ? "callers must authenticate (" + modes + "); identity-context is in the composition"
                                    : "accepts " + modes + " when identity-context is in the contract; it is not, so callers are not authenticated (trusted network)")
                                    + notes(authentication.notes()),
                            "claim.integration_ports.authentication"));
                }
                AuthorizationPort authorization = ports.authorization();
                if (authorization != null) {
                    String owner = authorization.decisionOwner() == null ? "an undeclared owner" : authorization.decisionOwner().name();
                    String prefixes = authorization.permissionPrefixes() == null || authorization.permissionPrefixes().isEmpty()
                            ? "no listed prefix" : String.join(", ", authorization.permissionPrefixes());
                    annotations.add(claimAnnotation(node, "AUTHORIZATION", identity ? "INFO" : "WARNING",
                            "authorization " + mode,
                            "decided by " + owner + " over permissions " + prefixes
                                    + (identity ? "" : "; no permission is checked without identity in the contract")
                                    + notes(authorization.notes()),
                            "claim.integration_ports.authorization"));
                }
            }
            // Whether identity is in the composition, and whether the session's need requires it (P2d): the need decides
            // that identity is mandatory (HLD-needs-driven-identity), the composition shows whether it is provided.
            List<StudioOverlayAnnotations.Finding> findings = annotations.isEmpty() ? List.of() : List.of(
                    new StudioOverlayAnnotations.Finding("Identity", identity
                            ? "in the composition: a component provides identity-context"
                            : "not in the composition: components run unauthenticated on a trusted network",
                            "claim.offers[].capability", null, null),
                    new StudioOverlayAnnotations.Finding("Identity in the needs", context.needsIdentity().get()
                            ? "required: the session's needs require identity-context, so the composition must provide it"
                            : "not required: the session's needs do not ask for identity-context",
                            "needs.required_capabilities[].capability", null, null));
            // Claims only: no compile record is read, so none is named.
            return new Result(annotations, findings, List.of(), new StudioAbsentReason("NO_AUTH_PORTS_DECLARED",
                    "no selected component declares authentication or authorization ports (integration_ports)"), null);
        }
    },

    /** Performance & Load: each child contract's timeout from the current compile; the runtime override is P2d. */
    PERFORMANCE("performance") {
        @Override
        Result project(StudioViewContext context, StudioDraftSession session) {
            StudioViewContext.CompiledView compiled = context.compile().get();
            if (compiled.contract() == null) {
                return Result.compileAbsent(compiled, "contract.child_contracts[].expectations.timeout_ms");
            }
            List<StudioOverlayAnnotations.Annotation> annotations = new ArrayList<>();
            List<CompositionContract> children = compiled.contract().childContracts();
            for (int i = 0; i < children.size(); i++) {
                CompositionContract child = children.get(i);
                Integer timeout = child.expectations() == null ? null : child.expectations().timeoutMs();
                String node = providerNode(compiled, child);
                if (timeout == null || node == null) {
                    continue;
                }
                String capability = child.binding() == null ? "its capability" : child.binding().providerCapability();
                annotations.add(compileAnnotation(compiled, node, "TIMEOUT", "INFO", "timeout " + timeout + " ms",
                        capability + " is called with a " + timeout + " ms timeout by contract " + child.contractId(),
                        "contract.child_contracts[" + i + "].expectations.timeout_ms"));
            }
            // The record is named even when it sets no timeout, so that answer is pinned to the compile it describes.
            return new Result(annotations, List.of(), List.of(), new StudioAbsentReason("NO_TIMEOUTS_DECLARED",
                    "no child contract of the current compile sets a timeout (expectations.timeout_ms)"), compiled.provenance());
        }
    },

    /**
     * Resilience & Faults (P2e): each component's declared faults (DCP HLD-C2 A.10), and, for a fault whose
     * {@code parent_impact} is {@code DEGRADED} or {@code BLOCKED}, its propagation path up the {@code CONTAINS} tree to
     * the immediate parent: the edge and the parent. Further escalation is the parent's own policy, known at runtime, so
     * it is not drawn. Claims only: no compile is read.
     */
    RESILIENCE("resilience") {
        @Override
        Result project(StudioViewContext context, StudioDraftSession session) {
            List<StudioOverlayAnnotations.Annotation> annotations = new ArrayList<>();
            int declared = 0;
            int propagating = 0;
            List<String> unparented = new ArrayList<>();
            for (StudioViewContext.GroundedNode node : context.nodes()) {
                if (node.claim().faults() == null) continue;
                for (FaultDeclaration fault : node.claim().faults().emitted()) {
                    declared++;
                    annotations.add(claimAnnotation(node, "FAULT", faultSeverity(fault.severity()), "fault " + fault.code(),
                            faultDetail(fault), "claim.faults.emitted[" + fault.code() + "]"));
                    ParentImpact impact = fault.propagation().parentImpact();
                    if (impact == null || impact == ParentImpact.NONE) continue;
                    propagating++;
                    if (node.parentNodeId() == null) {
                        unparented.add(node.label() + " (" + fault.code() + ")");
                        continue;
                    }
                    String severity = impact == ParentImpact.BLOCKED ? "ERROR" : "WARNING";
                    String label = impact.name().toLowerCase(java.util.Locale.ROOT) + " by " + fault.code();
                    String detail = node.label() + "'s fault " + fault.code() + " leaves " + node.parentLabel() + " "
                            + impact.name().toLowerCase(java.util.Locale.ROOT) + " when "
                            + fault.propagation().propagatesWhen()
                            + (blank(fault.propagation().suppressesWhen()) ? ""
                            : "; suppressed when " + fault.propagation().suppressesWhen());
                    String source = "claim.faults.emitted[" + fault.code() + "].propagation";
                    annotations.add(new StudioOverlayAnnotations.Annotation(node.containsEdgeId(), "EDGE",
                            "FAULT_PROPAGATION", severity, label, detail, source, null, null));
                    annotations.add(new StudioOverlayAnnotations.Annotation(node.parentNodeId(), "NODE",
                            "FAULT_PROPAGATION", severity, label, detail, source, null, null));
                }
            }
            if (declared == 0) {
                return new Result(List.of(), List.of(), List.of(), new StudioAbsentReason("NO_FAULTS_DECLARED",
                        "no selected component declares a fault (faults.emitted)"), null);
            }
            List<StudioOverlayAnnotations.Finding> findings = new ArrayList<>();
            findings.add(new StudioOverlayAnnotations.Finding("Declared faults", declared + " declared, " + propagating
                    + " propagating to a parent", "claim.faults.emitted", null, null));
            if (!unparented.isEmpty()) {
                findings.add(new StudioOverlayAnnotations.Finding("Propagation not drawn", "no parent in the draft for "
                        + String.join(", ", unparented), "dynamic-dcp edges[CONTAINS]", null, null));
            }
            return new Result(annotations, findings, List.of(), null, null);
        }
    },

    /**
     * Compliance & Governance: the current compile's decision audit, which components emit audit events, and the trust
     * policy's provenance. The trust-policy finding is always present, so the overlay is never empty; the runtime
     * {@code audit_enabled} flag is P2d.
     */
    COMPLIANCE("compliance") {
        @Override
        Result project(StudioViewContext context, StudioDraftSession session) {
            List<StudioOverlayAnnotations.Annotation> annotations = new ArrayList<>();
            for (StudioViewContext.GroundedNode node : context.nodes()) {
                IntegrationPorts ports = node.claim().integrationPorts();
                if (ports != null && ports.telemetry() != null && ports.telemetry().signals() != null
                        && ports.telemetry().signals().contains(TelemetrySignal.AUDIT_EVENTS)) {
                    annotations.add(claimAnnotation(node, "AUDIT_EVENTS", "INFO", "emits audit events",
                            "the claim declares audit events among its telemetry signals" + notes(ports.telemetry().notes()),
                            "claim.integration_ports.telemetry.signals"));
                }
            }
            List<StudioOverlayAnnotations.Finding> findings = new ArrayList<>();
            findings.add(new StudioOverlayAnnotations.Finding("Trust policy", session.trustPolicyId().isBlank()
                    ? "none set: planned under the permissive policy"
                    : session.trustPolicyId() + " (named on the session; Fabric does not apply named trust policies yet)",
                    "session.trustPolicyId", null, null));
            List<StudioOverlayAnnotations.StaleSource> stale = new ArrayList<>();
            StudioViewContext.CompiledView compiled = context.compile().get();
            if (compiled.contract() == null) {
                stale.add(staleSource(compiled, "contract.audit"));
            } else if (compiled.contract().audit() != null) {
                DecisionAudit audit = compiled.contract().audit();
                Long revision = compiled.provenance().sourceRevision();
                String hash = compiled.provenance().contractHash();
                // A contract compiled elsewhere may record no mode; say so rather than print a placeholder.
                String mode = audit.selectionMode() == null || audit.selectionMode().isBlank()
                        ? "not recorded" : audit.selectionMode();
                findings.add(new StudioOverlayAnnotations.Finding("Selection mode", mode,
                        "contract.audit.selection_mode", revision, hash));
                findings.add(new StudioOverlayAnnotations.Finding("Alternatives considered",
                        listText(audit.alternativesConsidered()), "contract.audit.alternatives_considered", revision, hash));
                findings.add(new StudioOverlayAnnotations.Finding("Selection reasons",
                        listText(audit.selectionReasons()), "contract.audit.selection_reasons", revision, hash));
                findings.add(new StudioOverlayAnnotations.Finding("Score breakdown", mapText(audit.scoreBreakdown()),
                        "contract.audit.score_breakdown", revision, hash));
                findings.add(new StudioOverlayAnnotations.Finding("Planning warnings",
                        listText(audit.planningWarnings()), "contract.audit.planning_warnings", revision, hash));
            }
            return new Result(annotations, findings, stale, null, compiled.provenance());
        }
    };

    /** The identity capability whose provider makes identity part of the composition (Fabric HLD-needs-driven-identity). */
    static final String IDENTITY_CAPABILITY = "identity-context";

    private final String id;

    StudioOverlayProjector(String id) {
        this.id = id;
    }

    /** The overlay's id on the route. */
    String id() {
        return id;
    }

    /**
     * The overlay's annotations, findings and left-out sources for the draft.
     *
     * @param context the grounded nodes and the current compile.
     * @param session the draft session (for its trust policy).
     * @return what the overlay found, and its absent reason when it found nothing.
     */
    abstract Result project(StudioViewContext context, StudioDraftSession session);

    /**
     * The projector for a route's overlay id.
     *
     * @throws IllegalArgumentException {@code OVERLAY_UNKNOWN} for anything but the four overlays.
     */
    static StudioOverlayProjector forOverlay(String overlay) {
        for (StudioOverlayProjector projector : values()) {
            if (projector.id.equals(overlay)) {
                return projector;
            }
        }
        throw new IllegalArgumentException("OVERLAY_UNKNOWN: overlay must be security, performance, resilience or compliance, got '"
                + overlay + "'");
    }

    /**
     * What an overlay found.
     *
     * @param annotations  annotations on the context's nodes.
     * @param findings     overlay-level facts.
     * @param staleSources compile-derived sources left out.
     * @param emptyReason  the reason when there are no annotations and no findings.
     * @param provenance   the compile record read, for an overlay that reads the compile; null when it reads none, or
     *                     the session has none.
     */
    record Result(List<StudioOverlayAnnotations.Annotation> annotations, List<StudioOverlayAnnotations.Finding> findings,
                  List<StudioOverlayAnnotations.StaleSource> staleSources, StudioAbsentReason emptyReason,
                  StudioCompileProvenance provenance) {
        /** An overlay whose only data is compile-derived, when the compile cannot be read: its reason, and the stale source. */
        static Result compileAbsent(StudioViewContext.CompiledView compiled, String source) {
            return new Result(List.of(), List.of(), List.of(staleSource(compiled, source)), compiled.absentReason(),
                    compiled.provenance());
        }
    }

    /** Whether a grounded node's claim offers {@code identity-context}: identity is then in the composition. */
    static boolean identityInComposition(StudioViewContext context) {
        return context.nodes().stream().anyMatch(node -> node.claim().offers().stream()
                .map(Offer::capability).anyMatch(IDENTITY_CAPABILITY::equals));
    }

    /** A DCP fault severity as an overlay severity: info stays info, warning and degraded warn, critical and blocking are errors. */
    static String faultSeverity(FaultSeverity severity) {
        if (severity == null) return "WARNING";
        return switch (severity) {
            case INFO -> "INFO";
            case WARNING, DEGRADED -> "WARNING";
            case CRITICAL, BLOCKING -> "ERROR";
        };
    }

    /** What a declared fault says, in one line: category, severity, description, affected surfaces, evidence, actions. */
    private static String faultDetail(FaultDeclaration fault) {
        List<String> parts = new ArrayList<>();
        parts.add((fault.category() == null ? "uncategorized" : fault.category().name()) + ", "
                + (fault.severity() == null ? "no severity" : fault.severity().name()) + ": " + fault.description());
        if (!fault.affects().needs().isEmpty()) parts.add("affects needs " + String.join(", ", fault.affects().needs()));
        if (!fault.affects().offers().isEmpty()) parts.add("affects offers " + String.join(", ", fault.affects().offers()));
        if (!fault.affects().constraints().isEmpty()) {
            parts.add("affects constraints " + String.join(", ", fault.affects().constraints()));
        }
        if (fault.evidence() != null && !fault.evidence().signals().isEmpty()) {
            parts.add("evidence " + fault.evidence().signals().stream().map(Enum::name).collect(Collectors.joining(", ")));
        }
        if (fault.remediation() != null && !fault.remediation().allowedActions().isEmpty()) {
            parts.add("allowed actions " + String.join(", ", fault.remediation().allowedActions()));
        }
        return String.join("; ", parts);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static StudioOverlayAnnotations.Annotation claimAnnotation(StudioViewContext.GroundedNode node, String kind,
                                                                       String severity, String label, String detail,
                                                                       String source) {
        return new StudioOverlayAnnotations.Annotation(node.nodeId(), "NODE", kind, severity, label, detail, source, null, null);
    }

    private static StudioOverlayAnnotations.Annotation compileAnnotation(StudioViewContext.CompiledView compiled, String node,
                                                                         String kind, String severity, String label,
                                                                         String detail, String source) {
        return new StudioOverlayAnnotations.Annotation(node, "NODE", kind, severity, label, detail, source,
                compiled.provenance().sourceRevision(), compiled.provenance().contractHash());
    }

    private static StudioOverlayAnnotations.StaleSource staleSource(StudioViewContext.CompiledView compiled, String source) {
        return new StudioOverlayAnnotations.StaleSource(source,
                compiled.provenance() == null ? null : compiled.provenance().sourceRevision(),
                compiled.provenance() == null ? null : compiled.provenance().contractHash(), compiled.absentReason());
    }

    /** A child contract's provider node id, when one of the draft's grounded nodes is its provider party. */
    private static String providerNode(StudioViewContext.CompiledView compiled, CompositionContract child) {
        return child.parties() == null || child.parties().provider() == null ? null
                : compiled.providerNode(child.parties().provider().claimUri()).orElse(null);
    }

    private static String notes(String notes) {
        return notes == null || notes.isBlank() ? "" : ". " + notes;
    }

    private static String listText(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join("; ", values);
    }

    private static String mapText(Map<String, Integer> values) {
        return values == null || values.isEmpty() ? "none" : new java.util.TreeMap<>(values).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(", "));
    }
}
