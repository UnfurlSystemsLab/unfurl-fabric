package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Containment derived from bindings (LLD-fabric-studio-api): a component nests under the component that uses it, under
 * the nearest common user when several do, never through a cycle, and never over declared containment.
 */
class StudioDerivedContainmentTest {

    private static final String ASSEMBLY = "assembly:a";

    /** The assembly at depth 1 and each named component declared directly under it, at depth 2. */
    private static List<StudioDynamicDcpNode> nodes(String... components) {
        List<StudioDynamicDcpNode> nodes = new ArrayList<>();
        nodes.add(new StudioDynamicDcpNode("company:x", "X", "COMPANY", "PARENT", null, 0, "", List.of(), List.of(), false));
        nodes.add(new StudioDynamicDcpNode(ASSEMBLY, "A", "MODULE", "ASSEMBLY", "company:x", 1, "", List.of(), List.of(), false));
        for (String id : components) {
            nodes.add(new StudioDynamicDcpNode(id, id, "COMPONENT", "CHILD", ASSEMBLY, 2, "uploaded:" + id, List.of(), List.of(), true));
        }
        return nodes;
    }

    /** The declared tree: company contains the assembly, the assembly contains each component. */
    private static List<StudioDynamicDcpEdge> declared(List<StudioDynamicDcpNode> nodes) {
        List<StudioDynamicDcpEdge> edges = new ArrayList<>();
        nodes.forEach(node -> { if (node.parentNodeId() != null) edges.add(new StudioDynamicDcpEdge(node.parentNodeId(), node.nodeId(), "CONTAINS")); });
        return edges;
    }

    /** A matched binding: {@code user} uses what {@code provider} offers. */
    private static StudioPortConnectionEdge uses(String user, String provider) {
        return new StudioPortConnectionEdge(provider, "offer", user, "need", provider + ".cap", "ALLOWED", "offered");
    }

    private static Map<String, String> parents(StudioDerivedContainment.Result result) {
        return result.nodes().stream().filter(node -> node.parentNodeId() != null)
                .collect(Collectors.toMap(StudioDynamicDcpNode::nodeId, StudioDynamicDcpNode::parentNodeId));
    }

    @Test
    void nestsAComponentUnderItsOnlyUserAndASharedOneUnderTheNearestCommonUser() {
        List<StudioDynamicDcpNode> nodes = nodes("flow", "foundry", "keycloak", "adapter", "rag", "standalone");
        StudioDerivedContainment.Result result = StudioDerivedContainment.apply(nodes, declared(nodes), List.of(
                uses("flow", "foundry"),
                uses("flow", "keycloak"), uses("foundry", "keycloak"),
                uses("foundry", "adapter"), uses("rag", "adapter")), ASSEMBLY);

        Map<String, String> parents = parents(result);
        assertThat(parents).containsEntry("foundry", "flow")
                .containsEntry("keycloak", "flow")            // Flow reaches Foundry: the nearest common user
                .containsEntry("adapter", ASSEMBLY)          // no component reaches both Foundry and rag
                .containsEntry("rag", ASSEMBLY)
                .containsEntry("standalone", ASSEMBLY)
                .containsEntry("flow", ASSEMBLY);
        assertThat(result.nodes()).filteredOn(node -> node.nodeId().equals("foundry"))
                .singleElement().satisfies(node -> assertThat(node.depth()).isEqualTo(3));
        assertThat(result.edges()).filteredOn(edge -> edge.toNodeId().equals("foundry")).singleElement().satisfies(edge -> {
            assertThat(edge.fromNodeId()).isEqualTo("flow");
            assertThat(edge.derivation()).isEqualTo(StudioDynamicDcpEdge.DERIVED_FROM_BINDINGS);
        });
        assertThat(result.edges()).filteredOn(edge -> edge.toNodeId().equals("flow")).singleElement()
                .satisfies(edge -> assertThat(edge.derivation()).isEqualTo(StudioDynamicDcpEdge.DECLARED));
        assertThat(result.edges()).extracting(StudioDynamicDcpEdge::relationship).containsOnly("CONTAINS");
    }

    @Test
    void neverTurnsACycleIntoContainment() {
        List<StudioDynamicDcpNode> nodes = nodes("a", "b", "c");
        StudioDerivedContainment.Result result = StudioDerivedContainment.apply(nodes, declared(nodes), List.of(
                uses("a", "b"), uses("b", "a"), uses("a", "c")), ASSEMBLY);

        assertThat(parents(result)).containsEntry("a", ASSEMBLY).containsEntry("b", ASSEMBLY).containsEntry("c", "a");
    }

