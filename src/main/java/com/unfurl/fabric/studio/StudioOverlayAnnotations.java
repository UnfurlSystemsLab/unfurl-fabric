package com.unfurl.fabric.studio;

import java.util.List;

/**
 * The response of {@code GET .../overlays/{overlay}} (LLD-fabric-studio-api, <i>StudioOverlayAnnotations</i>): one
 * perspective overlay's annotations on the draft's nodes, and its overlay-level findings.
 *
 * <p>Pattern: immutable <b>DTO</b> (read model). An overlay annotates and never gates: it changes no session, revision or
 * contract, and it never introduces an element id that is not a node of the draft's projection. {@code available} is
 * true exactly when there is at least one annotation or finding. {@code provenance} names the compile record an overlay
 * that reads the compile was answered from, as a compile-derived view section does, so Studio can pin the answer to it
 * even when nothing came of that record.
 *
 * @param overlay             {@code security}, {@code performance} or {@code compliance}.
 * @param view                the view the overlay was asked for.
 * @param tenantId            the tenant.
 * @param assemblyId          the assembly.
 * @param sessionId           the draft session.
 * @param revision            the session revision annotated.
 * @param provenance          the compile record read, for an overlay that reads the compile ({@code performance},
 *                            {@code compliance}) when the session has one, current or not; null for {@code security},
 *                            which reads claims only, and before any compile.
 * @param available           whether there is anything to show.
 * @param absentReason        why not, when unavailable.
 * @param annotations         annotations on {@code /dynamic-dcp} nodes.
 * @param findings            overlay-level facts.
 * @param staleSources        compile-derived sources left out because they are not current.
 * @param diagnostics         nodes that could not be grounded, and the like.
 * @param diagnosticArtifacts the hash-pinned snapshot of this response.
 */
public record StudioOverlayAnnotations(
        String overlay,
        String view,
        String tenantId,
        String assemblyId,
        String sessionId,
        long revision,
        StudioCompileProvenance provenance,
        boolean available,
        StudioAbsentReason absentReason,
        List<Annotation> annotations,
        List<Finding> findings,
        List<StaleSource> staleSources,
        List<StudioDcpDiagnostic> diagnostics,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    /** Compact constructor: enforces the availability invariant and freezes the lists. */
    public StudioOverlayAnnotations {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        findings = findings == null ? List.of() : List.copyOf(findings);
        staleSources = staleSources == null ? List.of() : List.copyOf(staleSources);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
        boolean anything = !annotations.isEmpty() || !findings.isEmpty();
        if (available != anything || available == (absentReason != null)) {
            throw new IllegalArgumentException("an overlay is available exactly when it has annotations or findings");
        }
    }

    /** The same response with its diagnostic snapshot attached. */
    StudioOverlayAnnotations withDiagnosticArtifacts(List<StudioExportArtifact> artifacts) {
        return new StudioOverlayAnnotations(overlay, view, tenantId, assemblyId, sessionId, revision, provenance, available,
                absentReason, annotations, findings, staleSources, diagnostics, artifacts);
    }

    /**
     * An annotation on one node of the draft's projection.
     *
     * @param elementId      the {@code /dynamic-dcp} node id.
     * @param elementKind    always {@code NODE} in P2c.
     * @param kind           what it says, for example {@code AUTHENTICATION} or {@code TIMEOUT}.
     * @param severity       {@code INFO} or {@code WARNING}.
     * @param label          the short badge text.
     * @param detail         the full statement.
     * @param source         the DCP field path it was read from.
     * @param sourceRevision the compile record's revision, when compile-derived.
     * @param contractHash   the compile record's contract hash, when compile-derived.
     */
    public record Annotation(String elementId, String elementKind, String kind, String severity, String label,
                             String detail, String source, Long sourceRevision, String contractHash) {
    }

    /**
     * An overlay-level fact that belongs to no element, such as the decision audit.
     *
     * @param label          what it is.
     * @param value          its value.
     * @param source         the field path it was read from.
     * @param sourceRevision the compile record's revision, when compile-derived.
     * @param contractHash   the compile record's contract hash, when compile-derived.
     */
    public record Finding(String label, String value, String source, Long sourceRevision, String contractHash) {
    }

    /**
     * A compile-derived source left out because the record is not current or consistent.
     *
     * @param source         what was left out, for example {@code contract.child_contracts[].expectations.timeout_ms}.
     * @param sourceRevision the record's revision, when there is one.
     * @param contractHash   the record's contract hash, when there is one.
     * @param reason         why, as the compile rules name it.
     */
    public record StaleSource(String source, Long sourceRevision, String contractHash, StudioAbsentReason reason) {
    }
}
