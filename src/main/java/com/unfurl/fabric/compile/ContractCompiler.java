package com.unfurl.fabric.compile;

import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.contract.Binding;
import com.unfurl.dcp.contract.CompositionContract;
import com.unfurl.dcp.contract.CompositionContractMetadata;
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
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.ContractCompileException;
import com.unfurl.fabric.compiler.DecisionAudit;
import com.unfurl.fabric.compiler.SelectionRecord;
import com.unfurl.fabric.matcher.CandidateScore;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.PlanningWarning;
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
 * Deterministically compiles a selected {@link CompositionCandidate} into a DCP contract tree.
 *
 * <p>Pattern: <b>Builder/Composite Assembler</b>. The compiler emits an aggregate parent
 * {@link CompositionContract} whose {@link CompositionContractMetadata} references one child contract
 * per selected provider offer. That keeps Flow/Foundry assemblies inside DCP's normal recursive
 * containment model instead of hiding the closure in product-specific planner metadata.
 *
 * <p>The compiled contract ids are <b>content-addressed</b> (SHA-256 over selected artifacts, offers,
 * child ids, and the compile timestamp), so identical inputs at the same instant yield identical output.
 */
public final class ContractCompiler {
    /** Time source for {@code compiledAt} and content-addressed ids; injectable for tests. */
    private final Clock clock;

    /** Production constructor: uses the system UTC clock. */
    public ContractCompiler() {
        this(Clock.systemUTC());
    }

    /**
     * Test constructor: injects the clock used for compile timestamps and content-addressed ids.
     *
     * @param clock time source; falls back to {@link Clock#systemUTC()} when null.
     */
    public ContractCompiler(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Compile a selected candidate into a signed-shape {@link CompiledContract} contract closure.
     *
     * <p>Steps: validate inputs, deterministically sort selected entries, derive one
     * {@link SelectionRecord} per entry, build one child DCP {@link CompositionContract} per selected
     * provider offer, then assemble an aggregate parent whose metadata references those children.
     *
     * @param candidate     the chosen composition; must contain at least one selected entry.
     * @param need          the consumer need; defaults to "no required capabilities" when null.
     * @param hostOwnerMeta consumer identity/provenance metadata; defaults applied when null.
     * @return the compiled root contract, child contracts, selection records, and decision audit.
     * @throws ContractCompileException if the candidate is null/empty or any selected entry exposes no offers.
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
        Instant compiledAt = clock.instant();
        DecisionAudit audit = auditFor(candidate, need, entries, compiledAt);
        List<CompositionContract> childContracts = childContracts(entries, hostOwnerMeta, compiledAt);
        CompositionContract contract = aggregateContract(candidate, hostOwnerMeta, childContracts, audit);
        return new CompiledContract(contract, childContracts, selections, audit, null, null, null);
    }

    /**
     * Composite assembler: creates the aggregate/root contract that references every selected child
     * contract through DCP containment metadata.
     *
     * @param candidate      selected composition.
     * @param hostOwnerMeta  consumer/host identity metadata.
     * @param childContracts child DCP contracts already built for selected offers.
     * @param audit          compile audit carrying the deterministic compile timestamp.
     * @return aggregate parent contract.
     */
    private static CompositionContract aggregateContract(
            CompositionCandidate candidate,
            HostOwnerMeta hostOwnerMeta,
            List<CompositionContract> childContracts,
            DecisionAudit audit
    ) {
        List<Map<String, String>> contains = childContracts.stream()
                .map(child -> Map.of("contractId", child.contractId().toString()))
                .toList();
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("dcpType", "COMPOSITION_CONTRACT");
        extensions.put("level", "CONTRACT_AGGREGATE");
        extensions.put("candidateId", candidate.candidateId());
        extensions.put("childContractCount", childContracts.size());
        extensions.put(CompositionContractMetadata.EXT_CONTAINS, contains);

        URI compositionUri = URI.create("urn:unfurl:fabric:composition:" + candidate.candidateId());
        return new CompositionContract(
                aggregateContractId(candidate, childContracts, audit),
                "0.1.0",
                new Parties(
                        new Party(hostOwnerMeta.consumerClaimUri(), hostOwnerMeta.consumerClaimVersion()),
                        new Party(compositionUri, "0.1.0")),
                new Binding("assembly.requirements", "assembly.aggregate", "0.1.0"),
                new DataMapping(Map.of(), Map.of()),
                new Transport(TransportKind.IN_PROCESS, Map.of("bindingMode", "AGGREGATE")),
                new Expectations(null, true, false, false),
                new Provenance(CreatedBy.FABRIC, NegotiationMode.H2H, null,
                        hostOwnerMeta.fabricVersion(), false, audit.compiledAt()),
                new Trust(TrustTier.NEUTRAL),
                new Invalidation(List.of(), RuntimeViolationPolicy.HARD_FAIL),
                new CompositionContractMetadata(extensions));
    }

