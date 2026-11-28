package com.unfurl.fabric.studio;

/**
 * Data Transfer Object: one relationship of the dynamic DCP projection between two of its nodes.
 *
 * <p>{@code derivation} says where a {@code CONTAINS} edge came from (LLD-fabric-studio-api, Containment derived from
 * bindings): {@link #DECLARED} for containment the claims or their containment bridge declare, and
 * {@link #DERIVED_FROM_BINDINGS} for a parent Fabric derived from the composition's matched bindings. A derived edge is
 * never presented as declared.
 */
public record StudioDynamicDcpEdge(
        String fromNodeId,
        String toNodeId,
        String relationship,
        String derivation
) {
    /** Containment the claims (or their containment bridge) declare. */
    public static final String DECLARED = "DECLARED";
    /** Containment Fabric derived from matched bindings: the parent uses the child. */
    public static final String DERIVED_FROM_BINDINGS = "DERIVED_FROM_BINDINGS";

    /** Invariant constructor: both ends are required; the relationship defaults to CONTAINS, the derivation to declared. */
    public StudioDynamicDcpEdge {
        if (fromNodeId == null || fromNodeId.isBlank()) {
            throw new IllegalArgumentException("fromNodeId is required");
        }
        if (toNodeId == null || toNodeId.isBlank()) {
            throw new IllegalArgumentException("toNodeId is required");
        }
        relationship = relationship == null || relationship.isBlank() ? "CONTAINS" : relationship;
        derivation = derivation == null || derivation.isBlank() ? DECLARED : derivation;
    }

    /** A declared relationship. */
    public StudioDynamicDcpEdge(String fromNodeId, String toNodeId, String relationship) {
        this(fromNodeId, toNodeId, relationship, DECLARED);
    }
}
