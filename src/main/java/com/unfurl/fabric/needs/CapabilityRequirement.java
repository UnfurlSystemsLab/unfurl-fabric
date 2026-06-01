package com.unfurl.fabric.needs;

public record CapabilityRequirement(
        String capability,
        CapabilityVersionRange capabilityVersion,
        boolean required
) {
    public CapabilityRequirement {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("capability name is required");
        }
        if (capabilityVersion == null) {
            capabilityVersion = CapabilityVersionRange.any();
        }
    }

    public static CapabilityRequirement requiredOf(String capability, String versionRange) {
        return new CapabilityRequirement(capability, new CapabilityVersionRange(versionRange), true);
    }

    public static CapabilityRequirement optionalOf(String capability, String versionRange) {
        return new CapabilityRequirement(capability, new CapabilityVersionRange(versionRange), false);
    }
}
