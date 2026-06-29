package com.unfurl.fabric.needs;

/**
 * One capability a need demands (or prefers): the capability name, an acceptable version range, and
 * whether it is required or optional.
 *
 * <p>Pattern: immutable <b>value object</b> with intent-revealing {@code requiredOf}/{@code optionalOf}
 * factories.
 *
 * @param capability        the capability name (required).
 * @param capabilityVersion acceptable capability version range (defaults to any).
 * @param required          true if mandatory; false if optional/nice-to-have.
 */
public record CapabilityRequirement(
        String capability,
        CapabilityVersionRange capabilityVersion,
        boolean required
) {
    /** Compact constructor: requires a capability name; defaults a null version range to "any". */
    public CapabilityRequirement {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("capability name is required");
        }
        if (capabilityVersion == null) {
            capabilityVersion = CapabilityVersionRange.any();
        }
    }

    /**
     * Factory for a required capability.
     *
     * @param capability   the capability name.
     * @param versionRange the npm-style version range.
     * @return a required requirement.
     */
    public static CapabilityRequirement requiredOf(String capability, String versionRange) {
        return new CapabilityRequirement(capability, new CapabilityVersionRange(versionRange), true);
    }

    /**
     * Factory for an optional capability.
     *
     * @param capability   the capability name.
     * @param versionRange the npm-style version range.
     * @return an optional requirement.
     */
    public static CapabilityRequirement optionalOf(String capability, String versionRange) {
        return new CapabilityRequirement(capability, new CapabilityVersionRange(versionRange), false);
    }
}
