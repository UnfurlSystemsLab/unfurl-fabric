package com.unfurl.fabric.trust;

/**
 * Why a catalog entry was rejected by the trust policy. Preserved per-entry so diagnostic
 * commands (fabric explain-rejection) can surface the exact reason an entry was excluded
 * from matching.
 */
public sealed interface RejectionReason
        permits RejectionReason.VendorNotTrusted,
                RejectionReason.ArtifactGroupNotAllowed,
                RejectionReason.StabilityBelowFloor,
                RejectionReason.LifecycleNotAllowed,
                RejectionReason.UnsignedClaim,
                RejectionReason.CapabilityDenied,
                RejectionReason.CapabilityNotAllowed {

    String detail();

    record VendorNotTrusted(String vendor, String detail) implements RejectionReason {
        public VendorNotTrusted(String vendor) {
            this(vendor, "vendor " + vendor + " is not in the trusted vendor list");
        }
    }

    record ArtifactGroupNotAllowed(String group, String detail) implements RejectionReason {
        public ArtifactGroupNotAllowed(String group) {
            this(group, "artifact group " + group + " is not in the allowed group list");
        }
    }

    record StabilityBelowFloor(String observed, String floor, String detail) implements RejectionReason {
        public StabilityBelowFloor(String observed, String floor) {
            this(observed, floor, "offer stability " + observed + " is below floor " + floor);
        }
    }

    record LifecycleNotAllowed(String status, String detail) implements RejectionReason {
        public LifecycleNotAllowed(String status) {
            this(status, "lifecycle status " + status + " is not permitted by trust policy");
        }
    }

    record UnsignedClaim(String detail) implements RejectionReason {
        public UnsignedClaim() {
            this("trust policy requires signed claims and this entry has no signature");
        }
    }

    record CapabilityDenied(String capability, String detail) implements RejectionReason {
        public CapabilityDenied(String capability) {
            this(capability, "capability " + capability + " is on the denied list");
        }
    }

    record CapabilityNotAllowed(String capability, String detail) implements RejectionReason {
        public CapabilityNotAllowed(String capability) {
            this(capability, "capability " + capability + " does not match any allowed pattern");
        }
    }
}
