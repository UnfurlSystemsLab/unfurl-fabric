package com.unfurl.fabric.runtime;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.documentation.CapabilityDocumentationValidator;
import com.unfurl.dcp.documentation.DocumentationGap;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.SelectedCatalogEntries;
import com.unfurl.fabric.signing.SignedFabricContract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Facade: the export-time documentation check, {@code fabric documentation-check} and Flowfoundry Step 18
 * {@code fabric.documentation-check} (HLD-api-documentation-artifacts §6).
 *
 * <p>It reuses the binding generator's own assembly ({@link RuntimeBindingSetGenerator#assemble}), so it checks exactly
 * the bindings {@code runtime-bindings} would write, then runs DCP's gap gate over them with the given visibility policy,
 * whether or not the composition contains an API catalog. The selected components' shipped artifacts were verified when
 * the catalog was scanned and resolved ({@link SelectedCatalogEntries}). It writes no binding and never infers a schema.
 */
public final class DocumentationCheck {
    /** The report status when no gap was found. */
    public static final String PASS = "PASS";
    /** The report status when a gap was found. */
    public static final String GAP = "GAP";

    private final RuntimeBindingSetGenerator generator;

    /** Production constructor. */
    public DocumentationCheck() {
        this(new RuntimeBindingSetGenerator());
    }

    /** Seam constructor: the generator whose assembly is checked. */
    DocumentationCheck(RuntimeBindingSetGenerator generator) {
        this.generator = generator;
    }

    /**
     * Runs the check.
     *
     * @param signed   the signed contract
     * @param selected its selections, resolved in its catalog
     * @param options  the options {@code runtime-bindings} would take, documentation settings included
     * @return the report; {@link Report#status()} is {@value #PASS} or {@value #GAP}
     * @throws IllegalStateException when the bindings themselves cannot be built (for example identity inputs or a
     *                               missing service base URL), exactly as {@code runtime-bindings} would refuse
     */
    public Report run(SignedFabricContract signed, SelectedCatalogEntries selected, RuntimeBindingSetGenerator.Options options) {
        RuntimeBindingSetGenerator.Assembled assembled = generator.assemble(signed, selected, options);
        List<DocumentationGap> gaps = new CapabilityDocumentationValidator().gaps(assembled.childContracts(), selected.claims(),
                assembled.childBindings(), assembled.options().documentation().policy());
        List<Gap> reported = gaps.stream()
                .map(gap -> new Gap(gap.contractId().toString(), gap.capability(), gap.providerClaimUri().toString()))
                .toList();
        List<Component> components = new ArrayList<>();
        for (CatalogEntry entry : selected.entries()) {
            Claim claim = entry.claimDescriptor().claim();
            List<String> documented = new ArrayList<>();
            List<String> external = new ArrayList<>();
            claim.offers().forEach(offer -> {
                boolean externalProtocol = offer.offerInterface() != null
                        && offer.offerInterface().interfaceKind() == com.unfurl.dcp.claim.InterfaceKind.EXTERNAL_PROTOCOL;
                if (externalProtocol) {
                    external.add(offer.capability());
                } else if (offer.documentation() != null && !offer.documentation().operations().isEmpty()) {
                    documented.add(offer.capability());
                }
            });
            components.add(new Component(claim.identity().uri().toString(), claim.identity().version(),
                    documented.stream().sorted().toList(), external.stream().sorted().toList()));
        }
        components.sort(Comparator.comparing(Component::claimUri));
        return new Report(reported.isEmpty() ? PASS : GAP, reported, List.copyOf(components));
    }

    /**
     * Value Object: the check's result, written as {@code step-18-documentation-check-report.json}.
     *
     * @param status     {@value #PASS} or {@value #GAP}
     * @param gaps       every gap, sorted by contract id
     * @param components each selected component, with the capabilities it documents and the ones it offers over its own
     *                   protocol (listed, never documented as DCP operations)
     */
    public record Report(String status, List<Gap> gaps, List<Component> components) { }

    /** Value Object: one documentation gap: the contract, its capability and the provider that ships no schemas. */
    public record Gap(String contractId, String capability, String provider) { }

    /** Value Object: one selected component in the report. */
    public record Component(String claimUri, String version, List<String> documented, List<String> external) { }
}
