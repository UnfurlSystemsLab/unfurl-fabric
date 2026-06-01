package com.unfurl.fabric.matcher;

import com.unfurl.dcp.claim.Offer;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.trust.RejectedEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic DCP structural matcher. Walks the allowed catalog, generates valid
 * compositions for the given Need, scores them, and emits a {@link MatchResult}.
 *
 * <p>For the deterministic MVP, the matcher uses a greedy minimal-cover strategy:
 * <ol>
 *   <li>For every required capability, collect candidate offering entries from the allowed
 *       catalog.</li>
 *   <li>If any required capability has no candidates, return {@link MatchResult.NoMatch}
 *       (surfacing trust-policy rejections that would have provided it).</li>
 *   <li>Else generate compositions as the set of distinct minimal covers — sets that satisfy
 *       all required caps with as few entries as possible. The matcher emits up to a small
 *       cap of candidates per request to keep runtimes bounded; ties broken deterministically.</li>
 *   <li>Each candidate is validated by {@link CandidateValidator}; only valid ones reach the
 *       result. Scoring runs over valid candidates only.</li>
 *   <li>One valid candidate → {@link MatchResult.ExactMatch}; many → {@link MatchResult.Ambiguous}
 *       sorted by finalScore DESC, tiebreak lexicographic JAR coordinates.</li>
 * </ol>
 *
 * <p>AI advisor (Phase F) re-ranks Ambiguous results and suggests substitutes for NoMatch,
 * but never alters validity or the deterministic score math.
 */
public final class StructuralMatcher {

    private static final int MAX_CANDIDATES = 16;

    private final CandidateValidator validator;
    private final Scorer scorer;

    public StructuralMatcher() {
        this(new CandidateValidator(), new Scorer());
    }

    public StructuralMatcher(CandidateValidator validator, Scorer scorer) {
        this.validator = validator;
        this.scorer = scorer;
    }

    public MatchResult match(List<CatalogEntry> allowedEntries, Need need, List<RejectedEntry> rejectedEntries) {
        if (allowedEntries == null) {
            allowedEntries = List.of();
        }
        if (need == null) {
            need = Need.ofRequiredCapabilities();
        }
        if (rejectedEntries == null) {
            rejectedEntries = List.of();
        }

        Map<String, List<CatalogEntry>> providersByCapability =
                indexProvidersByCapability(allowedEntries, need);

        List<UnmetCapabilityRequirement> missing = findUnmetRequiredCapabilities(need, providersByCapability);
        if (!missing.isEmpty()) {
            List<RejectedEntry> relevant = filterRejectionsRelevantTo(missing, rejectedEntries);
            return new MatchResult.NoMatch(missing, List.of(), relevant);
        }

        List<List<CatalogEntry>> compositionShapes = generateMinimalCovers(need, providersByCapability);

        List<CompositionCandidate> candidates = new ArrayList<>();
        List<Conflict> aggregatedConflicts = new ArrayList<>();

        for (List<CatalogEntry> shape : compositionShapes) {
            CandidateValidity validity = validator.validate(shape, need);
            if (!validity.isValid()) {
                aggregatedConflicts.addAll(validity.conflicts());
                continue;
            }
            CandidateScore score = scorer.score(shape, need);
            candidates.add(new CompositionCandidate(
                    CompositionCandidate.computeId(shape),
                    shape,
                    satisfiedRequiredCaps(shape, need),
                    satisfiedOptionalCaps(shape, need),
                    List.of(),
                    derivePlanningWarnings(shape, need),
                    score));
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
        }

        if (candidates.isEmpty()) {
            List<RejectedEntry> relevant = filterRejectionsRelevantTo(
                    findUnmetRequiredCapabilities(need, providersByCapability),
                    rejectedEntries);
            return new MatchResult.NoMatch(List.of(), aggregatedConflicts, relevant);
        }

        candidates.sort(
                Comparator.comparingInt((CompositionCandidate c) -> c.score().finalScore()).reversed()
                        .thenComparing(StructuralMatcher::lexCoordinates));

        if (candidates.size() == 1) {
            return new MatchResult.ExactMatch(candidates.get(0));
        }
        return new MatchResult.Ambiguous(candidates);
    }

