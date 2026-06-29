package com.unfurl.fabric.substrate;

import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.deployment.plan.BindingPlan;
import com.unfurl.deployment.plan.BindingPlanEntry;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.substrate.api.BindingMode;
import com.unfurl.substrate.api.SubstrateClaim;
import com.unfurl.substrate.api.SubstrateMetadata;
import com.unfurl.substrate.api.SubstratePortRequirement;
import com.unfurl.substrate.api.SubstrateProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Derives the closed-world {@link SubstrateProfile} for a chosen {@link CompositionCandidate}: the set of
 * substrate ports the composition requires, the binding modes in play, and the participating claims.
 *
 * <p>Pattern: <b>Builder/Deriver</b> (pure transformation candidate → profile) using an internal
 * <b>accumulator + reduce</b> ({@link PortAccum} merged via {@link #merge}) to fold multiple declarations
 * of the same port into one requirement with an intersected version range. Strictly deterministic and
 * secret-free: dependency strings that look like secret names are rejected so design-time secrets never
 * leak into a profile.
 */
public final class SubstrateProfileDeriver {
    /** Guard pattern: matches dependency tokens that look like secret material and must be rejected. */
    private static final Pattern SECRET_LIKE =
            Pattern.compile("(?i).*(?:_KEY|_TOKEN|_SECRET|PASSWORD|APIKEY|ACCESSKEY).*");

    /**
     * Derive a substrate profile from the candidate alone (no binding plan).
     *
     * @param candidate the chosen composition candidate.
     * @return the derived substrate profile.
     */
    public SubstrateProfile derive(CompositionCandidate candidate) {
        return derive(candidate, null);
    }

    /**
     * Derive a substrate profile from the candidate, optionally augmented with required ports declared by
     * a resolved {@link BindingPlan}.
     *
     * <p>For each entry it records the binding mode and claim, then folds every {@code substrate=true}
     * dependency into the per-port accumulator (rejecting secret-like tokens). Binding-plan required ports
     * are merged in the same way. The result lists port requirements sorted by name for determinism.
     *
     * @param candidate   the chosen composition candidate (required).
     * @param bindingPlan optional resolved binding plan contributing required substrate ports; may be null.
     * @return the derived, deterministic substrate profile.
     * @throws IllegalArgumentException                       if candidate is null.
     * @throws SubstrateProfileException.ConflictingVersions  if a port is required at incompatible ranges.
     * @throws SubstrateProfileException.MalformedDependency  if a dependency carries a secret-like name.
     */
    public SubstrateProfile derive(CompositionCandidate candidate, BindingPlan bindingPlan) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        List<CatalogEntry> entries = candidate.entries().stream()
                .sorted(CatalogEntry.CANONICAL_ORDER)
                .toList();
        Map<String, PortAccum> ports = new LinkedHashMap<>();
        Set<BindingMode> bindingModes = new LinkedHashSet<>();
        List<SubstrateClaim> claims = new ArrayList<>();

        for (CatalogEntry entry : entries) {
            bindingModes.add(entry.metadata().binding().defaultMode());
            claims.add(new SubstrateClaim(entry.claimDescriptor().claim(), entry.claimDescriptor().claimHash()));
            Dependencies dependencies = entry.claimDescriptor().claim().dependencies();
            if (dependencies == null) {
                continue;
            }
            for (String raw : dependencies.needs()) {
                if (raw == null || !raw.contains("substrate=true")) {
                    continue;
                }
                rejectSecretLike(raw);
                SubstrateDependencyUri uri = SubstrateDependencyUri.parse(raw);
                ports.merge(uri.port(), new PortAccum(uri.port(), uri.versionRange(), uri.provider()),
                        SubstrateProfileDeriver::merge);
            }
        }
        if (bindingPlan != null) {
            for (BindingPlanEntry entry : bindingPlan.entries()) {
                for (String port : entry.requiredSubstratePorts()) {
                    rejectSecretLike(port);
                    ports.merge(port, new PortAccum(port, "*", null), SubstrateProfileDeriver::merge);
                }
            }
        }

        List<SubstratePortRequirement> requirements = ports.values().stream()
                .sorted(Comparator.comparing(PortAccum::port))
                .map(PortAccum::toRequirement)
                .toList();
        String sourceArtifact = entries.stream()
                .map(e -> e.artifact().coordinates())
                .collect(Collectors.joining(","));
        return new SubstrateProfile(
                "fabric-composition:" + candidate.candidateId(),
                "1.0.0",
                new SubstrateMetadata(null, sourceArtifact, Map.of(), List.copyOf(bindingModes)),
                requirements,
                claims,
                null);
    }

    /**
     * Reduce function (used by {@link Map#merge}) folding two declarations of the same port into one,
     * intersecting their version ranges and keeping the first non-null provider preference.
     *
     * @param left  the accumulated declaration.
     * @param right the newly seen declaration.
     * @return the merged accumulator.
     * @throws SubstrateProfileException.ConflictingVersions if the ranges have no intersection.
     */
    private static PortAccum merge(PortAccum left, PortAccum right) {
        String intersection = VersionRanges.intersect(left.versionRange, right.versionRange);
        if (intersection == null) {
            throw new SubstrateProfileException.ConflictingVersions(
                    left.port, left.versionRange, right.versionRange);
        }
        String provider = left.provider == null ? right.provider : left.provider;
        return new PortAccum(left.port, intersection, provider);
    }

    /**
     * Reject a token that looks like a design-time secret name, enforcing the no-secrets-in-profile rule.
     *
     * @param raw the dependency/port token to check.
     * @throws SubstrateProfileException.MalformedDependency if the token matches {@link #SECRET_LIKE}.
     */
    private static void rejectSecretLike(String raw) {
        if (SECRET_LIKE.matcher(raw).matches()) {
            throw new SubstrateProfileException.MalformedDependency(raw,
                    "substrate profile dependencies must not contain design-time secret names");
        }
    }

    /**
     * Mutable-free per-port accumulator used during the fold.
     *
     * @param port         the substrate port name.
     * @param versionRange the (possibly intersected) version range so far.
     * @param provider     the preferred provider, or null.
     */
    private record PortAccum(String port, String versionRange, String provider) {
        /**
         * Convert the accumulator into the immutable {@link SubstratePortRequirement} for the profile.
         *
         * @return the port requirement (required=true; provider carried as a constraint when present).
         */
        SubstratePortRequirement toRequirement() {
            Map<String, Object> constraints = provider == null ? Map.of() : Map.of("provider", provider);
            return new SubstratePortRequirement(port, port, versionRange, null, true, constraints);
        }
    }

    /**
     * Small internal utility for intersecting caret/exact/range version expressions on the major axis.
     *
     * <p>Pattern: <b>utility class</b> (private, no instances). Supports {@code *}, {@code ^N},
     * {@code N.N.N}, and {@code >=A.B.C <D.E.F} forms; returns null when the inputs don't intersect or
     * aren't recognized (the caller treats null as a conflict).
     */
    private static final class VersionRanges {
        /**
         * Intersect two version-range expressions on the major axis.
         *
         * @param left  one range expression.
         * @param right the other range expression.
         * @return a range expression covering the intersection, or null if disjoint/unparseable.
         */
        static String intersect(String left, String right) {
            if (left.equals(right)) {
                return left;
            }
            if ("*".equals(left)) {
                return right;
            }
            if ("*".equals(right)) {
                return left;
            }
            Bound a = Bound.parse(left);
            Bound b = Bound.parse(right);
            if (a == null || b == null) {
                return null;
            }
            int lower = Math.max(a.lowerMajor, b.lowerMajor);
            int upper = Math.min(a.upperMajorExclusive, b.upperMajorExclusive);
            if (lower >= upper) {
                return null;
            }
            if (a.lowerMajor == lower && a.upperMajorExclusive == upper) {
                return a.raw;
            }
            if (b.lowerMajor == lower && b.upperMajorExclusive == upper) {
                return b.raw;
            }
            return format(lower, upper);
        }

        /**
         * Format a half-open major-version interval as a {@code >=A.0.0 <B.0.0} range expression.
         *
         * @param lowerMajor          inclusive lower major version.
         * @param upperMajorExclusive exclusive upper major version.
         * @return the formatted range expression.
         */
        private static String format(int lowerMajor, int upperMajorExclusive) {
            return ">=" + lowerMajor + ".0.0 <" + upperMajorExclusive + ".0.0";
        }

        /**
         * A parsed major-version bound: the original text plus the inclusive lower and exclusive upper
         * major versions it covers.
         *
         * @param raw                 the original range expression.
         * @param lowerMajor          inclusive lower major version.
         * @param upperMajorExclusive exclusive upper major version.
         */
        private record Bound(String raw, int lowerMajor, int upperMajorExclusive) {
            /**
             * Parse a supported range expression into a {@link Bound}.
             *
             * @param raw the range expression ({@code ^N}, {@code N.N.N}, or {@code >=A.B.C <D.E.F}).
             * @return the parsed bound, or null if unrecognized.
             */
            static Bound parse(String raw) {
                if (raw.matches("\\^\\d+")) {
                    int major = Integer.parseInt(raw.substring(1));
                    return new Bound(raw, major, major + 1);
                }
                if (raw.matches("\\d+\\.\\d+\\.\\d+")) {
                    int major = Integer.parseInt(raw.substring(0, raw.indexOf('.')));
                    return new Bound(raw, major, major + 1);
                }
                java.util.regex.Matcher matcher = Pattern
                        .compile(">=\\s*(\\d+)\\.\\d+\\.\\d+\\s+<\\s*(\\d+)\\.\\d+\\.\\d+")
                        .matcher(raw);
                if (matcher.matches()) {
                    return new Bound(raw, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
                }
                return null;
            }
        }

        /** Non-instantiable utility holder. */
        private VersionRanges() {
        }
    }
}
