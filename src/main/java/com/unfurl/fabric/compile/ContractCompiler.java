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

public final class ContractCompiler {
    private final Clock clock;

    public ContractCompiler() {
        this(Clock.systemUTC());
    }

    public ContractCompiler(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

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

    private static String offeredCapabilities(CatalogEntry entry) {
        return entry.claimDescriptor().claim().offers().stream()
                .map(Offer::capability)
                .sorted()
                .collect(Collectors.joining(","));
    }

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

    private static List<CatalogEntry> sortedEntries(List<CatalogEntry> entries) {
        List<CatalogEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing((CatalogEntry e) -> e.artifact().coordinates())
                .thenComparing(e -> e.artifact().sha256()));
        return List.copyOf(sorted);
    }

    private static TransportKind transportKind(BindingMode bindingMode) {
        return switch (bindingMode) {
            case REMOTE_HTTP -> TransportKind.HTTP_JSON;
            default -> TransportKind.IN_PROCESS;
        };
    }

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

    private record PrimaryBinding(CatalogEntry entry, Offer offer, String consumerNeed, BindingMode bindingMode) {
    }
}
