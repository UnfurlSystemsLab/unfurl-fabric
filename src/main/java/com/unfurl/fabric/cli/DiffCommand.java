package com.unfurl.fabric.cli;

import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.SelectionRecord;
import com.unfurl.deployment.plan.BindingPlanEntry;
import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;
import com.unfurl.substrate.api.SubstratePortRequirement;
import com.unfurl.substrate.api.SubstrateProfile;
import com.unfurl.substrate.api.SubstrateProfileCodec;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

final class DiffCommand {
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        SignedFabricContract left = readSigned(args.requiredPath("left"));
        SignedFabricContract right = readSigned(args.requiredPath("right"));

        out.println("Fabric contract diff");
        out.println("leftCanonicalHash=" + left.canonicalHash());
        out.println("rightCanonicalHash=" + right.canonicalHash());
        out.println("canonicalHashChanged=" + !left.canonicalHash().equals(right.canonicalHash()));
        out.println("contractIdChanged=" + !Objects.equals(
                left.contract().contract().contractId(), right.contract().contract().contractId()));
        out.println();

        printSelectionDelta(out, left.contract(), right.contract());
        printDeploymentDelta(out, left.contract(), right.contract());
        printSubstrateDelta(out, left.contract(), right.contract(), args);
        return 0;
    }

    private void printSelectionDelta(PrintStream out, CompiledContract left, CompiledContract right) {
        Map<String, SelectionRecord> leftSelections = selectionsByCoordinates(left);
        Map<String, SelectionRecord> rightSelections = selectionsByCoordinates(right);
        Set<String> keys = union(leftSelections.keySet(), rightSelections.keySet());

        out.println("Selection delta");
        printKeys(out, "addedSelections", keys.stream()
                .filter(key -> !leftSelections.containsKey(key) && rightSelections.containsKey(key))
                .toList());
        printKeys(out, "removedSelections", keys.stream()
                .filter(key -> leftSelections.containsKey(key) && !rightSelections.containsKey(key))
                .toList());
        printKeys(out, "changedSelections", keys.stream()
                .filter(key -> leftSelections.containsKey(key) && rightSelections.containsKey(key))
                .filter(key -> selectionChanged(leftSelections.get(key), rightSelections.get(key)))
                .toList());
        out.println();
    }

    private void printDeploymentDelta(PrintStream out, CompiledContract left, CompiledContract right) {
        Map<String, BindingPlanEntry> leftEntries = bindingEntries(left);
        Map<String, BindingPlanEntry> rightEntries = bindingEntries(right);
        Set<String> keys = union(leftEntries.keySet(), rightEntries.keySet());

        out.println("Deployment shape delta");
        printKeys(out, "addedDeploymentEntries", keys.stream()
                .filter(key -> !leftEntries.containsKey(key) && rightEntries.containsKey(key))
                .toList());
        printKeys(out, "removedDeploymentEntries", keys.stream()
                .filter(key -> leftEntries.containsKey(key) && !rightEntries.containsKey(key))
                .toList());
        java.util.List<String> changed = keys.stream()
                .filter(key -> leftEntries.containsKey(key) && rightEntries.containsKey(key))
                .filter(key -> deploymentChanged(leftEntries.get(key), rightEntries.get(key)))
                .toList();
        out.println("changedDeploymentShapes:");
        if (changed.isEmpty()) {
            out.println("- none");
        } else {
            for (String key : changed) {
                BindingPlanEntry l = leftEntries.get(key);
                BindingPlanEntry r = rightEntries.get(key);
                out.println("- " + key + " " + l.deploymentShape() + " -> " + r.deploymentShape()
                        + " ports " + l.requiredSubstratePorts() + " -> " + r.requiredSubstratePorts());
            }
        }
        out.println();
    }

    private void printSubstrateDelta(PrintStream out, CompiledContract left, CompiledContract right, CliArgs args) {
        out.println("Substrate profile delta");
        out.println("leftSubstrateProfileHash=" + value(left.substrateProfileHash()));
        out.println("rightSubstrateProfileHash=" + value(right.substrateProfileHash()));
        out.println("substrateProfileHashChanged=" + !Objects.equals(left.substrateProfileHash(), right.substrateProfileHash()));

        Path leftProfilePath = args.optionalPath("left-profile");
        Path rightProfilePath = args.optionalPath("right-profile");
        if (leftProfilePath == null && rightProfilePath == null) {
            out.println("profileDetail=<not provided>");
            return;
        }
        if (leftProfilePath == null || rightProfilePath == null) {
            throw FabricCliException.usage("--left-profile and --right-profile must be provided together");
        }
        SubstrateProfile leftProfile = readProfile(leftProfilePath);
        SubstrateProfile rightProfile = readProfile(rightProfilePath);
        Map<String, SubstratePortRequirement> leftPorts = portsByName(leftProfile);
        Map<String, SubstratePortRequirement> rightPorts = portsByName(rightProfile);
        Set<String> keys = union(leftPorts.keySet(), rightPorts.keySet());

        printKeys(out, "addedPorts", keys.stream()
                .filter(key -> !leftPorts.containsKey(key) && rightPorts.containsKey(key))
                .toList());
        printKeys(out, "removedPorts", keys.stream()
                .filter(key -> leftPorts.containsKey(key) && !rightPorts.containsKey(key))
                .toList());
        printKeys(out, "changedPorts", keys.stream()
                .filter(key -> leftPorts.containsKey(key) && rightPorts.containsKey(key))
                .filter(key -> portChanged(leftPorts.get(key), rightPorts.get(key)))
                .toList());
    }

    private Map<String, SelectionRecord> selectionsByCoordinates(CompiledContract contract) {
        Map<String, SelectionRecord> result = new LinkedHashMap<>();
        contract.selections().stream()
                .sorted(Comparator.comparing(s -> s.artifact().coordinates()))
                .forEach(selection -> result.put(selection.artifact().coordinates(), selection));
        return result;
    }

    private Map<String, BindingPlanEntry> bindingEntries(CompiledContract contract) {
        Map<String, BindingPlanEntry> result = new LinkedHashMap<>();
        if (contract.bindingPlan() == null) {
            return result;
        }
        contract.bindingPlan().entries().stream()
                .sorted(Comparator.comparing(this::bindingKey))
                .forEach(entry -> result.put(bindingKey(entry), entry));
        return result;
    }

    private Map<String, SubstratePortRequirement> portsByName(SubstrateProfile profile) {
        Map<String, SubstratePortRequirement> result = new LinkedHashMap<>();
        profile.portRequirements().stream()
                .sorted(Comparator.comparing(SubstratePortRequirement::port))
                .forEach(port -> result.put(port.port(), port));
        return result;
    }

    private boolean selectionChanged(SelectionRecord left, SelectionRecord right) {
        return !Objects.equals(left.artifact().sha256(), right.artifact().sha256())
                || !Objects.equals(left.claimHash(), right.claimHash())
                || left.bindingMode() != right.bindingMode()
                || left.chosenInterfaceKind() != right.chosenInterfaceKind()
                || left.deploymentShape() != right.deploymentShape();
    }

    private boolean deploymentChanged(BindingPlanEntry left, BindingPlanEntry right) {
        return left.deploymentShape() != right.deploymentShape()
                || left.bindingMode() != right.bindingMode()
                || !Objects.equals(left.endpointRef(), right.endpointRef())
                || !Objects.equals(left.requiredSubstratePorts(), right.requiredSubstratePorts());
    }

    private String bindingKey(BindingPlanEntry entry) {
        return entry.componentId() + "/" + entry.capability();
    }

    private boolean portChanged(SubstratePortRequirement left, SubstratePortRequirement right) {
        return !Objects.equals(left.capability(), right.capability())
                || !Objects.equals(left.versionRange(), right.versionRange())
                || left.bindingMode() != right.bindingMode()
                || left.required() != right.required()
                || !Objects.equals(left.constraints(), right.constraints());
    }

    private Set<String> union(Set<String> left, Set<String> right) {
        TreeSet<String> result = new TreeSet<>();
        result.addAll(left);
        result.addAll(right);
        return result;
    }

    private void printKeys(PrintStream out, String label, java.util.List<String> keys) {
        out.println(label + ":");
        if (keys.isEmpty()) {
            out.println("- none");
            return;
        }
        keys.forEach(key -> out.println("- " + key));
    }

    private SignedFabricContract readSigned(Path path) {
        try {
            return new SignedFabricContractCodec().parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read signed contract " + path + ": " + ex.getMessage());
        }
    }

    private SubstrateProfile readProfile(Path path) {
        try {
            return new SubstrateProfileCodec().parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read substrate profile " + path + ": " + ex.getMessage());
        }
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
