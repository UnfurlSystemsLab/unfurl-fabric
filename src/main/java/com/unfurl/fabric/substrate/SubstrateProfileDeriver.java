package com.unfurl.fabric.substrate;

import com.unfurl.dcp.claim.Dependencies;
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

public final class SubstrateProfileDeriver {
    private static final Pattern SECRET_LIKE =
            Pattern.compile("(?i).*(?:_KEY|_TOKEN|_SECRET|PASSWORD|APIKEY|ACCESSKEY).*");

    public SubstrateProfile derive(CompositionCandidate candidate) {
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

    private static PortAccum merge(PortAccum left, PortAccum right) {
        String intersection = VersionRanges.intersect(left.versionRange, right.versionRange);
        if (intersection == null) {
            throw new SubstrateProfileException.ConflictingVersions(
                    left.port, left.versionRange, right.versionRange);
        }
        String provider = left.provider == null ? right.provider : left.provider;
        return new PortAccum(left.port, intersection, provider);
    }

    private static void rejectSecretLike(String raw) {
        if (SECRET_LIKE.matcher(raw).matches()) {
            throw new SubstrateProfileException.MalformedDependency(raw,
                    "substrate profile dependencies must not contain design-time secret names");
        }
    }

    private record PortAccum(String port, String versionRange, String provider) {
        SubstratePortRequirement toRequirement() {
            Map<String, Object> constraints = provider == null ? Map.of() : Map.of("provider", provider);
            return new SubstratePortRequirement(port, port, versionRange, null, true, constraints);
        }
    }

    private static final class VersionRanges {
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

        private static String format(int lowerMajor, int upperMajorExclusive) {
            return ">=" + lowerMajor + ".0.0 <" + upperMajorExclusive + ".0.0";
        }

        private record Bound(String raw, int lowerMajor, int upperMajorExclusive) {
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

        private VersionRanges() {
        }
    }
}
