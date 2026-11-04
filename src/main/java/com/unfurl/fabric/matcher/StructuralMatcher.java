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
 *
 * <p>Pattern: <b>orchestrator/pipeline service</b> composing {@link CandidateValidator} (gate) and
 * {@link Scorer} (rank), both constructor-injected for testability.
 */
public final class StructuralMatcher {

    /** Upper bound on emitted candidates, to keep planning runtimes bounded. */
    private static final int MAX_CANDIDATES = 16;

    /** Validity gate. */
    private final CandidateValidator validator;
    /** Deterministic scorer for valid candidates. */
    private final Scorer scorer;

    /** Production constructor wiring the default validator and scorer. */
    public StructuralMatcher() {
        this(new CandidateValidator(), new Scorer());
    }

    /**
     * Test/seam constructor injecting collaborators.
     *
     * @param validator the validity gate.
     * @param scorer    the deterministic scorer.
     */
    public StructuralMatcher(CandidateValidator validator, Scorer scorer) {
        this.validator = validator;
        this.scorer = scorer;
    }

    /**
     * Match an allowed catalog against a need, producing exactly one of the three {@link MatchResult}s.
     *
     * @param allowedEntries  trust-allowed catalog entries (null → empty).
     * @param need            the operator need (null → no requirements).
     * @param rejectedEntries trust-rejected entries, for NoMatch diagnostics (null → empty).
     * @return the match outcome (ExactMatch / Ambiguous / NoMatch).
     */
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