    /**
     * Composite assembler: creates one child composition contract for each selected provider offer.
     *
     * @param entries       deterministic selected entries.
     * @param hostOwnerMeta consumer/host identity metadata.
     * @param compiledAt    compile timestamp used in content-addressed ids and provenance.
     * @return immutable list of child contracts.
     */
    private static List<CompositionContract> childContracts(
            List<CatalogEntry> entries,
            HostOwnerMeta hostOwnerMeta,
            Instant compiledAt
    ) {
        List<CompositionContract> contracts = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            List<Offer> offers = sortedOffers(entry);
            if (offers.isEmpty()) {
                throw new ContractCompileException(
                        "selected entry has no offers to bind: " + entry.artifact().coordinates());
            }
            for (Offer offer : offers) {
                contracts.add(childContract(entry, offer, hostOwnerMeta, compiledAt));
            }
        }
        return List.copyOf(contracts);
    }

    /**
     * Leaf-contract assembler: freezes one selected provider offer as a normal DCP composition edge.
     *
     * @param entry         selected catalog entry.
     * @param offer         selected entry offer.
     * @param hostOwnerMeta consumer/host identity metadata.
     * @param compiledAt    compile timestamp used in id/provenance.
     * @return child composition contract for the offer.
     */
    private static CompositionContract childContract(
            CatalogEntry entry,
            Offer offer,
            HostOwnerMeta hostOwnerMeta,
            Instant compiledAt
    ) {
        String capability = requireOfferCapability(entry, offer);
        String version = requireOfferVersion(entry, offer);
        BindingMode bindingMode = entry.metadata().binding().defaultMode();
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("dcpType", "COMPOSITION_CONTRACT");
        extensions.put("level", "CONTRACT_CHILD");
        extensions.put("artifactCoordinates", entry.artifact().coordinates());
        extensions.put("artifactSha256", entry.artifact().sha256());
        extensions.put("claimHash", entry.claimDescriptor().claimHash());

        return new CompositionContract(
                childContractId(entry, offer, compiledAt),
                "0.1.0",
                new Parties(
                        new Party(hostOwnerMeta.consumerClaimUri(), hostOwnerMeta.consumerClaimVersion()),
                        new Party(entry.claimDescriptor().claim().identity().uri(),
                                entry.claimDescriptor().claim().identity().version())),
                new Binding(capability, capability, version),
                new DataMapping(Map.of(), Map.of()),
                new Transport(transportKind(bindingMode), Map.of("bindingMode", bindingMode.name())),
                new Expectations(null, true, false, false),
                new Provenance(CreatedBy.FABRIC, NegotiationMode.H2H, null,
                        hostOwnerMeta.fabricVersion(), false, compiledAt),
                new Trust(TrustTier.NEUTRAL),
                new Invalidation(List.of(), RuntimeViolationPolicy.HARD_FAIL),
                new CompositionContractMetadata(extensions));
    }

    /**
     * Build the {@link SelectionRecord} for one catalog entry: artifact, claim hash, binding mode,
     * and selected interface kind.
     *
     * @param entry the selected catalog entry.
     * @return immutable selection record.
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
     * Assemble the human-readable {@link DecisionAudit}: alternatives considered, selected reasons,
     * satisfied capabilities, score breakdown, and planning warnings.
     *
     * @param candidate  chosen candidate.
     * @param need       consumer need retained for audit-shape stability.
     * @param entries    selected entries in deterministic order.
     * @param compiledAt compile timestamp.
     * @return decision audit.
     */
    private static DecisionAudit auditFor(
            CompositionCandidate candidate,
            Need need,
            List<CatalogEntry> entries,
            Instant compiledAt
    ) {
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
     * Render an entry's offered capability names as a stable, comma-joined, sorted string for audit.
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
     * Flatten a {@link CandidateScore} into an ordered name-to-value map for deterministic output.
     *
     * @param score the candidate score.
     * @return insertion-ordered map of score components.
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
     * Return entries in deterministic order by artifact coordinates, then SHA-256.
     *
     * @param entries unsorted selected entries.
     * @return immutable sorted copy.
     */
    private static List<CatalogEntry> sortedEntries(List<CatalogEntry> entries) {
        List<CatalogEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing((CatalogEntry e) -> e.artifact().coordinates())
                .thenComparing(e -> e.artifact().sha256()));
        return List.copyOf(sorted);
    }

    /**
     * Return offers in deterministic order so child contract ids and YAML output are stable.
     *
     * @param entry selected catalog entry.
     * @return immutable, sorted offers.
     */
    private static List<Offer> sortedOffers(CatalogEntry entry) {
        return entry.claimDescriptor().claim().offers().stream()
                .sorted(Comparator.comparing(Offer::capability)
                        .thenComparing(Offer::version, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /**
     * Offer guard: returns the capability or fails compile rather than fabricating a DCP binding.
     *
     * @param entry selected catalog entry used for diagnostics.
     * @param offer provider offer.
     * @return non-blank capability.
     */
    private static String requireOfferCapability(CatalogEntry entry, Offer offer) {
        if (offer.capability() == null || offer.capability().isBlank()) {
            throw new ContractCompileException(
                    "selected offer has no capability: " + entry.artifact().coordinates());
        }
        return offer.capability();
    }

    /**
     * Offer guard: returns the version or fails compile rather than emitting an under-specified edge.
     *
     * @param entry selected catalog entry used for diagnostics.
     * @param offer provider offer.
     * @return non-blank provider capability version.
     */
    private static String requireOfferVersion(CatalogEntry entry, Offer offer) {
        if (offer.version() == null || offer.version().isBlank()) {
            throw new ContractCompileException(
                    "selected offer has no capability version: " + entry.artifact().coordinates());
        }
        return offer.version();
    }

    /**
     * Map a substrate {@link BindingMode} to the DCP {@link TransportKind} recorded on the contract.
     *
     * @param bindingMode entry default binding mode.
     * @return HTTP_JSON for remote bindings, IN_PROCESS otherwise.
     */
    private static TransportKind transportKind(BindingMode bindingMode) {
        return switch (bindingMode) {
            case REMOTE_HTTP -> TransportKind.HTTP_JSON;
            default -> TransportKind.IN_PROCESS;
        };
    }

    /**
     * Compute the aggregate/root contract id from the candidate id and loaded child contract ids.
     *
     * @param candidate selected composition.
     * @param childContracts child contracts referenced by the parent.
     * @param audit decision audit carrying the compile timestamp.
     * @return deterministic aggregate contract URI.
     */
    private static URI aggregateContractId(
            CompositionCandidate candidate,
            List<CompositionContract> childContracts,
            DecisionAudit audit
    ) {
        String childSeed = childContracts.stream()
                .map(contract -> contract.contractId().toString())
                .sorted()
                .collect(Collectors.joining(";"));
        String seed = candidate.candidateId() + "|" + childSeed + "|" + audit.compiledAt();
        return URI.create("urn:unfurl:fabric:contract:aggregate:"
                + sha256Hex(seed.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Compute a child contract id from the selected artifact, provider offer, binding mode, and time.
     *
     * @param entry selected catalog entry.
     * @param offer selected provider offer.
     * @param compiledAt compile timestamp.
     * @return deterministic child contract URI.
     */
    private static URI childContractId(CatalogEntry entry, Offer offer, Instant compiledAt) {
        String seed = entry.artifact().coordinates()
                + "|" + entry.artifact().sha256()
                + "|" + entry.claimDescriptor().claimHash()
                + "|" + entry.claimDescriptor().claim().identity().uri()
                + "|" + offer.capability()
                + "|" + offer.version()
                + "|" + entry.metadata().binding().defaultMode()
                + "|" + compiledAt;
        return URI.create("urn:unfurl:fabric:contract:child:"
                + sha256Hex(seed.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Compute a lowercase hex SHA-256 digest of the given bytes.
     *
     * @param data input bytes.
     * @return 64-character lowercase hex digest.
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
}
