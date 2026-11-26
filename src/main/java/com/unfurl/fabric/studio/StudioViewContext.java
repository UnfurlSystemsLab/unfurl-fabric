package com.unfurl.fabric.studio;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.fabric.compiler.CompiledContract;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What a {@link StudioViewProjector} or {@link StudioOverlayProjector} reads: the draft's grounded component nodes, the
 * current compile (lazily, and only when it is current and consistent), the current compile's verification, and whether
 * the session's need requires {@code identity-context} (LLD-fabric-studio-api, <i>StudioViewProjection</i>,
 * <i>StudioOverlayAnnotations</i>).
 *
 * <p>Pattern: <b>Parameter Object</b> built once per request by {@code StudioCatalogService.viewProjection}. The compile
 * and the verification are memoized {@link Supplier}s, so a view that does not read them never loads the record's bytes
 * or runs a verification.
 *
 * @param nodes        the draft's component nodes whose claim grounded, in projection order.
 * @param compile      the current compile, or why it cannot be read; evaluated at most once.
 * @param verification the current compile's verification; evaluated at most once.
 * @param needsIdentity whether the session's need (the need compile uses) requires {@code identity-context}; evaluated
 *                      at most once.
 */
record StudioViewContext(List<GroundedNode> nodes, Supplier<CompiledView> compile,
                         Supplier<StudioVerificationResult> verification, Supplier<Boolean> needsIdentity) {
    /** Compact constructor: freezes the nodes and memoizes the suppliers. */
    StudioViewContext {
        nodes = List.copyOf(nodes);
        compile = memoize(compile);
        verification = memoize(verification);
        needsIdentity = memoize(needsIdentity);
    }

    /**
     * One component node of the draft's {@code /dynamic-dcp} projection and the claim compile grounds it to.
     *
     * @param nodeId the {@code /dynamic-dcp} node id.
     * @param claim  the claim read from the node's admitted artifact.
     */
    record GroundedNode(String nodeId, Claim claim) {
    }

    /**
     * The current compile record's contract, or why it is not available.
     *
     * @param contract       the compiled contract, when available.
     * @param provenance     the record, when there is one.
     * @param absentReason   why the contract is not available, when it is not.
     * @param providerNodes  the node id of each grounded claim URI, for mapping a child contract's provider party.
     */
    record CompiledView(CompiledContract contract, StudioCompileProvenance provenance, StudioAbsentReason absentReason,
                        Map<URI, String> providerNodes) {
        /** The node a child contract's provider party maps to, when one does. */
        Optional<String> providerNode(URI providerClaimUri) {
            return Optional.ofNullable(providerClaimUri == null ? null : providerNodes.get(providerClaimUri));
        }
    }

    /** A supplier that evaluates its delegate once, on first use. */
    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        return new Supplier<>() {
            private boolean evaluated;
            private T value;

            @Override
            public synchronized T get() {
                if (!evaluated) {
                    value = delegate.get();
                    evaluated = true;
                }
                return value;
            }
        };
    }
}
