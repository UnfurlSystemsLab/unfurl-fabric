package com.unfurl.fabric.compile;

import com.unfurl.fabric.compiler.ContractCompileException;
import com.unfurl.dcp.contract.Party;
import com.unfurl.deployment.domain.ComponentHost;
import com.unfurl.deployment.plan.BindingPlan;
import com.unfurl.deployment.plan.BindingPlanEntry;
import com.unfurl.deployment.resolver.DeploymentComponent;
import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.catalog.ComponentHostDeclaration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Value plus projections for hosted in-process components (HLD-in-process-component-hosting §4): which selected entries
 * are hosted, by whom, and what their host shares. It connects the deployment resolver's placement (HLD-D §6.2a) to the
 * compiler, which writes the host as consumer of the hosted entry's child contracts (DCP HLD-C §6.1 rule 7).
 *
 * @param placements each hosted entry's placement, by its artifact coordinates
 */
public record HostedComponents(Map<String, Placement> placements) {
    /** DCP's base shared API, always shared by any host in addition to its own prefixes. */
    static final List<String> DCP_BASE = List.of("com.unfurl.dcp.", "com.unfurl.substrate.");

    /**
     * One hosted entry's placement.
     *
     * @param consumer          the host's claim identity, the consumer of the entry's child contracts
     * @param host              the host as the plan records it
     * @param sharedApiPrefixes every package the host shares: DCP's base set plus the host's own declared prefixes
     */
    public record Placement(Party consumer, ComponentHost host, List<String> sharedApiPrefixes) {
        /** Validating constructor. */
        public Placement {
            sharedApiPrefixes = List.copyOf(sharedApiPrefixes);
        }
    }

    /** Validating constructor: copies the map in key order. */
    public HostedComponents {
        placements = placements == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(placements));
    }

    /** No hosted components. */
    public static HostedComponents none() {
        return new HostedComponents(Map.of());
    }

    /** Returns the placement of a selected entry, if it is hosted. */
    public Optional<Placement> placement(CatalogEntry entry) {
        return Optional.ofNullable(placements.get(entry.artifact().coordinates()));
    }

    /**
     * Projects the resolved plan's hosts onto the selected entries. An internal host is another selected entry, whose
     * packaged claim identity becomes the consumer; an external host is its own claim identity.
     *
     * @throws ContractCompileException {@code HOST_PLACEMENT_INVALID} when an internal host is not a selected entry
     */
    public static HostedComponents from(List<CatalogEntry> selected, BindingPlan plan) {
        Map<String, CatalogEntry> byCoordinates = new TreeMap<>();
        selected.forEach(entry -> byCoordinates.put(entry.artifact().coordinates(), entry));
        Map<String, Placement> placements = new TreeMap<>();
        for (BindingPlanEntry entry : plan == null ? List.<BindingPlanEntry>of() : plan.entries()) {
            if (entry.host().isEmpty() || placements.containsKey(entry.componentId())) continue;
            ComponentHost host = entry.host().get();
            Placement placement = switch (host) {
                case ComponentHost.InternalHost internal -> {
                    CatalogEntry hostEntry = byCoordinates.get(internal.componentId());
                    if (hostEntry == null) {
                        throw new ContractCompileException("HOST_PLACEMENT_INVALID: host " + internal.componentId()
                                + " of " + entry.componentId() + " is not a selected component");
                    }
                    List<String> prefixes = new java.util.ArrayList<>(DCP_BASE);
                    hostEntry.metadata().optionalComponentHost().map(ComponentHostDeclaration::sharedApiPrefixes).ifPresent(prefixes::addAll);
                    var identity = hostEntry.claimDescriptor().claim().identity();
                    yield new Placement(new Party(identity.uri(), identity.version()), host, prefixes);
                }
                case ComponentHost.ExternalHost external ->
                        new Placement(new Party(external.claimUri(), external.claimVersion()), host, DCP_BASE);
            };
            placements.put(entry.componentId(), placement);
        }
        return new HostedComponents(placements);
    }

    /** Projects an entry's {@code hosted_activation} into the deployment resolver's input. */
    public static Optional<DeploymentComponent.HostedActivation> hostedActivation(CatalogEntry entry) {
        return entry.metadata().optionalHostedActivation()
                .map(activation -> new DeploymentComponent.HostedActivation(java.util.Set.copyOf(activation.registrarShapes())));
    }

    /** Projects an entry's {@code component_host} into the deployment resolver's input. */
    public static Optional<DeploymentComponent.HostOffer> componentHost(CatalogEntry entry) {
        return entry.metadata().optionalComponentHost().map(host -> new DeploymentComponent.HostOffer(host.registrarShape()));
    }
}
