package com.unfurl.fabric.compile;

import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.contract.Binding;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.CreatedBy;
import com.unfurl.dcp.contract.DataMapping;
import com.unfurl.dcp.contract.Expectations;
import com.unfurl.dcp.contract.Invalidation;
import com.unfurl.dcp.contract.NegotiationMode;
import com.unfurl.dcp.contract.Parties;
import com.unfurl.dcp.contract.Party;
import com.unfurl.dcp.contract.Provenance;
import com.unfurl.dcp.contract.RuntimeViolationPolicy;
import com.unfurl.dcp.contract.Transport;
import com.unfurl.dcp.contract.TransportKind;
import com.unfurl.dcp.contract.Trust;
import com.unfurl.dcp.trust.TrustTier;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.ContractCompileException;
import com.unfurl.fabric.compiler.DecisionAudit;
import com.unfurl.fabric.compiler.SelectionRecord;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.matcher.CandidateScore;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.PlanningWarning;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.substrate.api.BindingMode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deterministically compiles a selected {@link CompositionCandidate} (plus the consumer {@link Need}
 * and {@link HostOwnerMeta}) into an immutable DCP {@link CompositionContract} wrapped in a
 * {@link CompiledContract} (contract + selection records + decision audit).
 *
 * <p>Pattern: <b>Builder/Assembler</b> over the DCP contract schema — it assembles the many small DCP
 * value records (Parties, Binding, Transport, Provenance, Trust, Invalidation) into one contract. It is
 * a pure, stateless translation: the only collaborator is an injected {@link Clock} (so {@code compiledAt}
 * and content-addressed ids are reproducible in tests). No models, no I/O, no reasoning — selection
 * intelligence happened upstream in the matcher; this layer only freezes the chosen result.
 *
 * <p>The compiled contract id is <b>content-addressed</b> (SHA-256 over the selected artifacts, the
 * primary binding, and the compile timestamp), so identical inputs at the same instant yield the same id.
 */
public final class ContractCompiler {
    /** Time source for {@code compiledAt} and the content-addressed contract id; injectable for tests. */
    private final Clock clock;

    /** Production constructor: uses the system UTC clock. */
    public ContractCompiler() {
        this(Clock.systemUTC());
    }

