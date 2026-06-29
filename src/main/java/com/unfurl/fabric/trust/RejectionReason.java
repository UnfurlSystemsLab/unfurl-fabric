package com.unfurl.fabric.trust;

/**
 * Why a catalog entry was rejected by the trust policy. Preserved per-entry so diagnostic
 * commands (fabric explain-rejection) can surface the exact reason an entry was excluded
 * from matching.
 *
 * <p>Pattern: <b>sealed interface as an algebraic/sum type</b>. Each permitted record is one rejection
 * variant carrying its own context fields plus a human-readable {@code detail}; sealing keeps the set of
 * reasons closed and exhaustively switchable.
 */
public sealed interface RejectionReason
        permits RejectionReason.VendorNotTrusted,
                RejectionReason.ArtifactGroupNotAllowed,
                RejectionReason.StabilityBelowFloor,
                RejectionReason.LifecycleNotAllowed,
                RejectionReason.UnsignedClaim,
                RejectionReason.CapabilityDenied,
                RejectionReason.CapabilityNotAllowed {

    /** @return the human-readable explanation for this rejection. */
    String detail();

    /**
     * The entry's publisher is not in the policy's trusted-vendor list.
     *
     * @param vendor the offending publisher/vendor.
     * @param detail human-readable explanation.
     */
    record VendorNotTrusted(String vendor, String detail) implements RejectionReason {
        /** Convenience constructor deriving the detail message. @param vendor the untrusted vendor. */
        public VendorNotTrusted(String vendor) {
            this(vendor, "vendor " + vendor + " is not in the trusted vendor list");
        }
    }

    /**
     * The artifact's group is not in the policy's allowed-group list.
     *
     * @param group  the offending artifact group.
     * @param detail human-readable explanation.
     */
    record ArtifactGroupNotAllowed(String group, String detail) implements RejectionReason {
        /** Convenience constructor deriving the detail message. @param group the disallowed group. */
        public ArtifactGroupNotAllowed(String group) {
            this(group, "artifact group " + group + " is not in the allowed group list");
        }
    }

    /**
     * An offer's stability is below the policy's minimum stability floor.
     *
     * @param observed the observed stability.
     * @param floor    the minimum required stability.
     * @param detail   human-readable explanation.
     */
    record StabilityBelowFloor(String observed, String floor, String detail) implements RejectionReason {
        /** Convenience constructor deriving the detail message. @param observed seen; @param floor required. */
        public StabilityBelowFloor(String observed, String floor) {
            this(observed, floor, "offer stability " + observed + " is below floor " + floor);
        }
    }

    /**
     * The entry's lifecycle status is not permitted by the policy.
     *
     * @param status the offending lifecycle status.
     * @param detail human-readable explanation.
     */
    record LifecycleNotAllowed(String status, String detail) implements RejectionReason {
        /** Convenience constructor deriving the detail message. @param status the disallowed status. */
        public LifecycleNotAllowed(String status) {
            this(status, "lifecycle status " + status + " is not permitted by trust policy");
        }
    }

    /**
     * The policy requires signed claims but the entry is unsigned.
     *
     * @param detail human-readable explanation.
     */
    record UnsignedClaim(String detail) implements RejectionReason {
        /** Convenience constructor with the standard detail message. */
        public UnsignedClaim() {
            this("trust policy requires signed claims and this entry has no signature");
        }
    }

    /**
     * An offer's capability matches a denied-capability pattern.
     *
     * @param capability the denied capability.
     * @param detail     human-readable explanation.
     */
    record CapabilityDenied(String capability, String detail) implements RejectionReason {
        /** Convenience constructor deriving the detail message. @param capability the denied capability. */
        public CapabilityDenied(String capability) {
            this(capability, "capability " + capability + " is on the denied list");
        }
    }

    /**
     * An offer's capability matches none of the allowed-capability patterns.
     *
     * @param capability the unmatched capability.
     * @param detail     human-readable explanation.
     */
    record CapabilityNotAllowed(String capability, String detail) implements RejectionReason {
        /** Convenience constructor deriving the detail message. @param capability the unmatched capability. */
        public CapabilityNotAllowed(String capability) {
            this(capability, "capability " + capability + " does not match any allowed pattern");
        }
    }
}