    @Test
    void leavesAComponentInPlaceWhenTwoUsersAreEquallyNear() {
        List<StudioDynamicDcpNode> nodes = nodes("x", "y", "p", "q", "shared");
        // x and y each use p and q; p and q both use shared. Both x and y reach p and q, and neither reaches the other.
        StudioDerivedContainment.Result result = StudioDerivedContainment.apply(nodes, declared(nodes), List.of(
                uses("x", "p"), uses("x", "q"), uses("y", "p"), uses("y", "q"),
                uses("p", "shared"), uses("q", "shared")), ASSEMBLY);

        assertThat(parents(result)).containsEntry("shared", ASSEMBLY).containsEntry("p", ASSEMBLY).containsEntry("q", ASSEMBLY);
    }

    @Test
    void ignoresBlockedAndSubstrateConnectionsAndKeepsDeclaredContainment() {
        List<StudioDynamicDcpNode> nodes = new ArrayList<>(nodes("user", "provider", "nested"));
        nodes.removeIf(node -> node.nodeId().equals("nested"));
        nodes.add(new StudioDynamicDcpNode("nested", "nested", "COMPONENT", "CHILD", "provider", 3, "uploaded:nested",
                List.of(), List.of(), true));
        StudioPortConnectionEdge blocked = new StudioPortConnectionEdge("provider", "o", "user", "n", "cap", "BLOCKED", "refused");
        StudioPortConnectionEdge substrate = new StudioPortConnectionEdge("substrate:runtime", "port", "user", "n", "cap", "ALLOWED", "substrate");
        StudioDerivedContainment.Result result = StudioDerivedContainment.apply(nodes, declared(nodes),
                List.of(blocked, substrate, uses("user", "nested")), ASSEMBLY);

        assertThat(parents(result)).containsEntry("provider", ASSEMBLY)   // its only use is blocked
                .containsEntry("nested", "provider");                     // declared containment wins over the use
    }

    @Test
    void movesADeclaredSubtreeDownWithItsDerivedParent() {
        List<StudioDynamicDcpNode> nodes = new ArrayList<>(nodes("user", "provider"));
        nodes.add(new StudioDynamicDcpNode("inner", "inner", "COMPONENT", "CHILD", "provider", 3, "uploaded:inner",
                List.of(), List.of(), true));
        StudioDerivedContainment.Result result = StudioDerivedContainment.apply(nodes, declared(nodes),
                List.of(uses("user", "provider")), ASSEMBLY);

        assertThat(result.nodes()).filteredOn(node -> node.nodeId().equals("provider")).singleElement()
                .satisfies(node -> assertThat(node.depth()).isEqualTo(3));
        assertThat(result.nodes()).filteredOn(node -> node.nodeId().equals("inner")).singleElement()
                .satisfies(node -> assertThat(node.depth()).isEqualTo(4));
    }

    /**
     * Regression: A declares child B and B uses A. Nesting A under B would make A its own ancestor; it stays under the
     * assembly, and the projection completes with finite depths instead of overflowing the stack.
     */
    @Test
    void neverNestsAComponentUnderItsOwnDeclaredDescendant() {
        List<StudioDynamicDcpNode> nodes = new ArrayList<>(nodes("a"));
        nodes.add(new StudioDynamicDcpNode("b", "b", "COMPONENT", "CHILD", "a", 3, "uploaded:b", List.of(), List.of(), true));
        StudioDerivedContainment.Result result = StudioDerivedContainment.apply(nodes, declared(nodes),
                List.of(uses("b", "a")), ASSEMBLY);

        assertThat(parents(result)).containsEntry("a", ASSEMBLY).containsEntry("b", "a");
        assertThat(result.edges()).noneMatch(edge -> StudioDynamicDcpEdge.DERIVED_FROM_BINDINGS.equals(edge.derivation()));
        assertThat(result.nodes()).filteredOn(node -> node.nodeId().equals("b")).singleElement()
                .satisfies(node -> assertThat(node.depth()).isEqualTo(3));
    }

    @Test
    void changesNothingWithoutUses() {
        List<StudioDynamicDcpNode> nodes = nodes("a", "b");
        StudioDerivedContainment.Result result = StudioDerivedContainment.apply(nodes, declared(nodes), List.of(), ASSEMBLY);

        assertThat(result.nodes()).isEqualTo(nodes);
        assertThat(result.edges()).allSatisfy(edge -> assertThat(edge.derivation()).isEqualTo(StudioDynamicDcpEdge.DECLARED));
    }
}
