package com.unfurl.fabric.studio;

import com.unfurl.dcp.claim.Claim;
import com.unfurl.dcp.claim.Concern;
import com.unfurl.dcp.claim.DecisionOwned;
import com.unfurl.dcp.claim.Dependencies;
import com.unfurl.dcp.claim.InterfaceKind;
import com.unfurl.dcp.claim.Offer;
import com.unfurl.dcp.claim.OperationDocumentation;
import com.unfurl.dcp.claim.StateOwned;
import com.unfurl.dcp.contract.CompositionContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The four Studio views' projections (LLD-fabric-studio-api, <i>StudioViewProjection</i>): each reads the typed claim
 * fields, or the current compile record's contract, that its view needs and nothing else.
 *
 * <p>Pattern: <b>Strategy</b>, one constant per view, over a {@link StudioViewContext}. Every value is read from the
 * {@code unfurl-dcp} model of a claim Fabric grounded, never parsed from text, and every item names the DCP field it
 * came from. A section with nothing to show is unavailable with its reason ({@link StudioViewSection#of}); the
 * {@code integration_ports.ports} wiring map is opaque to DCP and is never projected.
 */
enum StudioViewProjector {
    /** Flow & Composition: external owners and external-protocol offers (DCP HLD-C2 A.5, A.6). */
    FLOW("flow") {
        @Override
        Sections project(StudioViewContext context) {
            if (context.nodes().isEmpty()) {
                return new Sections().externalOwners(StudioViewSection.absent(NO_COMPONENTS, null))
                        .externalProtocolOffers(StudioViewSection.absent(NO_COMPONENTS, null));
            }
            List<StudioViewProjection.ExternalOwner> owners = new ArrayList<>();
            List<StudioViewProjection.ExternalProtocolOffer> protocols = new ArrayList<>();
            for (StudioViewContext.GroundedNode node : context.nodes()) {
                Dependencies dependencies = node.claim().dependencies();
                if (dependencies != null) {
                    addOwners(owners, node.nodeId(), dependencies.needs(), "claim.dependencies.needs");
                    addOwners(owners, node.nodeId(), dependencies.optional(), "claim.dependencies.optional");
                }
                List<Offer> offers = node.claim().offers();
                for (int i = 0; i < offers.size(); i++) {
                    Offer offer = offers.get(i);
                    if (offer.offerInterface() != null && offer.offerInterface().interfaceKind() == InterfaceKind.EXTERNAL_PROTOCOL) {
                        protocols.add(new StudioViewProjection.ExternalProtocolOffer(node.nodeId(), offer.capability(),
                                String.valueOf(offer.offerInterface().details().get("protocol")),
                                "claim.offers[" + i + "].interface.details.protocol"));
                    }
                }
            }
            return new Sections()
                    .externalOwners(StudioViewSection.of(owners, "NO_EXTERNAL_OWNERS",
                            "no selected component declares a dependency bound outside the catalog (owner=)", null))
                    .externalProtocolOffers(StudioViewSection.of(protocols, "NO_EXTERNAL_PROTOCOL_OFFERS",
                            "no selected component offers a capability over its own protocol (external_protocol)", null));
        }

        /** One external owner per dependency whose owner= qualifier is set. */
        private void addOwners(List<StudioViewProjection.ExternalOwner> owners, String nodeId, List<String> dependencies,
                               String path) {
            for (int i = 0; i < dependencies.size(); i++) {
                String owner = Dependencies.ownerOf(dependencies.get(i));
                if (!owner.isBlank()) {
                    owners.add(new StudioViewProjection.ExternalOwner("owner:" + owner, owner, dependencies.get(i), nodeId,
                            path + "[" + i + "]"));
                }
            }
        }
    },

    /** Data & State: owned state and decisions, documented operations, and the compile's data mappings. */
    DATA("data") {
        @Override
        Sections project(StudioViewContext context) {
            Sections sections = new Sections();
            if (context.nodes().isEmpty()) {
                sections.ownedState(StudioViewSection.absent(NO_COMPONENTS, null))
                        .ownedDecisions(StudioViewSection.absent(NO_COMPONENTS, null))
                        .operations(StudioViewSection.absent(NO_COMPONENTS, null));
            } else {
                List<StudioViewProjection.OwnedState> state = new ArrayList<>();
                List<StudioViewProjection.OwnedDecision> decisions = new ArrayList<>();
                List<StudioViewProjection.Operation> operations = new ArrayList<>();
                for (StudioViewContext.GroundedNode node : context.nodes()) {
                    collectOwnership(node, state, decisions);
                    collectOperations(node, operations);
                }
                sections.ownedState(StudioViewSection.of(state, "NO_OWNED_STATE_DECLARED",
                                "no selected component's concerns declare owned state (owns_state)", null))
                        .ownedDecisions(StudioViewSection.of(decisions, "NO_OWNED_DECISIONS_DECLARED",
                                "no selected component's concerns declare owned decisions (owns_decisions)", null))
                        .operations(StudioViewSection.of(operations, "NO_OPERATIONS_DOCUMENTED",
                                "no selected component documents its offers' operations (documentation.operations)", null));
            }
            StudioViewContext.CompiledView compiled = context.compile().get();
            if (compiled.contract() == null) {
                return sections.dataMappings(StudioViewSection.absent(compiled.absentReason(), compiled.provenance()));
            }
            List<StudioViewProjection.DataMapping> mappings = new ArrayList<>();
            List<CompositionContract> children = compiled.contract().childContracts();
            for (int i = 0; i < children.size(); i++) {
                CompositionContract child = children.get(i);
                if (child.dataMapping() == null
                        || child.dataMapping().inbound().isEmpty() && child.dataMapping().outbound().isEmpty()) {
                    continue;
                }
                mappings.add(new StudioViewProjection.DataMapping(String.valueOf(child.contractId()),
                        providerNode(compiled, child), child.dataMapping().inbound(), child.dataMapping().outbound(),
                        "contract.child_contracts[" + i + "].data_mapping"));
            }
            return sections.dataMappings(StudioViewSection.of(mappings, "NO_DATA_MAPPINGS",
                    "no child contract of the current compile maps any field (data_mapping)", compiled.provenance()));
        }

        /** Each concern's owned state and decisions, with their DCP paths. */
        private void collectOwnership(StudioViewContext.GroundedNode node, List<StudioViewProjection.OwnedState> state,
                                      List<StudioViewProjection.OwnedDecision> decisions) {
            if (node.claim().domain() == null) {
                return;
            }
            List<Concern> concerns = node.claim().domain().concerns();
            for (int c = 0; c < concerns.size(); c++) {
                Concern concern = concerns.get(c);
                for (int s = 0; s < concern.ownsState().size(); s++) {
                    StateOwned owned = concern.ownsState().get(s);
                    state.add(new StudioViewProjection.OwnedState(node.nodeId(), concern.concern(), owned.resource(),
                            owned.description(), enumName(owned.sensitivity()),
                            "claim.domain.concerns[" + c + "].owns_state[" + s + "]"));
                }
                for (int d = 0; d < concern.ownsDecisions().size(); d++) {
                    DecisionOwned owned = concern.ownsDecisions().get(d);
                    decisions.add(new StudioViewProjection.OwnedDecision(node.nodeId(), concern.concern(), owned.decision(),
                            owned.description(), enumName(owned.authority()),
                            "claim.domain.concerns[" + c + "].owns_decisions[" + d + "]"));
                }
            }
        }

        /** Each documented operation of each offer, with its schemas as the claim names them. */
        private void collectOperations(StudioViewContext.GroundedNode node, List<StudioViewProjection.Operation> operations) {
            List<Offer> offers = node.claim().offers();
            for (int i = 0; i < offers.size(); i++) {
                Offer offer = offers.get(i);
                if (offer.documentation() == null) {
                    continue;
                }
                List<OperationDocumentation> documented = offer.documentation().operations();
                for (int o = 0; o < documented.size(); o++) {
                    OperationDocumentation operation = documented.get(o);
                    operations.add(new StudioViewProjection.Operation(node.nodeId(), offer.capability(),
                            operation.operation(), operation.summary(), operation.requestSchema(),
                            operation.responseSchema(), "claim.offers[" + i + "].documentation.operations[" + o + "]"));
                }
            }
        }
    },

    /** Substrate & Topology: the compile's transports (runtime bindings arrive with the emit plan, P2d). */
    TOPOLOGY("topology") {
        @Override
        Sections project(StudioViewContext context) {
            StudioViewContext.CompiledView compiled = context.compile().get();
            if (compiled.contract() == null) {
                return new Sections().transports(StudioViewSection.absent(compiled.absentReason(), compiled.provenance()));
            }
            List<StudioViewProjection.Transport> transports = new ArrayList<>();
            List<CompositionContract> children = compiled.contract().childContracts();
            for (int i = 0; i < children.size(); i++) {
                CompositionContract child = children.get(i);
                if (child.transport() == null) {
                    continue;
                }
                transports.add(new StudioViewProjection.Transport(String.valueOf(child.contractId()),
                        providerNode(compiled, child), enumName(child.transport().kind()), child.transport().details(),
                        "contract.child_contracts[" + i + "].transport"));
            }
            return new Sections().transports(StudioViewSection.of(transports, "NO_TRANSPORTS",
                    "the current compile has no child contract with a transport", compiled.provenance()));
        }
    },

    /** Runtime & Telemetry: declared monitoring and telemetry (DCP HLD-C2 A.9), and the current compile's verification. */
    RUNTIME("runtime") {
        @Override
        Sections project(StudioViewContext context) {
            Sections sections = new Sections().verification(context.verification().get());
            if (context.nodes().isEmpty()) {
                return sections.monitoring(StudioViewSection.absent(NO_COMPONENTS, null))
                        .telemetry(StudioViewSection.absent(NO_COMPONENTS, null));
            }
            List<StudioViewProjection.Monitoring> monitoring = new ArrayList<>();
            List<StudioViewProjection.Telemetry> telemetry = new ArrayList<>();
            for (StudioViewContext.GroundedNode node : context.nodes()) {
                Claim claim = node.claim();
                if (claim.integrationPorts() == null) {
                    continue;
                }
                var monitor = claim.integrationPorts().monitoring();
                if (monitor != null) {
                    monitoring.add(new StudioViewProjection.Monitoring(node.nodeId(), monitor.required(),
                            monitor.healthEndpoints(), monitor.readinessRequired(), monitor.livenessRequired(),
                            monitor.notes(), "claim.integration_ports.monitoring"));
                }
                var telemetered = claim.integrationPorts().telemetry();
                if (telemetered != null) {
                    telemetry.add(new StudioViewProjection.Telemetry(node.nodeId(), telemetered.required(),
                            telemetered.signals() == null ? null
                                    : telemetered.signals().stream().map(StudioViewProjector::enumName).toList(),
                            telemetered.correlationIdRequired(), telemetered.notes(), "claim.integration_ports.telemetry"));
                }
            }
            return sections
                    .monitoring(StudioViewSection.of(monitoring, "NO_MONITORING_DECLARED",
                            "no selected component declares monitoring (integration_ports.monitoring)", null))
                    .telemetry(StudioViewSection.of(telemetry, "NO_TELEMETRY_DECLARED",
                            "no selected component declares telemetry (integration_ports.telemetry)", null));
        }
    };

    /** The reason every claim section gives for a draft with no component node. */
    static final StudioAbsentReason NO_COMPONENTS = new StudioAbsentReason("NO_COMPONENTS",
            "the draft has no components; add one to see this view");

    private final String id;

    StudioViewProjector(String id) {
        this.id = id;
    }

    /** The view's id on the route ({@code flow}, {@code data}, {@code topology}, {@code runtime}). */
    String id() {
        return id;
    }

    /**
     * The view's sections.
     *
     * @param context the grounded nodes, the current compile and its verification.
     * @return the sections this view sets; the others stay null.
     */
    abstract Sections project(StudioViewContext context);

    /**
     * The projector for a route's view id.
     *
     * @param view the id from the route.
     * @return the projector.
     * @throws IllegalArgumentException {@code VIEW_UNKNOWN} for any other id; the route answers 400.
     */
    static StudioViewProjector forView(String view) {
        for (StudioViewProjector projector : values()) {
            if (projector.id.equals(view)) {
                return projector;
            }
        }
        throw new IllegalArgumentException("VIEW_UNKNOWN: view must be flow, data, topology or runtime, got '" + view + "'");
    }

    /** A child contract's provider node id, when one of the draft's grounded nodes is its provider party. */
    private static String providerNode(StudioViewContext.CompiledView compiled, CompositionContract child) {
        Optional<String> node = child.parties() == null || child.parties().provider() == null ? Optional.empty()
                : compiled.providerNode(child.parties().provider().claimUri());
        return node.orElse(null);
    }

    /** An enum value exactly as DCP holds and writes it (upper case, HLD-C2 A.9); Fabric never transforms it. */
    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** The sections one projector sets, gathered before the response is assembled. Builder. */
    static final class Sections {
        StudioViewSection<StudioViewProjection.ExternalOwner> externalOwners;
        StudioViewSection<StudioViewProjection.ExternalProtocolOffer> externalProtocolOffers;
        StudioViewSection<StudioViewProjection.OwnedState> ownedState;
        StudioViewSection<StudioViewProjection.OwnedDecision> ownedDecisions;
        StudioViewSection<StudioViewProjection.Operation> operations;
        StudioViewSection<StudioViewProjection.DataMapping> dataMappings;
        StudioViewSection<StudioViewProjection.Transport> transports;
        StudioViewSection<StudioViewProjection.Monitoring> monitoring;
        StudioViewSection<StudioViewProjection.Telemetry> telemetry;
        StudioVerificationResult verification;

        Sections externalOwners(StudioViewSection<StudioViewProjection.ExternalOwner> value) { externalOwners = value; return this; }
        Sections externalProtocolOffers(StudioViewSection<StudioViewProjection.ExternalProtocolOffer> value) { externalProtocolOffers = value; return this; }
        Sections ownedState(StudioViewSection<StudioViewProjection.OwnedState> value) { ownedState = value; return this; }
        Sections ownedDecisions(StudioViewSection<StudioViewProjection.OwnedDecision> value) { ownedDecisions = value; return this; }
        Sections operations(StudioViewSection<StudioViewProjection.Operation> value) { operations = value; return this; }
        Sections dataMappings(StudioViewSection<StudioViewProjection.DataMapping> value) { dataMappings = value; return this; }
        Sections transports(StudioViewSection<StudioViewProjection.Transport> value) { transports = value; return this; }
        Sections monitoring(StudioViewSection<StudioViewProjection.Monitoring> value) { monitoring = value; return this; }
        Sections telemetry(StudioViewSection<StudioViewProjection.Telemetry> value) { telemetry = value; return this; }
        Sections verification(StudioVerificationResult value) { verification = value; return this; }
    }
}