    private static String lexCoordinates(CompositionCandidate candidate) {
        return candidate.entries().stream()
                .map(e -> e.artifact().coordinates())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static Map<String, List<CatalogEntry>> indexProvidersByCapability(
            List<CatalogEntry> entries, Need need) {
        Map<String, List<CatalogEntry>> index = new LinkedHashMap<>();
        for (CapabilityRequirement req : need.requiredCapabilities()) {
            List<CatalogEntry> providers = new ArrayList<>();
            for (CatalogEntry entry : entries) {
                for (Offer offer : entry.claimDescriptor().claim().offers()) {
                    if (req.capability().equals(offer.capability())
                            && req.capabilityVersion().satisfiedBy(offer.version())) {
                        providers.add(entry);
                        break;
                    }
                }
            }
            index.put(req.capability(), providers);
        }
        return index;
    }

    private static List<UnmetCapabilityRequirement> findUnmetRequiredCapabilities(
            Need need, Map<String, List<CatalogEntry>> providers) {
        List<UnmetCapabilityRequirement> unmet = new ArrayList<>();
        for (CapabilityRequirement req : need.requiredCapabilities()) {
            if (providers.getOrDefault(req.capability(), List.of()).isEmpty()) {
                unmet.add(new UnmetCapabilityRequirement(
                        req.capability(), req.capabilityVersion().range(),
                        "no allowed catalog entry provides " + req.capability()
                                + " matching " + req.capabilityVersion().range()));
            }
        }
        return unmet;
    }

    private static List<List<CatalogEntry>> generateMinimalCovers(
            Need need, Map<String, List<CatalogEntry>> providers) {
        // Greedy: choose one provider per required cap, generating each Cartesian product
        // entry. Dedup by entry set so a single provider covering multiple caps is counted once.
        List<List<CatalogEntry>> shapes = new ArrayList<>();
        Set<Set<String>> seen = new HashSet<>();
        List<List<CatalogEntry>> cartesian = new ArrayList<>();
        cartesian.add(new ArrayList<>());
        for (CapabilityRequirement req : need.requiredCapabilities()) {
            List<CatalogEntry> options = providers.getOrDefault(req.capability(), List.of());
            List<List<CatalogEntry>> next = new ArrayList<>();
            for (List<CatalogEntry> partial : cartesian) {
                for (CatalogEntry option : options) {
                    if (partial.contains(option)) {
                        next.add(partial);
                        continue;
                    }
                    List<CatalogEntry> extended = new ArrayList<>(partial);
                    extended.add(option);
                    next.add(extended);
                }
            }
            cartesian = next;
        }
        // Sort each composition by canonical entry order, then deduplicate by coordinates set.
        for (List<CatalogEntry> combination : cartesian) {
            combination.sort(CatalogEntry.CANONICAL_ORDER);
            Set<String> coords = combination.stream()
                    .map(e -> e.artifact().coordinates())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (seen.add(coords)) {
                shapes.add(combination);
            }
            if (shapes.size() >= MAX_CANDIDATES * 4) {
                break;
            }
        }
        // Smaller compositions first to favor minimal covers; ties broken by lex coordinates.
        shapes.sort(
                Comparator.comparingInt((List<CatalogEntry> shape) -> shape.size())
                        .thenComparing(shape -> shape.stream()
                                .map(e -> e.artifact().coordinates())
                                .collect(Collectors.joining(","))));
        return shapes;
    }

    private static List<RejectedEntry> filterRejectionsRelevantTo(
            List<UnmetCapabilityRequirement> missing, List<RejectedEntry> rejections) {
        if (missing.isEmpty() || rejections.isEmpty()) {
            return List.of();
        }
        Set<String> missingCapabilities = missing.stream()
                .map(UnmetCapabilityRequirement::capability)
                .collect(Collectors.toSet());
        List<RejectedEntry> relevant = new ArrayList<>();
        for (RejectedEntry r : rejections) {
            for (Offer o : r.entry().claimDescriptor().claim().offers()) {
                if (missingCapabilities.contains(o.capability())) {
                    relevant.add(r);
                    break;
                }
            }
        }
        return relevant;
    }

    private static Set<String> satisfiedRequiredCaps(List<CatalogEntry> entries, Need need) {
        Set<String> satisfied = new LinkedHashSet<>();
        for (CapabilityRequirement req : need.requiredCapabilities()) {
            for (CatalogEntry e : entries) {
                for (Offer o : e.claimDescriptor().claim().offers()) {
                    if (req.capability().equals(o.capability())
                            && req.capabilityVersion().satisfiedBy(o.version())) {
                        satisfied.add(req.capability());
                    }
                }
            }
        }
        return satisfied;
    }

    private static Set<String> satisfiedOptionalCaps(List<CatalogEntry> entries, Need need) {
        Set<String> satisfied = new LinkedHashSet<>();
        for (CapabilityRequirement req : need.optionalCapabilities()) {
            for (CatalogEntry e : entries) {
                for (Offer o : e.claimDescriptor().claim().offers()) {
                    if (req.capability().equals(o.capability())
                            && req.capabilityVersion().satisfiedBy(o.version())) {
                        satisfied.add(req.capability());
                    }
                }
            }
        }
        return satisfied;
    }

    private static List<PlanningWarning> derivePlanningWarnings(List<CatalogEntry> entries, Need need) {
        List<PlanningWarning> warnings = new ArrayList<>();
        Set<String> satisfiedOptional = satisfiedOptionalCaps(entries, need);
        for (CapabilityRequirement req : need.optionalCapabilities()) {
            if (!satisfiedOptional.contains(req.capability())) {
                warnings.add(new PlanningWarning.OptionalCapabilityMissing(req.capability()));
            }
        }
        for (CatalogEntry e : entries) {
            if (e.metadata().lifecycle().status()
                    == com.unfurl.fabric.catalog.LifecycleStatus.DEPRECATED) {
                warnings.add(new PlanningWarning.DeprecatedComponentSelected(
                        e.artifact().coordinates()));
            }
        }
        return warnings;
    }
}
