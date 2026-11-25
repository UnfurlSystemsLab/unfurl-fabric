package com.unfurl.fabric.verify;

import java.util.List;

/**
 * The difference between two compiled contracts, as {@link ContractDiffer} computes it: what {@code fabric diff} prints
 * and what Studio's {@code contract-diff} route returns (LLD-fabric-studio-api, <i>Contract diff and verification</i>).
 *
 * <p>Pattern: immutable <b>value object</b>. Every key list is sorted, so the same two contracts always give the same
 * diff. A key is the identity the comparison uses: artifact coordinates for selections,
 * {@code componentId/capability} for deployment entries, and the port name for substrate ports.
 *
 * @param leftCanonicalHash           canonical hash of the left (base) contract.
 * @param rightCanonicalHash          canonical hash of the right (target) contract.
 * @param canonicalHashChanged        whether the two canonical hashes differ.
 * @param contractIdChanged           whether the root contracts' ids differ.
 * @param addedSelections             coordinates selected only on the right.
 * @param removedSelections           coordinates selected only on the left.
 * @param changedSelections           coordinates selected on both sides whose artifact, claim, binding or shape differ.
 * @param addedDeploymentEntries      deployment entries only on the right.
 * @param removedDeploymentEntries    deployment entries only on the left.
 * @param changedDeploymentShapes     deployment entries on both sides whose shape, binding, endpoint or ports differ.
 * @param leftSubstrateProfileHash    the left contract's substrate profile hash, or null when it records none.
 * @param rightSubstrateProfileHash   the right contract's substrate profile hash, or null when it records none.
 * @param substrateProfileHashChanged whether the two profile hashes differ.
 * @param ports                       the substrate port delta, or null when the profiles were not supplied (a compiled
 *                                    contract carries only its profile's hash).
 */
public record ContractDiff(
        String leftCanonicalHash,
        String rightCanonicalHash,
        boolean canonicalHashChanged,
        boolean contractIdChanged,
        List<String> addedSelections,
        List<String> removedSelections,
        List<String> changedSelections,
        List<String> addedDeploymentEntries,
        List<String> removedDeploymentEntries,
        List<DeploymentShapeChange> changedDeploymentShapes,
        String leftSubstrateProfileHash,
        String rightSubstrateProfileHash,
        boolean substrateProfileHashChanged,
        PortDelta ports
) {
    /** Compact constructor: freezes every list. */
    public ContractDiff {
        addedSelections = List.copyOf(addedSelections);
        removedSelections = List.copyOf(removedSelections);
        changedSelections = List.copyOf(changedSelections);
        addedDeploymentEntries = List.copyOf(addedDeploymentEntries);
        removedDeploymentEntries = List.copyOf(removedDeploymentEntries);
        changedDeploymentShapes = List.copyOf(changedDeploymentShapes);
    }

    /**
     * One deployment entry present on both sides that changed.
     *
     * @param key       the entry's {@code componentId/capability}.
     * @param fromShape the left deployment shape.
     * @param toShape   the right deployment shape.
     * @param fromPorts the left entry's required substrate ports.
     * @param toPorts   the right entry's required substrate ports.
     */
    public record DeploymentShapeChange(String key, String fromShape, String toShape, List<String> fromPorts,
                                        List<String> toPorts) {
        /** Compact constructor: freezes the port lists. */
        public DeploymentShapeChange {
            fromPorts = List.copyOf(fromPorts);
            toPorts = List.copyOf(toPorts);
        }
    }

    /**
     * The substrate port delta between two substrate profiles.
     *
     * @param added   ports required only on the right.
     * @param removed ports required only on the left.
     * @param changed ports on both sides whose capability, range, binding, required flag or constraints differ.
     */
    public record PortDelta(List<String> added, List<String> removed, List<String> changed) {
        /** Compact constructor: freezes the lists. */
        public PortDelta {
            added = List.copyOf(added);
            removed = List.copyOf(removed);
            changed = List.copyOf(changed);
        }
    }
}
