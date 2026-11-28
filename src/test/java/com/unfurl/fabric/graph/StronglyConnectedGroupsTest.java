package com.unfurl.fabric.graph;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared cycle finder: groups, cycles including a self-edge, and no stack overflow on a long chain. */
class StronglyConnectedGroupsTest {

    @Test
    void findsTheCyclesAndLeavesAcyclicNodesApart() {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        edges.put("a", List.of("b"));
        edges.put("b", List.of("c", "a"));
        edges.put("c", List.of("d", "missing"));   // an edge to a node the graph does not list is ignored
        edges.put("d", List.of());
        edges.put("self", List.of("self"));

        StronglyConnectedGroups<String> groups = StronglyConnectedGroups.of(edges);

        assertThat(groups.sameGroup("a", "b")).isTrue();
        assertThat(groups.sameGroup("b", "c")).isFalse();
        assertThat(groups.cycles(edges)).containsExactlyInAnyOrder(List.of("b", "a"), List.of("self"));
    }

    @Test
    void handlesALongChainWithoutRecursion() {
        Map<Integer, List<Integer>> chain = new LinkedHashMap<>();
        int length = 20_000;
        for (int i = 0; i < length; i++) chain.put(i, i + 1 < length ? List.of(i + 1) : List.of(0));

        StronglyConnectedGroups<Integer> groups = StronglyConnectedGroups.of(chain);

        assertThat(groups.sameGroup(0, length - 1)).isTrue();
        assertThat(groups.cycles(chain)).singleElement().satisfies(cycle -> assertThat(cycle).hasSize(length));
    }
}