        List<UnmetCapabilityRequirement> missing =
                findUnmetRequiredCapabilities(need, providersByCapability, allowedEntries, rejectedEntries);
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
            // Every required capability has a provider here (the unmet case returned above), so the cause is in the
            // conflicts; no trust rejection is relevant to an unmet capability.
            return new MatchResult.NoMatch(List.of(), distinct(aggregatedConflicts), List.of());
        }

        candidates.sort(
                Comparator.comparingInt((CompositionCandidate c) -> c.score().finalScore()).reversed()
                        .thenComparing(StructuralMatcher::lexCoordinates));

        if (candidates.size() == 1) {
            return new MatchResult.ExactMatch(candidates.get(0));
        }
        return new MatchResult.Ambiguous(candidates);
    }

    /**
     * Deterministic tiebreak key: the candidate's sorted, comma-joined coordinates.
     *
     * @param candidate the candidate.
     * @return the lexicographic coordinates key.
     */
    private static String lexCoordinates(CompositionCandidate candidate) {
        return candidate.entries().stream()
                .map(e -> e.artifact().coordinates())
                .sorted()
                .collect(Collectors.joining(","));
    }

    /**
     * Index, per required capability, the allowed entries that provide it at a satisfying version.
     *
     * @param entries the allowed entries.
     * @param need    the need.
     * @return capability → list of providing entries (insertion-ordered).
     */
    private static Map<String, List<CatalogEntry>> indexProvidersByCapability(
            List<CatalogEntry> entries, Need need) {
        Map<String, List<CatalogEntry>> index = new LinkedHashMap<>();
        for (CapabilityRequirement req : need.requiredCapabilities()) {
            List<CatalogEntry> providers = new ArrayList<>();
            for (CatalogEntry entry : entries) {
                for (Offer offer : entry.claimDescriptor().claim().offers()) {
                    if (CapabilityOfferMatcher.matches(offer, req)) {
                        providers.add(entry);
                        break;
                    }
                }
            }
            index.put(req.capability(), providers);
        }
        return index;
    }

    /**
     * Conflicts de-duplicated by detail, in first-seen order: every candidate shape repeats the conflicts of the
     * components it shares with other shapes, and the operator needs each cause once.
     *
     * @param conflicts the aggregated conflicts.
     * @return the distinct conflicts.
     */
    private static List<Conflict> distinct(List<Conflict> conflicts) {
        Map<String, Conflict> byDetail = new LinkedHashMap<>();
        conflicts.forEach(conflict -> byDetail.putIfAbsent(conflict.detail(), conflict));
        return List.copyOf(byDetail.values());
    }

    /**
     * Required capabilities with zero providers in the index, each with the most specific reason and a message
     * naming the fix. Every capability the needs require is mandatory, so each of these fails the match.
     *
     * @param need     the need.
     * @param providers the capability→providers index.
     * @param allowed  the trust-allowed entries, to tell a missing capability from an unmatched version or details.
     * @param rejected the trust-rejected entries, to report a capability that only rejected entries offer.
     * @return the unmet requirements (empty if all covered).
     */
    private static List<UnmetCapabilityRequirement> findUnmetRequiredCapabilities(
            Need need, Map<String, List<CatalogEntry>> providers,
            List<CatalogEntry> allowed, List<RejectedEntry> rejected) {
        List<UnmetCapabilityRequirement> unmet = new ArrayList<>();
        for (CapabilityRequirement req : need.requiredCapabilities()) {
            if (!providers.getOrDefault(req.capability(), List.of()).isEmpty()) {
                continue;
            }
            String capability = req.capability();
            String range = req.capabilityVersion().range();
            String required = "the needs require " + capability + " " + range;
            List<String> versionMatches = offersOf(allowed, capability, offer -> CapabilityOfferMatcher.matchesNameAndVersion(offer, req));
            List<String> anyVersion = offersOf(allowed, capability, offer -> true);
            List<String> rejectedOffers = rejected.stream()
                    .filter(r -> offersCapability(r.entry(), capability))
                    .map(r -> r.entry().artifact().coordinates() + " ("
                            + r.reasons().stream().map(reason -> reason.detail()).collect(Collectors.joining("; ")) + ")")
                    .toList();
            String rejectedNote = rejectedOffers.isEmpty() ? ""
                    : "; the trust policy rejected " + String.join(", ", rejectedOffers);
            UnmetCapabilityRequirement.Reason reason;
            String detail;
            if (!versionMatches.isEmpty()) {
                reason = UnmetCapabilityRequirement.Reason.OFFER_DETAILS_UNSATISFIED;
                detail = required + " with offer details " + req.requiredOfferDetails() + ", but "
                        + String.join(", ", versionMatches) + " does not offer those details" + rejectedNote;
            } else if (!anyVersion.isEmpty()) {
                reason = UnmetCapabilityRequirement.Reason.VERSION_NOT_OFFERED;
                detail = required + ", but the catalog offers only " + String.join(", ", anyVersion)
                        + "; change the version range in the needs, or add a component that offers a matching version"
                        + rejectedNote;
            } else if (!rejectedOffers.isEmpty()) {
                reason = UnmetCapabilityRequirement.Reason.REJECTED_BY_TRUST_POLICY;
                detail = required + ", but only components the trust policy rejected offer it: "
                        + String.join(", ", rejectedOffers) + "; change the trust policy, or add an allowed component";
            } else {
                reason = UnmetCapabilityRequirement.Reason.NOT_IN_CATALOG;
                detail = required + ", but no catalog entry offers " + capability
                        + "; add a component that offers it to the catalog, or remove it from the needs";
            }
            unmet.add(new UnmetCapabilityRequirement(capability, range, reason, detail));
        }
        return unmet;
    }

    /**
     * Allowed offers of a capability that pass a filter, rendered as {@code capability version (coordinates)}.
     *
     * @param entries    the entries to search.
     * @param capability the capability name.
     * @param filter     which offers to include.
     * @return the rendered offers, in catalog order.
     */
    private static List<String> offersOf(List<CatalogEntry> entries, String capability,
                                         java.util.function.Predicate<Offer> filter) {
        List<String> offers = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            for (Offer offer : entry.claimDescriptor().claim().offers()) {
                if (capability.equals(offer.capability()) && filter.test(offer)) {
                    offers.add(capability + " " + offer.version() + " (" + entry.artifact().coordinates() + ")");
                }
            }
        }
        return offers;
    }

    /**
     * Whether an entry offers a capability by name, at any version.
     *
     * @param entry      the entry.
     * @param capability the capability name.
     * @return true when one of its offers has that capability.
     */
    private static boolean offersCapability(CatalogEntry entry, String capability) {
        return entry.claimDescriptor().claim().offers().stream().anyMatch(offer -> capability.equals(offer.capability()));
    }

    /**
     * Generate distinct candidate compositions as the Cartesian product of one provider per required
     * capability, de-duplicated by coordinates set and ordered smallest-first (minimal covers favored),
     * with a bound on the number generated.
     *
     * @param need      the need.
     * @param providers the capability→providers index.
     * @return candidate entry shapes, smallest/lexicographically first.
     */
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

    /**
     * Filter trust-rejected entries to those that offer one of the missing capabilities, so NoMatch
     * diagnostics can explain "present but rejected" rather than just "not found".
     *
     * @param missing    the unmet capabilities.
     * @param rejections the trust-rejected entries.
     * @return the relevant subset of rejections.
     */
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

    /**
     * The required capabilities satisfied by a given entry shape.
     *
     * @param entries the entry shape.
     * @param need    the need.
     * @return the satisfied required capability names.
     */
    private static Set<String> satisfiedRequiredCaps(List<CatalogEntry> entries, Need need) {
        Set<String> satisfied = new LinkedHashSet<>();
        for (CapabilityRequirement req : need.requiredCapabilities()) {
            for (CatalogEntry e : entries) {
                for (Offer o : e.claimDescriptor().claim().offers()) {
                    if (CapabilityOfferMatcher.matches(o, req)) {
                        satisfied.add(req.capability());
                    }
                }
            }
        }
        return satisfied;
    }

    /**
     * The optional capabilities satisfied by a given entry shape.
     *
     * @param entries the entry shape.
     * @param need    the need.
     * @return the satisfied optional capability names.
     */
    private static Set<String> satisfiedOptionalCaps(List<CatalogEntry> entries, Need need) {
        Set<String> satisfied = new LinkedHashSet<>();
        for (CapabilityRequirement req : need.optionalCapabilities()) {
            for (CatalogEntry e : entries) {
                for (Offer o : e.claimDescriptor().claim().offers()) {
                    if (CapabilityOfferMatcher.matches(o, req)) {
                        satisfied.add(req.capability());
                    }
                }
            }
        }
        return satisfied;
    }

    /**
     * Derive non-fatal planning warnings for a shape: missing optional capabilities and any selected
     * DEPRECATED components.
     *
     * @param entries the entry shape.
     * @param need    the need.
     * @return the planning warnings.
     */
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
