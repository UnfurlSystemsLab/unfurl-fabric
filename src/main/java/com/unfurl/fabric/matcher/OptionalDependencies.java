package com.unfurl.fabric.matcher;

import com.unfurl.dcp.claim.Offer;
import com.unfurl.fabric.catalog.CatalogEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Projector: lists the optional claim dependencies ({@code dependencies.optional}, DCP HLD-C v0.2 §3.4) that a set of
 * selected entries leaves unprovided, so planning can show what each component will run without instead of hiding it.
 *
 * <p>An unprovided optional dependency is informational, never a conflict: {@link CandidateValidator} gates only
 * {@code dependencies.needs} (HLD-unfurl-fabric, Core Flow step 3). Externally owned optional dependencies
 * ({@link CandidateValidator#isExternallyOwnedDependency}) are bound by the host, not by a catalog component, so they
 * are never listed.
 */
public final class OptionalDependencies {

    /**
     * Value Object: one optional dependency of a selected component that no selected component offers.
     *
     * @param component  the consuming component's artifact coordinates.
     * @param dependency the dependency URI exactly as the claim declares it.
     * @param capability the capability the dependency names.
     */
    public record Unprovided(String component, String dependency, String capability) {
        /**
         * Presenter: one plan-output line naming the component, the capability and how to include a provider.
         *
         * @return the line, prefixed {@code optional, not provided: }.
         */
        public String describe() {
            return "optional, not provided: " + component + " can use " + capability
                    + " but runs without it; add " + capability + " to the needs to include a provider";
        }
    }

    private OptionalDependencies() {
    }

    /**
     * Lists the selected entries' optional dependencies that no selected entry offers, in entry then declaration order.
     *
     * @param entries the selected entries (null treated as empty).
     * @return the unprovided optional dependencies; empty when every one is provided or none is declared.
     */
    public static List<Unprovided> unprovided(List<CatalogEntry> entries) {
        if (entries == null) {
            return List.of();
        }
        Set<String> offered = new HashSet<>();
        for (CatalogEntry entry : entries) {
            for (Offer offer : entry.claimDescriptor().claim().offers()) {
                offered.add(offer.capability());
            }
        }
        List<Unprovided> unprovided = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            var dependencies = entry.claimDescriptor().claim().dependencies();
            if (dependencies == null) {
                continue;
            }
            for (String dependency : dependencies.optional()) {
                String capability = CandidateValidator.capabilityNameFromDependencyUri(dependency);
                if (capability == null || offered.contains(capability)
                        || CandidateValidator.isExternallyOwnedDependency(dependency)) {
                    continue;
                }
                unprovided.add(new Unprovided(entry.artifact().coordinates(), dependency, capability));
            }
        }
        return List.copyOf(unprovided);
    }
}