    /**
     * Test/seam constructor: injects the clock used for the compile timestamp and id seed.
     *
     * @param clock time source; falls back to {@link Clock#systemUTC()} when null.
     */
    public ContractCompiler(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Compile a selected candidate into a signed-shape {@link CompiledContract}.
     *
     * <p>Steps: validate inputs, deterministically sort the selected entries, derive one
     * {@link SelectionRecord} per entry, choose the single {@link PrimaryBinding} (consumer need →
     * provider offer), build the decision audit, then assemble the immutable {@link CompositionContract}
     * with a content-addressed id, NEUTRAL trust, and HARD_FAIL runtime-violation policy.
     *
     * @param candidate     the chosen composition (must contain at least one selected entry).
     * @param need          the consumer need; defaults to "no required capabilities" when null.
     * @param hostOwnerMeta consumer identity/provenance metadata; defaults applied when null.
     * @return the compiled contract plus its selection records and decision audit.
     * @throws ContractCompileException if the candidate is null/empty or has no offer to bind.
     */
    public CompiledContract compile(CompositionCandidate candidate, Need need, HostOwnerMeta hostOwnerMeta) {
        if (candidate == null) {
            throw new ContractCompileException("candidate is required");
        }
        if (candidate.entries().isEmpty()) {
            throw new ContractCompileException("candidate must contain at least one selected entry");
        }
        need = need == null ? Need.ofRequiredCapabilities() : need;
        hostOwnerMeta = hostOwnerMeta == null ? new HostOwnerMeta(null, null, null) : hostOwnerMeta;

        List<CatalogEntry> entries = sortedEntries(candidate.entries());
        List<SelectionRecord> selections = entries.stream()
                .map(this::selectionFor)
                .toList();
        PrimaryBinding primary = primaryBinding(entries, need);
        Instant compiledAt = clock.instant();
        DecisionAudit audit = auditFor(candidate, need, entries, compiledAt);
        CompositionContract contract = new CompositionContract(
                contractId(selections, audit, primary),
                "0.1.0",
                new Parties(
                        new Party(hostOwnerMeta.consumerClaimUri(), hostOwnerMeta.consumerClaimVersion()),
                        new Party(primary.entry().claimDescriptor().claim().identity().uri(),
                                primary.entry().claimDescriptor().claim().identity().version())),
                new Binding(primary.consumerNeed(), primary.offer().capability(), primary.offer().version()),
                new DataMapping(Map.of(), Map.of()),
                new Transport(transportKind(primary.bindingMode()), Map.of("bindingMode", primary.bindingMode().name())),
                new Expectations(null, true, false, false),
                new Provenance(CreatedBy.FABRIC, NegotiationMode.H2H, null,
                        hostOwnerMeta.fabricVersion(), false, compiledAt),
                new Trust(TrustTier.NEUTRAL),
                new Invalidation(List.of(), RuntimeViolationPolicy.HARD_FAIL));
        return new CompiledContract(contract, selections, audit, null, null);
    }

    /**
     * Build the {@link SelectionRecord} for one catalog entry: its artifact, claim hash, binding mode,
     * and the wire interface kind. The interface kind is taken from the claim's first declared offer
     * interface, falling back to HTTP_API for remote bindings and IN_PROCESS otherwise.
     *
     * @param entry the selected catalog entry.
     * @return the immutable selection record describing how this entry is bound.
     */
    private SelectionRecord selectionFor(CatalogEntry entry) {
        BindingMode bindingMode = entry.metadata().binding().defaultMode();
        return new SelectionRecord(
                entry.artifact(),
                entry.claimDescriptor().claimHash(),
                bindingMode,
                entry.claimDescriptor().claim().offers().stream()
                        .map(Offer::offerInterface)
                        .filter(i -> i != null && i.interfaceKind() != null)
                        .map(i -> i.interfaceKind())
                        .findFirst()
                        .orElse(switch (bindingMode) {
                            case REMOTE_HTTP -> com.unfurl.dcp.claim.InterfaceKind.HTTP_API;
                            default -> com.unfurl.dcp.claim.InterfaceKind.IN_PROCESS;
                        }));
    }

    /**
     * Choose the single contract binding (the DCP {@link Binding} is singular). Scans required
     * capabilities in order and returns the first entry/offer whose capability matches and whose version
     * satisfies the requirement. If no required capability matches, falls back to the first offer of the
     * first (sorted) entry.
     *
     * @param entries selected entries in deterministic order.
     * @param need    the consumer need carrying required capabilities.
     * @return the chosen primary binding.
     * @throws ContractCompileException if the fallback entry exposes no offer to bind.
     */
    private static PrimaryBinding primaryBinding(List<CatalogEntry> entries, Need need) {
        List<CapabilityRequirement> required = need.requiredCapabilities();
        for (CapabilityRequirement requirement : required) {
            for (CatalogEntry entry : entries) {
                for (Offer offer : entry.claimDescriptor().claim().offers()) {
                    if (requirement.capability().equals(offer.capability())
                            && requirement.capabilityVersion().satisfiedBy(offer.version())) {
                        return new PrimaryBinding(entry, offer, requirement.capability(), entry.metadata().binding().defaultMode());
                    }
                }
            }
        }
        CatalogEntry first = entries.get(0);
        Offer offer = first.claimDescriptor().claim().offers().isEmpty()
                ? null
                : first.claimDescriptor().claim().offers().get(0);
        if (offer == null) {
            throw new ContractCompileException("selected entry has no offer to bind");
        }
        return new PrimaryBinding(first, offer, offer.capability(), first.metadata().binding().defaultMode());
    }

    /**
     * Assemble the human-readable {@link DecisionAudit}: the alternative artifacts considered, a reason
     * line per selected entry, the satisfied required/optional capabilities, the numeric score breakdown,
     * and any planning warnings. This is the "show your work" record attached to every compiled contract.
     *
     * @param candidate  the chosen candidate (source of satisfied capabilities, score, warnings).
     * @param need       the consumer need (unused directly but kept for audit-shape stability/extension).
     * @param entries    the selected entries in deterministic order.
     * @param compiledAt the compile timestamp.
     * @return the decision audit.
     */
    private static DecisionAudit auditFor(
            CompositionCandidate candidate,
            Need need,
            List<CatalogEntry> entries,
            Instant compiledAt) {
        List<String> alternatives = entries.stream()
                .map(e -> e.artifact().coordinates())
                .toList();
        List<String> reasons = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            reasons.add("selected " + entry.artifact().coordinates()
                    + " for " + offeredCapabilities(entry));
        }
        reasons.add("satisfied required capabilities " + candidate.satisfiedRequiredCapabilities());
        if (!candidate.satisfiedOptionalCapabilities().isEmpty()) {
            reasons.add("satisfied optional capabilities " + candidate.satisfiedOptionalCapabilities());
        }
        Map<String, Integer> scoreBreakdown = scoreBreakdown(candidate.score());
        List<String> warnings = candidate.warnings().stream()
                .map(PlanningWarning::detail)
                .toList();
        return new DecisionAudit(compiledAt, alternatives, reasons, scoreBreakdown, warnings);
    }

