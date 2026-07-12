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
 *
 * <p>Pattern: stateless <b>specification/validator service</b> — each {@code check*} method evaluates one
 * gate and appends conflicts; the result aggregates the gate booleans + conflicts.
 */
public final class CandidateValidator {

    /**
     * Run all gates over the proposed entries.
     *
     * @param entries the proposed composition entries (null treated as empty).
     * @param need    the operator need (null treated as no requirements).
     * @return the validity result with per-gate booleans and conflicts.
     */
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

    /**
     * Gate: every required capability has a satisfying offer in the entry set.
     *
     * @param entries   the entries.
     * @param need      the need.
     * @param conflicts sink for {@link Conflict.VersionConflict}s.
     * @return true iff all required capabilities are satisfied.
     */
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

    /**
     * Gate: every operator artifact constraint matches some selected entry.
     *
     * @param entries   the entries.
     * @param need      the need.
     * @param conflicts sink for {@link Conflict.ArtifactConflict}s.
     * @return true iff all artifact constraints are satisfied.
     */
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

    /**
     * Gate: every required component-to-component dependency resolves within the selected set.
     * Substrate ({@code substrate=true}) and customer-controlled dependencies are excluded — they are
     * not component-to-component gates (substrate is derived into a profile; customer deps are host-bound).
     *
     * @param entries   the entries.
     * @param need      the need (unused directly; kept for signature symmetry/extension).
     * @param conflicts sink for {@link Conflict.DependencyConflict}s.
     * @return true iff all required dependencies resolve.
     */
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
                // Externally owned dependencies are runtime/profile bindings
                // and are resolved outside Fabric's peer catalog graph.
                if (isExternallyOwnedDependency(dep)) {
                    continue;
                }
                conflicts.add(new Conflict.DependencyConflict(dep));
                allResolved = false;
            }
        }
        return allResolved;
    }

    /**
     * Gate: required concerns (from needs, or implied by peer dependencies on a refused concern) are
     * owned by some selected component.
     *
     * @param entries   the entries.
     * @param need      the need (source of explicit refusal expectations).
     * @param conflicts sink for {@link Conflict.RefusalConflict}s.
     * @return true iff all required concerns are owned.
     */
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

    /**
     * Gate: each capability with a binding preference is offered by a provider supporting that mode.
     *
     * @param entries   the entries.
     * @param need      the need (source of binding preferences).
     * @param conflicts sink for {@link Conflict.BindingConflict}s.
     * @return true iff all binding preferences are compatible.
     */
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

    /**
     * Whether any entry offers the required capability at a satisfying version.
     *
     * @param entries the entries.
     * @param req     the capability requirement.
     * @return true iff a matching offer exists.
     */
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

    /**
     * The first observed version of a capability across entries (for conflict messages).
     *
     * @param entries    the entries.
     * @param capability the capability name.
     * @return the first seen version, or {@code <none>}.
     */
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

    /**
     * Extract the capability/scheme name from a dependency URI by stripping the {@code @version} suffix
     * and any {@code ?query} parameters.
     *
     * @param dep the dependency URI string.
     * @return the capability name, or null if blank.
     */
    public static String capabilityNameFromDependencyUri(String dep) {
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

    /**
     * Strategy helper: identifies runtime/profile dependencies whose owner marker places resolution
     * outside the peer catalog graph while preserving them in the claim for deployment binding.
     *
     * @param dep the raw DCP dependency URI.
     * @return true when Fabric should not require a selected catalog entry to offer the dependency.
     */
    public static boolean isExternallyOwnedDependency(String dep) {
        String owner = ownerMarker(dep);
        return "customer-controlled".equals(owner)
                || "host".equals(owner)
                || "fabric".equals(owner)
                || owner.startsWith("customer-");
    }

    /**
     * Parser helper: extracts the dependency {@code owner=} query marker so Fabric can separate
     * peer catalog gates from runtime/profile bindings without hard-coding individual customers.
     *
     * @param dep the raw DCP dependency URI.
     * @return the owner marker, or an empty string when absent.
     */
    private static String ownerMarker(String dep) {
        if (dep == null || dep.isBlank()) {
            return "";
        }
        int q = dep.indexOf('?');
        if (q < 0 || q == dep.length() - 1) {
            return "";
        }
        String[] parts = dep.substring(q + 1).split("&");
        for (String part : parts) {
            if (part.startsWith("owner=")) {
                return part.substring("owner=".length()).trim();
            }
        }
        return "";
    }
}
