package com.unfurl.fabric.studio;

import com.unfurl.dcp.claim.ArtifactRef;

import java.util.List;
import java.util.Map;

/**
 * The response of {@code GET .../views/{view}} (LLD-fabric-studio-api, <i>StudioViewProjection</i>): the typed data one
 * Studio workbench view needs beyond {@code /dynamic-dcp}, read from the claims Fabric grounded and, for
 * compile-derived sections, from the session's current compile record.
 *
 * <p>Pattern: immutable <b>DTO</b> (read model). Only the requested view's sections are set; the others are null and
 * not serialized. It never changes a session and is never a validity source.
 *
 * @param view                   {@code flow}, {@code data}, {@code topology} or {@code runtime}.
 * @param tenantId               the tenant.
 * @param assemblyId             the assembly.
 * @param sessionId              the draft session projected.
 * @param revision               the session revision projected.
 * @param externalOwners         flow: dependencies bound outside the catalog.
 * @param externalProtocolOffers flow: offers reached over the provider's own protocol.
 * @param ownedState             data: state each concern owns.
 * @param ownedDecisions         data: decisions each concern makes.
 * @param operations             data: documented operations and their schemas.
 * @param dataMappings           data, compile-derived: child contracts' data mappings.
 * @param transports             topology, compile-derived: child contracts' transports.
 * @param monitoring             runtime: declared monitoring ports.
 * @param telemetry              runtime: declared telemetry ports.
 * @param verification           runtime: the current compile's verification.
 * @param diagnostics            nodes that could not be grounded, and the like.
 * @param diagnosticArtifacts    the hash-pinned snapshot of this response.
 */
public record StudioViewProjection(
        String view,
        String tenantId,
        String assemblyId,
        String sessionId,
        long revision,
        StudioViewSection<ExternalOwner> externalOwners,
        StudioViewSection<ExternalProtocolOffer> externalProtocolOffers,
        StudioViewSection<OwnedState> ownedState,
        StudioViewSection<OwnedDecision> ownedDecisions,
        StudioViewSection<Operation> operations,
        StudioViewSection<DataMapping> dataMappings,
        StudioViewSection<Transport> transports,
        StudioViewSection<Monitoring> monitoring,
        StudioViewSection<Telemetry> telemetry,
        StudioVerificationResult verification,
        List<StudioDcpDiagnostic> diagnostics,
        List<StudioExportArtifact> diagnosticArtifacts
) {
    /** Compact constructor: freezes the lists. */
    public StudioViewProjection {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        diagnosticArtifacts = diagnosticArtifacts == null ? List.of() : List.copyOf(diagnosticArtifacts);
    }

    /** The same projection with its diagnostic snapshot attached. */
    StudioViewProjection withDiagnosticArtifacts(List<StudioExportArtifact> artifacts) {
        return new StudioViewProjection(view, tenantId, assemblyId, sessionId, revision, externalOwners,
                externalProtocolOffers, ownedState, ownedDecisions, operations, dataMappings, transports, monitoring,
                telemetry, verification, diagnostics, artifacts);
    }

    /** A dependency bound outside the catalog; every node needing the same owner points at one boundary node. */
    public record ExternalOwner(String boundaryNodeId, String owner, String dependency, String neededByNodeId,
                                String source) {
    }

    /** An offer consumers reach over the provider's own protocol (DCP HLD-C2 A.6). */
    public record ExternalProtocolOffer(String nodeId, String capability, String protocol, String source) {
    }

    /** State a concern owns (DCP HLD-C2 A.3.2). */
    public record OwnedState(String nodeId, String concern, String resource, String description, String sensitivity,
                             String source) {
    }

    /** A decision a concern makes (DCP HLD-C2 A.3.3). */
    public record OwnedDecision(String nodeId, String concern, String decision, String description, String authority,
                                String source) {
    }

    /** A documented operation of an offer, with its payload schemas as the claim names them (DCP HLD-C2 A.6). */
    public record Operation(String nodeId, String capability, String operation, String summary, ArtifactRef requestSchema,
                            ArtifactRef responseSchema, String source) {
    }

    /** A child contract's data mapping (DCP HLD-C2 §B); {@code providerNodeId} is null when no node maps to it. */
    public record DataMapping(String childContractId, String providerNodeId, Map<String, String> inbound,
                              Map<String, String> outbound, String source) {
        /** Compact constructor: freezes the maps. */
        public DataMapping {
            inbound = inbound == null ? Map.of() : Map.copyOf(inbound);
            outbound = outbound == null ? Map.of() : Map.copyOf(outbound);
        }
    }

    /** A child contract's transport (DCP HLD-C2 §B); {@code providerNodeId} is null when no node maps to it. */
    public record Transport(String childContractId, String providerNodeId, String kind, Map<String, Object> details,
                            String source) {
        /** Compact constructor: freezes the details, keeping any null values the contract carries. */
        public Transport {
            details = details == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
        }
    }

    /**
     * A declared monitoring port (DCP HLD-C2 A.9), passed through as DCP holds it: a member the claim did not write is
     * null here and absent on the wire, never defaulted ({@code healthEndpoints} is optional in A.9).
     */
    public record Monitoring(String nodeId, Boolean required, List<String> healthEndpoints, Boolean readinessRequired,
                             Boolean livenessRequired, String notes, String source) {
        /** Compact constructor: freezes the endpoints when present. */
        public Monitoring {
            healthEndpoints = healthEndpoints == null ? null : List.copyOf(healthEndpoints);
        }
    }

    /** A declared telemetry port (DCP HLD-C2 A.9), passed through as DCP holds it, never defaulted. */
    public record Telemetry(String nodeId, Boolean required, List<String> signals, Boolean correlationIdRequired,
                            String notes, String source) {
        /** Compact constructor: freezes the signals when present. */
        public Telemetry {
            signals = signals == null ? null : List.copyOf(signals);
        }
    }
}