    /**
     * Render an entry's offered capability names as a stable, comma-joined, sorted string for the audit.
     *
     * @param entry the catalog entry.
     * @return sorted comma-separated capability names.
     */
    private static String offeredCapabilities(CatalogEntry entry) {
        return entry.claimDescriptor().claim().offers().stream()
                .map(Offer::capability)
                .sorted()
                .collect(Collectors.joining(","));
    }

    /**
     * Flatten a {@link CandidateScore} into an ordered name→value map for the decision audit, so the
     * scoring rationale is serializable and stable across runs.
     *
     * @param score the candidate score.
     * @return an insertion-ordered map of each score component.
     */
    private static Map<String, Integer> scoreBreakdown(CandidateScore score) {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("dependencyResolutionScore", score.dependencyResolutionScore());
        out.put("finalScore", score.finalScore());
        out.put("footprintPenalty", score.footprintPenalty());
        out.put("lifecycleScore", score.lifecycleScore());
        out.put("optionalCapabilityScore", score.optionalCapabilityScore());
        out.put("riskFlagPenalty", score.riskFlagPenalty());
        out.put("stabilityScore", score.stabilityScore());
        out.put("trustScore", score.trustScore());
        out.put("versionPreferenceScore", score.versionPreferenceScore());
        return out;
    }

    /**
     * Return the entries in a deterministic order (by artifact coordinates, then SHA-256) so selection
     * records, the audit, and the content-addressed contract id are reproducible.
     *
     * @param entries the unsorted selected entries.
     * @return an immutable, deterministically sorted copy.
     */
    private static List<CatalogEntry> sortedEntries(List<CatalogEntry> entries) {
        List<CatalogEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing((CatalogEntry e) -> e.artifact().coordinates())
                .thenComparing(e -> e.artifact().sha256()));
        return List.copyOf(sorted);
    }

    /**
     * Map a substrate {@link BindingMode} to the DCP {@link TransportKind} recorded on the contract.
     *
     * @param bindingMode the entry's default binding mode.
     * @return HTTP_JSON for remote bindings, IN_PROCESS otherwise.
     */
    private static TransportKind transportKind(BindingMode bindingMode) {
        return switch (bindingMode) {
            case REMOTE_HTTP -> TransportKind.HTTP_JSON;
            default -> TransportKind.IN_PROCESS;
        };
    }

    /**
     * Compute the content-addressed contract id (a {@code urn:unfurl:fabric:contract:<sha256>} URI).
     * The hash seed includes every selected artifact's coordinates/sha/claim-hash, the primary binding
     * (need + capability + version), and the compile instant — so equal inputs produce an equal id.
     *
     * @param selections the per-entry selection records.
     * @param audit      the decision audit (source of the compile instant).
     * @param primary    the chosen primary binding.
     * @return the deterministic contract id URI.
     */
    private static URI contractId(List<SelectionRecord> selections, DecisionAudit audit, PrimaryBinding primary) {
        String seed = selections.stream()
                .map(s -> s.artifact().coordinates() + "|" + s.artifact().sha256() + "|" + s.claimHash())
                .collect(Collectors.joining(";"))
                + "|" + primary.consumerNeed()
                + "|" + primary.offer().capability()
                + "|" + primary.offer().version()
                + "|" + audit.compiledAt();
        return URI.create("urn:unfurl:fabric:contract:" + sha256Hex(seed.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Compute a lowercase hex SHA-256 digest of the given bytes.
     *
     * @param data the input bytes.
     * @return the 64-character lowercase hex digest.
     * @throws IllegalStateException if SHA-256 is unavailable on the JVM (a programmer/environment error).
     */
    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available on this JVM", ex);
        }
    }

    /**
     * Internal value object (Parameter Object) carrying the single chosen binding: the provider entry,
     * the bound offer, the consumer need string, and the binding mode. Lives only within compilation.
     *
     * @param entry        the provider catalog entry.
     * @param offer        the provider offer being bound.
     * @param consumerNeed the consumer need/capability name driving the binding.
     * @param bindingMode  the substrate binding mode for the provider entry.
     */
    private record PrimaryBinding(CatalogEntry entry, Offer offer, String consumerNeed, BindingMode bindingMode) {
    }
}
