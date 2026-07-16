package com.unfurl.fabric.matcher;

import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.resolver.OfferDetailMatcher;
import com.unfurl.fabric.needs.CapabilityRequirement;

/**
 * Strategy: applies Fabric's capability requirement to a DCP offer using the same
 * offer-detail subset matcher as the DCP resolver.
 */
final class CapabilityOfferMatcher {
    /** Shared deterministic matcher for {@code OfferInterface.details}. */
    private static final OfferDetailMatcher DETAIL_MATCHER = new OfferDetailMatcher();

    /**
     * Full matcher: checks capability name, version range, and required DCP offer details.
     *
     * @param offer       provider offer.
     * @param requirement capability requirement from a need.
     * @return true when the offer satisfies the requirement.
     */
    static boolean matches(Offer offer, CapabilityRequirement requirement) {
        return matchesNameAndVersion(offer, requirement)
                && DETAIL_MATCHER.matches(offer, requirement.requiredOfferDetails());
    }

    /**
     * Partial matcher: checks capability name and version range before offer-detail constraints.
     *
     * @param offer       provider offer.
     * @param requirement capability requirement from a need.
     * @return true when name and version match.
     */
    static boolean matchesNameAndVersion(Offer offer, CapabilityRequirement requirement) {
        return offer != null
                && requirement != null
                && offer.capability().equals(requirement.capability())
                && requirement.capabilityVersion().satisfiedBy(offer.version());
    }

    /**
     * Detail matcher: checks only the required DCP offer-detail subset.
     *
     * @param offer       provider offer.
     * @param requirement capability requirement from a need.
     * @return true when required details are empty or satisfied by the offer.
     */
    static boolean detailsSatisfied(Offer offer, CapabilityRequirement requirement) {
        return requirement == null || DETAIL_MATCHER.matches(offer, requirement.requiredOfferDetails());
    }

    /** Utility class: prevent construction. */
    private CapabilityOfferMatcher() {
    }
}
