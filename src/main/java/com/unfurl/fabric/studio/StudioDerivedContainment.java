package com.unfurl.fabric.studio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.unfurl.fabric.graph.StronglyConnectedGroups;

/**
 * Projector: nests each draft component under the component that uses it (LLD-fabric-studio-api, Containment derived
 * from bindings), so the projection's {@code CONTAINS} tree follows the composition's matched dependencies instead of
 * listing every component directly under the assembly.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>Declared containment wins; only a component declared directly under the assembly can get a derived parent.</li>
 *   <li>A connection between two draft components is a use: its target uses its source. Substrate connections and
 *       {@code BLOCKED} connections are not uses.</li>
 *   <li>A component used by one component is nested under it; one used by several, under their nearest common user
 *       (the user-side ancestor that reaches every user and reaches no other such ancestor); otherwise it stays.</li>
 *   <li>Uses inside one strongly connected group are ignored, so a cycle never becomes containment.</li>
 * </ol>
 * Pure and deterministic: the same nodes, edges and connections always give the same tree. It reads only what Fabric
 * already matched from claims, and never a product name.
 */
final class StudioDerivedContainment {

    private StudioDerivedContainment() {
    }

    /** The projection's nodes and edges with derived containment applied. */
    record Result(List<StudioDynamicDcpNode> nodes, List<StudioDynamicDcpEdge> edges) {
    }

    /**
     * Applies derived containment.
     *
     * @param nodes          the projection's nodes, with declared parents and depths.
     * @param edges          the projection's declared edges.
     * @param connections    the matched connections between draft components (and the substrate).
     * @param assemblyNodeId the assembly node, the declared parent of every component without declared containment.
     * @return the nodes with derived parents and depths, and the edges with each derived parent's edge replacing the
     *         component's edge from the assembly.
     */
    static Result apply(List<StudioDynamicDcpNode> nodes, List<StudioDynamicDcpEdge> edges,
                        List<StudioPortConnectionEdge> connections, String assemblyNodeId) {
        Set<String> components = new LinkedHashSet<>();
        nodes.stream().filter(StudioDynamicDcpNode::replacementAllowed).forEach(node -> components.add(node.nodeId()));
        Map<String, String> declaredParent = new HashMap<>();
        edges.stream().filter(edge -> "CONTAINS".equals(edge.relationship()))
                .forEach(edge -> declaredParent.putIfAbsent(edge.toNodeId(), edge.fromNodeId()));

        // Uses between draft components: user -> the components it uses.
        Map<String, Set<String>> uses = new LinkedHashMap<>();
        components.forEach(id -> uses.put(id, new LinkedHashSet<>()));
        for (StudioPortConnectionEdge connection : connections) {
            String provider = connection.sourceNodeId();
            String user = connection.targetNodeId();
            if (!components.contains(provider) || !components.contains(user) || provider.equals(user)
                    || "BLOCKED".equalsIgnoreCase(connection.status())) {
                continue;
            }
            uses.get(user).add(provider);
        }
        // A cycle never becomes containment: uses inside one strongly connected group are dropped.
        StronglyConnectedGroups<String> groups = StronglyConnectedGroups.of(uses);
        uses.forEach((user, providers) -> providers.removeIf(provider -> groups.sameGroup(provider, user)));

        Map<String, Set<String>> usedBy = new LinkedHashMap<>();
        components.forEach(id -> usedBy.put(id, new LinkedHashSet<>()));
        uses.forEach((user, providers) -> providers.forEach(provider -> usedBy.get(provider).add(user)));
        Map<String, Set<String>> reach = new HashMap<>();
        components.forEach(id -> reach.put(id, reachable(id, uses)));

        // Every proposed parent is checked against the tree built so far, declared and derived together: a parent that
        // is already one of the component's descendants would make it its own ancestor (A declares child B, B uses A).
        Map<String, String> treeParent = new HashMap<>();
        nodes.forEach(node -> { if (node.parentNodeId() != null) treeParent.put(node.nodeId(), node.parentNodeId()); });
        declaredParent.forEach(treeParent::putIfAbsent);
        Map<String, String> derivedParent = new LinkedHashMap<>();
        for (String component : components) {
            if (!assemblyNodeId.equals(declaredParent.get(component))) {
                continue;
            }
            nearestCommonUser(component, usedBy.get(component), components, reach)
                    .filter(parent -> !isAncestorOrSelf(component, parent, treeParent))
                    .ifPresent(parent -> {
                        derivedParent.put(component, parent);
                        treeParent.put(component, parent);
                    });
        }
        if (derivedParent.isEmpty()) {
            return new Result(List.copyOf(nodes), List.copyOf(edges));
        }

        List<StudioDynamicDcpEdge> resultEdges = new ArrayList<>();
        for (StudioDynamicDcpEdge edge : edges) {
            boolean replaced = "CONTAINS".equals(edge.relationship()) && derivedParent.containsKey(edge.toNodeId())
                    && assemblyNodeId.equals(edge.fromNodeId());
            if (!replaced) resultEdges.add(edge);
        }
        derivedParent.forEach((child, parent) -> resultEdges.add(
                new StudioDynamicDcpEdge(parent, child, "CONTAINS", StudioDynamicDcpEdge.DERIVED_FROM_BINDINGS)));

        Map<String, String> parentOf = new HashMap<>();
        nodes.forEach(node -> {
            String parent = derivedParent.getOrDefault(node.nodeId(), node.parentNodeId());
            if (parent != null) parentOf.put(node.nodeId(), parent);
        });
        // Depths follow the final tree, so a component declared under a moved one moves down with it.
        Map<String, Integer> declaredDepth = new HashMap<>();
        nodes.forEach(node -> { if (node.depth() != null) declaredDepth.put(node.nodeId(), node.depth()); });
        Map<String, Integer> memo = new HashMap<>();
        List<StudioDynamicDcpNode> resultNodes = nodes.stream().map(node -> {
            String parent = parentOf.get(node.nodeId());
            Integer depth = node.depth() == null && !derivedParent.containsKey(node.nodeId())
                    ? null : depthOf(node.nodeId(), parentOf, declaredDepth, memo);
            boolean unchanged = Objects.equals(parent, node.parentNodeId()) && Objects.equals(depth, node.depth());
            return unchanged ? node : new StudioDynamicDcpNode(node.nodeId(), node.label(), node.dcpType(), node.level(),
                    parent, depth, node.catalogEntryId(), node.capabilities(), node.compatibleDescendants(),
                    node.replacementAllowed());
        }).toList();
        return new Result(resultNodes, List.copyOf(resultEdges));
    }

