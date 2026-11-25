package com.unfurl.fabric.verify;

import com.unfurl.deployment.plan.BindingPlanEntry;
import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.SelectionRecord;
import com.unfurl.substrate.api.SubstratePortRequirement;
import com.unfurl.substrate.api.SubstrateProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one comparison of two compiled contracts. {@code fabric diff} prints its result and Studio's
 * {@code contract-diff} route serializes it, so neither reimplements it (LLD-fabric-studio-api,
 * <i>Contract diff and verification</i>).
 *
 * <p>It compares {@link CompiledContract}s, which signed and unsigned compiles both have: selections by artifact
 * coordinates, deployment entries by {@code componentId/capability}, and, when both substrate profiles are supplied,
 * their required ports by name. A compiled contract carries only its profile's hash, so the profiles are separate
 * inputs; without them the result says whether the hash changed and carries no port delta.
 *
 * <p>Pattern: stateless <b>service</b> (pure comparison: no I/O, no mutation of inputs).
 */
public final class ContractDiffer {

    /**
     * Compares two compiled contracts.
     *
     * @param leftHash     the left contract's canonical hash (a signed envelope's {@code canonicalHash}, or a compile
     *                     record's {@code contractHash}).
     * @param left         the left (base) contract.
     * @param rightHash    the right contract's canonical hash.
     * @param right        the right (target) contract.
     * @param leftProfile  the left substrate profile, or null.
     * @param rightProfile the right substrate profile, or null.
     * @return the diff; {@code ports} is null unless both profiles are supplied.
     * @throws IllegalArgumentException when a contract is null, or exactly one profile is supplied.
     */
    public ContractDiff diff(String leftHash, CompiledContract left, String rightHash, CompiledContract right,
                             SubstrateProfile leftProfile, SubstrateProfile rightProfile) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("both contracts are required");
        }
        if ((leftProfile == null) != (rightProfile == null)) {
            throw new IllegalArgumentException("both substrate profiles are required, or neither");
        }
        Map<String, SelectionRecord> leftSelections = selectionsByCoordinates(left);
        Map<String, SelectionRecord> rightSelections = selectionsByCoordinates(right);
        Set<String> selectionKeys = union(leftSelections.keySet(), rightSelections.keySet());

        Map<String, BindingPlanEntry> leftEntries = bindingEntries(left);
        Map<String, BindingPlanEntry> rightEntries = bindingEntries(right);
        Set<String> entryKeys = union(leftEntries.keySet(), rightEntries.keySet());
        List<ContractDiff.DeploymentShapeChange> changedShapes = new ArrayList<>();
        for (String key : entryKeys) {
            BindingPlanEntry l = leftEntries.get(key);
            BindingPlanEntry r = rightEntries.get(key);
            if (l != null && r != null && deploymentChanged(l, r)) {
                changedShapes.add(new ContractDiff.DeploymentShapeChange(key, String.valueOf(l.deploymentShape()),
                        String.valueOf(r.deploymentShape()), l.requiredSubstratePorts(), r.requiredSubstratePorts()));
            }
        }

        return new ContractDiff(
                leftHash,
                rightHash,
                !Objects.equals(leftHash, rightHash),
                !Objects.equals(left.contract().contractId(), right.contract().contractId()),
                onlyIn(selectionKeys, rightSelections.keySet(), leftSelections.keySet()),
                onlyIn(selectionKeys, leftSelections.keySet(), rightSelections.keySet()),
                selectionKeys.stream()
                        .filter(key -> leftSelections.containsKey(key) && rightSelections.containsKey(key))
                        .filter(key -> selectionChanged(leftSelections.get(key), rightSelections.get(key)))
                        .toList(),
                onlyIn(entryKeys, rightEntries.keySet(), leftEntries.keySet()),
                onlyIn(entryKeys, leftEntries.keySet(), rightEntries.keySet()),
                changedShapes,
                left.substrateProfileHash(),
                right.substrateProfileHash(),
                !Objects.equals(left.substrateProfileHash(), right.substrateProfileHash()),
                leftProfile == null ? null : portDelta(leftProfile, rightProfile));
    }

    /** The port delta between two profiles, by port name. */
    private ContractDiff.PortDelta portDelta(SubstrateProfile left, SubstrateProfile right) {
        Map<String, SubstratePortRequirement> leftPorts = portsByName(left);
        Map<String, SubstratePortRequirement> rightPorts = portsByName(right);
        Set<String> keys = union(leftPorts.keySet(), rightPorts.keySet());
        return new ContractDiff.PortDelta(
                onlyIn(keys, rightPorts.keySet(), leftPorts.keySet()),
                onlyIn(keys, leftPorts.keySet(), rightPorts.keySet()),
                keys.stream()
                        .filter(key -> leftPorts.containsKey(key) && rightPorts.containsKey(key))
                        .filter(key -> portChanged(leftPorts.get(key), rightPorts.get(key)))
                        .toList());
    }

    /** The keys, in order, that are in {@code present} and not in {@code absent}. */
    private static List<String> onlyIn(Set<String> keys, Set<String> present, Set<String> absent) {
        return keys.stream().filter(key -> present.contains(key) && !absent.contains(key)).toList();
    }

    private static Map<String, SelectionRecord> selectionsByCoordinates(CompiledContract contract) {
        Map<String, SelectionRecord> result = new LinkedHashMap<>();
        contract.selections().stream()
                .sorted(Comparator.comparing(s -> s.artifact().coordinates()))
                .forEach(selection -> result.put(selection.artifact().coordinates(), selection));
        return result;
    }

    private static Map<String, BindingPlanEntry> bindingEntries(CompiledContract contract) {
        Map<String, BindingPlanEntry> result = new LinkedHashMap<>();
        if (contract.bindingPlan() == null) {
            return result;
        }
        contract.bindingPlan().entries().stream()
                .sorted(Comparator.comparing(ContractDiffer::bindingKey))
                .forEach(entry -> result.put(bindingKey(entry), entry));
        return result;
    }

    private static Map<String, SubstratePortRequirement> portsByName(SubstrateProfile profile) {
        Map<String, SubstratePortRequirement> result = new LinkedHashMap<>();
        profile.portRequirements().stream()
                .sorted(Comparator.comparing(SubstratePortRequirement::port))
                .forEach(port -> result.put(port.port(), port));
        return result;
    }

    /** Whether a selection on both sides changed: artifact hash, claim hash, binding mode, interface kind or shape. */
    private static boolean selectionChanged(SelectionRecord left, SelectionRecord right) {
        return !Objects.equals(left.artifact().sha256(), right.artifact().sha256())
                || !Objects.equals(left.claimHash(), right.claimHash())
                || left.bindingMode() != right.bindingMode()
                || left.chosenInterfaceKind() != right.chosenInterfaceKind()
                || left.deploymentShape() != right.deploymentShape();
    }

    /** Whether a deployment entry on both sides changed: shape, binding mode, endpoint or required ports. */
    private static boolean deploymentChanged(BindingPlanEntry left, BindingPlanEntry right) {
        return left.deploymentShape() != right.deploymentShape()
                || left.bindingMode() != right.bindingMode()
                || !Objects.equals(left.endpointRef(), right.endpointRef())
                || !Objects.equals(left.requiredSubstratePorts(), right.requiredSubstratePorts());
    }

    private static String bindingKey(BindingPlanEntry entry) {
        return entry.componentId() + "/" + entry.capability();
    }

    /** Whether a port on both sides changed: capability, version range, binding mode, required flag or constraints. */
    private static boolean portChanged(SubstratePortRequirement left, SubstratePortRequirement right) {
        return !Objects.equals(left.capability(), right.capability())
                || !Objects.equals(left.versionRange(), right.versionRange())
                || left.bindingMode() != right.bindingMode()
                || left.required() != right.required()
                || !Objects.equals(left.constraints(), right.constraints());
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        TreeSet<String> result = new TreeSet<>();
        result.addAll(left);
        result.addAll(right);
        return result;
    }
}
