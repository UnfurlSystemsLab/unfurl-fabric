package com.unfurl.fabric.matcher;

import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.Refusal;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.needs.ArtifactConstraint;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.substrate.api.BindingMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a proposed set of catalog entries against a {@link Need}. Required capabilities,
 * required dependencies, refusal alignment and binding compatibility are <i>gates</i>;
 * a candidate either passes all of them or it fails validation entirely.
 *
 * <p>Refusal alignment semantics (per architectural feedback): a refusal becomes
 * composition-relevant only when (a) the refused concern is in
 * {@link Need#refusalExpectations()}, or (b) it is depended on by another selected component,
 * or (c) the claim itself marks the {@code redirectTo} as required. Otherwise refusals are
 * informational and do not gate validity.
 */
public final class CandidateValidator {

    public CandidateValidity validate(List<CatalogEntry> entries, Need need) {
        if (entries == null) {
            entries = List.of();
        }
        if (need == null) {
            need = Need.ofRequiredCapabilities();
        }

        List<Conflict> conflicts = new ArrayList<>();

        boolean requiredCaps = checkRequiredCapabilities(entries, need, conflicts);
        boolean versions = checkVersionConstraints(entries, need, conflicts);
        boolean deps = checkRequiredDependencies(entries, need, conflicts);
        boolean refusals = checkRefusalAlignment(entries, need, conflicts);
        boolean bindings = checkBindingCompatibility(entries, need, conflicts);

        return new CandidateValidity(requiredCaps, versions, deps, refusals, bindings, conflicts);
    }

    private boolean checkRequiredCapabilities(List<CatalogEntry> entries, Need need, List<Conflict> conflicts) {
        boolean allMet = true;
        for (CapabilityRequirement req : need.requiredCapabilities()) {
            if (!hasMatchingOffer(entries, req)) {
                conflicts.add(new Conflict.VersionConflict(
                        req.capability(),
                        req.capabilityVersion().range(),
                        firstSeenVersion(entries, req.capability())));
                allMet = false;
            }
        }
        return allMet;
    }

    private boolean checkVersionConstraints(List<CatalogEntry> entries, Need need, List<Conflict> conflicts) {
        boolean allMet = true;
        for (ArtifactConstraint constraint : need.artifactConstraints()) {
            boolean matched = false;
            for (CatalogEntry entry : entries) {
                if (constraint.matches(entry.artifact().coordinates())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                conflicts.add(new Conflict.ArtifactConflict(
                        constraint.group(), constraint.name(), constraint.version().range()));
                allMet = false;
            }
        }
        return allMet;
    }

    private boolean checkRequiredDependencies(List<CatalogEntry> entries, Need need, List<Conflict> conflicts) {
        boolean allResolved = true;
        Set<String> providedCapabilities = new HashSet<>();
        for (CatalogEntry e : entries) {
            for (Offer o : e.claimDescriptor().claim().offers()) {
                providedCapabilities.add(o.capability());
            }
        }
        for (CatalogEntry e : entries) {
            if (e.claimDescriptor().claim().dependencies() == null) {
                continue;
            }
            for (String dep : e.claimDescriptor().claim().dependencies().needs()) {
                if (dep == null || dep.isBlank()) {
                    continue;
                }
                // Substrate dependencies are runtime substrate requirements. Fabric derives
                // them into a SubstrateProfile; they are not component-to-component gates.
                if (dep.contains("substrate=true")) {
                    continue;
                }
                String capabilityName = capabilityNameFromDependencyUri(dep);
                if (capabilityName == null || providedCapabilities.contains(capabilityName)) {
                    continue;
                }
                // Customer-owned dependencies are host-bound and resolved outside fabric.
                if (dep.contains("owner=customer-controlled")) {
                    continue;
                }
                conflicts.add(new Conflict.DependencyConflict(dep));
                allResolved = false;
            }
        }
        return allResolved;
    }

    private boolean checkRefusalAlignment(List<CatalogEntry> entries, Need need, List<Conflict> conflicts) {
        Set<String> concernsOwnedBySelectedComponents = new HashSet<>();
        Set<String> requiredConcerns = new LinkedHashSet<>(need.refusalExpectations());
        for (CatalogEntry e : entries) {
            for (Refusal r : e.claimDescriptor().claim().refusals()) {
                // (c) claim marks redirectTo as required => the responsibility must be owned
                // by another selected component. Today DCP's Refusal record has no `required`
                // field; treat all refusals as composition-relevant only when (a) needs ask
                // for them or (b) another selected component depends on them via 'needs'.
                // Concrete signal: if the refusal's concern is mentioned in another component's
                // claim dependencies, treat it as required to fill.
                for (CatalogEntry peer : entries) {
                    if (peer == e || peer.claimDescriptor().claim().dependencies() == null) {
                        continue;
                    }
                    for (String dep : peer.claimDescriptor().claim().dependencies().needs()) {
                        if (dep != null && dep.contains(r.concern())) {
                            requiredConcerns.add(r.concern());
                        }
                    }
                }
            }
            for (Offer o : e.claimDescriptor().claim().offers()) {
                concernsOwnedBySelectedComponents.add(o.capability());
            }
        }
        boolean aligned = true;
        for (String concern : requiredConcerns) {
            if (!concernsOwnedBySelectedComponents.contains(concern)) {
                conflicts.add(new Conflict.RefusalConflict(concern));
                aligned = false;
            }
        }
        return aligned;
    }

    private boolean checkBindingCompatibility(List<CatalogEntry> entries, Need need, List<Conflict> conflicts) {
        boolean compatible = true;
        for (CatalogEntry e : entries) {
            for (Offer o : e.claimDescriptor().claim().offers()) {
                BindingMode preferred = need.bindingPreferences().get(o.capability());
                if (preferred == null) {
                    continue;
                }
                Set<BindingMode> supported = e.metadata().binding().supportedModes();
                if (!supported.contains(preferred)) {
                    conflicts.add(new Conflict.BindingConflict(o.capability(),
                            preferred.name(), supported.toString()));
                    compatible = false;
                }
            }
        }
        return compatible;
    }

    private static boolean hasMatchingOffer(List<CatalogEntry> entries, CapabilityRequirement req) {
        for (CatalogEntry e : entries) {
            for (Offer o : e.claimDescriptor().claim().offers()) {
                if (!o.capability().equals(req.capability())) {
                    continue;
                }
                if (req.capabilityVersion().satisfiedBy(o.version())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String firstSeenVersion(List<CatalogEntry> entries, String capability) {
        for (CatalogEntry e : entries) {
            for (Offer o : e.claimDescriptor().claim().offers()) {
                if (capability.equals(o.capability())) {
                    return o.version();
                }
            }
        }
        return "<none>";
    }

    private static String capabilityNameFromDependencyUri(String dep) {
        // Strip "@" suffix and any "?" query params to extract the capability/scheme name
        String trimmed = dep;
        int q = trimmed.indexOf('?');
        if (q >= 0) {
            trimmed = trimmed.substring(0, q);
        }
        int at = trimmed.indexOf('@');
        if (at >= 0) {
            trimmed = trimmed.substring(0, at);
        }
        return trimmed.isBlank() ? null : trimmed;
    }
}
