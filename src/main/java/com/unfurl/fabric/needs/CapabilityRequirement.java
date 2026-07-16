package com.unfurl.fabric.needs;

import java.util.Map;

/**
 * One capability a need demands (or prefers): the capability name, an acceptable version range, and
 * whether it is required or optional. Required offer details reuse DCP's deterministic
 * {@code OfferInterface.details} subset matching so product modes such as Foundry harness execution
 * remain governed by claim metadata instead of Fabric-specific flags.
 *
 * <p>Pattern: immutable <b>value object</b> with intent-revealing {@code requiredOf}/{@code optionalOf}
 * factories.
 *
 * @param capability           the capability name (required).
 * @param capabilityVersion    acceptable capability version range (defaults to any).
 * @param required             true if mandatory; false if optional/nice-to-have.
 * @param requiredOfferDetails DCP offer-interface detail subset that a provider offer must satisfy.
 */
public record CapabilityRequirement(
        String capability,
        CapabilityVersionRange capabilityVersion,
        boolean required,
        Map<String, Object> requiredOfferDetails
) {
    /**
     * Compatibility constructor: creates a capability requirement without offer-detail constraints.
     *
     * @param capability        the capability name.
     * @param capabilityVersion acceptable version range.
     * @param required          true for mandatory requirements.
     */
    public CapabilityRequirement(String capability, CapabilityVersionRange capabilityVersion, boolean required) {
        this(capability, capabilityVersion, required, Map.of());
    }

    /**
     * Compact constructor: requires a capability name, defaults a null version range to "any",
     * and defensively copies offer-detail constraints.
     */
    public CapabilityRequirement {
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("capability name is required");
        }
        if (capabilityVersion == null) {
            capabilityVersion = CapabilityVersionRange.any();
        }
        requiredOfferDetails = requiredOfferDetails == null ? Map.of() : Map.copyOf(requiredOfferDetails);
    }

    /**
     * Factory for a required capability.
     *
     * @param capability   the capability name.
     * @param versionRange the npm-style version range.
     * @return a required requirement.
     */
    public static CapabilityRequirement requiredOf(String capability, String versionRange) {
        return requiredOf(capability, versionRange, Map.of());
    }

    /**
     * Factory for a required capability with DCP offer-detail constraints.
     *
     * @param capability           the capability name.
     * @param versionRange         the npm-style version range.
     * @param requiredOfferDetails DCP offer-interface detail subset to match.
     * @return a required requirement.
     */
    public static CapabilityRequirement requiredOf(
            String capability,
            String versionRange,
            Map<String, Object> requiredOfferDetails
    ) {
        return new CapabilityRequirement(
                capability,
                new CapabilityVersionRange(versionRange),
                true,
                requiredOfferDetails);
    }

    /**
     * Factory for an optional capability.
     *
     * @param capability   the capability name.
     * @param versionRange the npm-style version range.
     * @return an optional requirement.
     */
    public static CapabilityRequirement optionalOf(String capability, String versionRange) {
        return optionalOf(capability, versionRange, Map.of());
    }

    /**
     * Factory for an optional capability with DCP offer-detail constraints.
     *
     * @param capability           the capability name.
     * @param versionRange         the npm-style version range.
     * @param requiredOfferDetails DCP offer-interface detail subset to match.
     * @return an optional requirement.
     */
    public static CapabilityRequirement optionalOf(
            String capability,
            String versionRange,
            Map<String, Object> requiredOfferDetails
    ) {
        return new CapabilityRequirement(
                capability,
                new CapabilityVersionRange(versionRange),
                false,
                requiredOfferDetails);
    }
}
