package com.unfurl.fabric.trust;

import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.Stability;
import com.unfurl.fabric.catalog.Catalog;
import com.unfurl.fabric.catalog.CatalogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Classifies a catalog into allowed and rejected entries against a {@link TrustPolicy}.
 *
 * <p>Rejections are <i>preserved</i> with reasons rather than silently pruned. Matching layers
 * operate on the allowed half; diagnostic commands surface the rejected half so operators can
 * see why a specific entry was excluded (e.g., "vendor not trusted" rather than "no provider
 * for this capability").
 */
public final class TrustClassifier {

    public TrustClassification classify(Catalog catalog, TrustPolicy policy) {
        if (catalog == null) {
            return new TrustClassification(List.of(), List.of());
        }
        TrustPolicy effective = policy == null ? TrustPolicy.permissive() : policy;

        List<CatalogEntry> allowed = new ArrayList<>();
        List<RejectedEntry> rejected = new ArrayList<>();

        for (CatalogEntry entry : catalog.entries()) {
            List<RejectionReason> reasons = checkEntry(entry, effective);
            if (reasons.isEmpty()) {
                allowed.add(entry);
            } else {
                rejected.add(new RejectedEntry(entry, reasons));
            }
        }

        return new TrustClassification(allowed, rejected);
    }

    private List<RejectionReason> checkEntry(CatalogEntry entry, TrustPolicy policy) {
        List<RejectionReason> reasons = new ArrayList<>();

        if (!policy.trustedVendors().isEmpty()) {
            String publisher = entry.claimDescriptor().claim().identity().publisher();
            if (publisher == null || !policy.trustedVendors().contains(publisher)) {
                reasons.add(new RejectionReason.VendorNotTrusted(
                        publisher == null ? "<missing>" : publisher));
            }
        }

        if (!policy.allowedArtifactGroups().isEmpty()) {
            String group = artifactGroup(entry.artifact().coordinates());
            if (group == null || !policy.allowedArtifactGroups().contains(group)) {
                reasons.add(new RejectionReason.ArtifactGroupNotAllowed(
                        group == null ? "<unparseable>" : group));
            }
        }

        if (!policy.allowedLifecycleStatuses().contains(entry.metadata().lifecycle().status())) {
            reasons.add(new RejectionReason.LifecycleNotAllowed(
                    entry.metadata().lifecycle().status().name()));
        }

        if (policy.requireSignedClaims() && entry.artifact().signature() == null) {
            reasons.add(new RejectionReason.UnsignedClaim());
        }

        for (Offer offer : entry.claimDescriptor().claim().offers()) {
            String capability = offer.capability();
            for (String denied : policy.deniedCapabilityPatterns()) {
                if (globMatch(denied, capability)) {
                    reasons.add(new RejectionReason.CapabilityDenied(capability));
                    break;
                }
            }
            boolean anyAllowed = policy.allowedCapabilityPatterns().isEmpty();
            for (String allowed : policy.allowedCapabilityPatterns()) {
                if (globMatch(allowed, capability)) {
                    anyAllowed = true;
                    break;
                }
            }
            if (!anyAllowed) {
                reasons.add(new RejectionReason.CapabilityNotAllowed(capability));
            }
            if (!stabilityAtOrAbove(offer.stability(), policy.minimumStability())) {
                reasons.add(new RejectionReason.StabilityBelowFloor(
                        offer.stability() == null ? "<missing>" : offer.stability().name(),
                        policy.minimumStability().name()));
            }
        }

        return reasons;
    }

    private static String artifactGroup(String coordinates) {
        if (coordinates == null) {
            return null;
        }
        int idx = coordinates.indexOf(':');
        return idx <= 0 ? null : coordinates.substring(0, idx);
    }

    private static boolean stabilityAtOrAbove(Stability observed, TrustPolicy.Stability floor) {
        if (observed == null) {
            return false;
        }
        int observedRank = stabilityRank(observed);
        int floorRank = floorRank(floor);
        return observedRank >= floorRank;
    }

    private static int stabilityRank(Stability stability) {
        return switch (stability) {
            case DEPRECATED -> -1;
            case EXPERIMENTAL -> 0;
            case EVOLVING -> 1;
            case STABLE -> 2;
        };
    }

    private static int floorRank(TrustPolicy.Stability stability) {
        return switch (stability) {
            case EXPERIMENTAL -> 0;
            case EVOLVING -> 1;
            case STABLE -> 2;
        };
    }

    /**
     * Glob match supporting only {@code *} as a multi-character wildcard. Sufficient for the
     * capability namespaces fabric matches today (e.g. {@code storage.*}).
     */
    private static boolean globMatch(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        if ("*".equals(pattern)) {
            return true;
        }
        String regex = "^" + Pattern.quote(pattern).replace("*", "\\E.*\\Q") + "$";
        return value.matches(regex);
    }
}
