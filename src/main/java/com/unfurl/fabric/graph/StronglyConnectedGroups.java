package com.unfurl.fabric.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graph utility: the strongly connected groups of a directed graph (Tarjan's algorithm), so callers can tell which
 * edges lie on a cycle. Fabric uses it wherever a relationship must not become circular: derived containment ignores
 * uses inside one group, and composition validity refuses child-claim references that form one.
 *
 * <p>Pure and deterministic: nodes are visited in the graph's iteration order, and an edge to a node the graph does not
 * list is ignored. Iterative, so a long chain cannot overflow the stack.
 *
 * @param <T> the node type, with value equality.
 */
public final class StronglyConnectedGroups<T> {

    private final Map<T, Integer> groupOf;
    private final Map<Integer, List<T>> members;

    private StronglyConnectedGroups(Map<T, Integer> groupOf) {
        this.groupOf = Map.copyOf(groupOf);
        Map<Integer, List<T>> byGroup = new LinkedHashMap<>();
        groupOf.forEach((node, group) -> byGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(node));
        this.members = byGroup;
    }

    /**
     * Computes the groups of a graph.
     *
     * @param edges each node's outgoing edges; every node of the graph is a key, edges to non-keys are ignored.
     * @param <T>   the node type.
     * @return the graph's strongly connected groups.
     */
    public static <T> StronglyConnectedGroups<T> of(Map<T, ? extends Collection<T>> edges) {
        Map<T, Integer> index = new HashMap<>();
        Map<T, Integer> low = new HashMap<>();
        Map<T, Integer> group = new LinkedHashMap<>();
        Deque<T> stack = new ArrayDeque<>();
        Set<T> onStack = new LinkedHashSet<>();
        int counter = 0;
        int groups = 0;
        for (T start : edges.keySet()) {
            if (index.containsKey(start)) continue;
            // Iterative depth-first search: each frame is a node and the iterator over its edges.
            Deque<Map.Entry<T, Iterator<T>>> frames = new ArrayDeque<>();
            index.put(start, counter);
            low.put(start, counter++);
            stack.push(start);
            onStack.add(start);
            frames.push(Map.entry(start, edges.get(start).iterator()));
            while (!frames.isEmpty()) {
                Map.Entry<T, Iterator<T>> frame = frames.peek();
                T node = frame.getKey();
                Iterator<T> next = frame.getValue();
                if (next.hasNext()) {
                    T target = next.next();
                    if (!edges.containsKey(target)) continue;
                    if (!index.containsKey(target)) {
                        index.put(target, counter);
                        low.put(target, counter++);
                        stack.push(target);
                        onStack.add(target);
                        frames.push(Map.entry(target, edges.get(target).iterator()));
                    } else if (onStack.contains(target)) {
                        low.put(node, Math.min(low.get(node), index.get(target)));
                    }
                    continue;
                }
                frames.pop();
                if (!frames.isEmpty()) {
                    T parent = frames.peek().getKey();
                    low.put(parent, Math.min(low.get(parent), low.get(node)));
                }
                if (low.get(node).equals(index.get(node))) {
                    T member;
                    do {
                        member = stack.pop();
                        onStack.remove(member);
                        group.put(member, groups);
                    } while (!member.equals(node));
                    groups++;
                }
            }
        }
        return new StronglyConnectedGroups<>(group);
    }

    /** Whether two nodes are in one group: each reaches the other, so an edge between them lies on a cycle. */
    public boolean sameGroup(T a, T b) {
        Integer left = groupOf.get(a);
        return left != null && left.equals(groupOf.get(b));
    }

    /**
     * The cycles of the graph: each group with more than one node, plus any single node with an edge to itself.
     *
     * @param edges the graph the groups were computed from, to recognise a self-edge.
     * @return each cycle's nodes, in the graph's iteration order.
     */
    public List<List<T>> cycles(Map<T, ? extends Collection<T>> edges) {
        List<List<T>> cycles = new ArrayList<>();
        for (List<T> group : members.values()) {
            Collection<T> out = group.size() == 1 ? edges.get(group.get(0)) : null;
            boolean selfEdge = out != null && out.contains(group.get(0));
            if (group.size() > 1 || selfEdge) cycles.add(List.copyOf(group));
        }
        return cycles;
    }
}