    /**
     * The nearest common user of a component: one user when it has exactly one; for several, the single component that
     * reaches every user (or is one) and reaches no other such component. Empty when it has no user, or when no single
     * nearest one exists.
     */
    private static Optional<String> nearestCommonUser(String component, Set<String> users, Set<String> components,
                                                      Map<String, Set<String>> reach) {
        if (users.isEmpty()) return Optional.empty();
        if (users.size() == 1) return Optional.of(users.iterator().next());
        List<String> candidates = new ArrayList<>();
        for (String candidate : components) {
            if (candidate.equals(component)) continue;
            boolean coversAll = users.stream().allMatch(user -> user.equals(candidate) || reach.get(candidate).contains(user));
            if (coversAll) candidates.add(candidate);
        }
        List<String> nearest = candidates.stream()
                .filter(candidate -> candidates.stream().noneMatch(other -> !other.equals(candidate) && reach.get(candidate).contains(other)))
                .toList();
        return nearest.size() == 1 ? Optional.of(nearest.get(0)) : Optional.empty();
    }

    /** Every component {@code start} reaches through uses (not including itself, as the uses graph is acyclic here). */
    private static Set<String> reachable(String start, Map<String, Set<String>> uses) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(uses.get(start));
        while (!stack.isEmpty()) {
            String next = stack.pop();
            if (seen.add(next)) stack.addAll(uses.get(next));
        }
        return seen;
    }

    /**
     * Whether {@code candidate} is {@code component} or one of its descendants: walking up from the candidate through
     * {@code parentOf} reaches the component. The walk stops at a node it has seen, so a malformed input cannot loop.
     */
    private static boolean isAncestorOrSelf(String component, String candidate, Map<String, String> parentOf) {
        Set<String> seen = new LinkedHashSet<>();
        for (String node = candidate; node != null && seen.add(node); node = parentOf.get(node)) {
            if (node.equals(component)) return true;
        }
        return false;
    }

    /**
     * A node's depth: its parent's depth plus one, up to a node with no parent, whose declared depth (or 0) is the base.
     * Iterative and cycle-safe: a chain that returns to a node it has passed has no depth ({@code null}).
     */
    private static Integer depthOf(String id, Map<String, String> parentOf, Map<String, Integer> declaredDepth,
                                   Map<String, Integer> memo) {
        List<String> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String node = id;
        Integer base = null;
        while (node != null) {
            if (memo.containsKey(node)) { base = memo.get(node); break; }
            if (!seen.add(node)) return null;
            chain.add(node);
            String parent = parentOf.get(node);
            if (parent == null) { base = declaredDepth.getOrDefault(node, 0); chain.remove(chain.size() - 1); memo.put(node, base); break; }
            node = parent;
        }
        Integer depth = base;
        for (int i = chain.size() - 1; i >= 0; i--) {
            depth = depth == null ? null : depth + 1;
            memo.put(chain.get(i), depth);
        }
        return memo.get(id);
    }
}
