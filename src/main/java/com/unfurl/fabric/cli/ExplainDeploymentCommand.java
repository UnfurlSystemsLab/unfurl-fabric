package com.unfurl.fabric.cli;

import com.unfurl.deployment.plan.BindingPlan;
import com.unfurl.deployment.plan.BindingPlanEntry;
import com.unfurl.fabric.compile.HostedComponents;
import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class ExplainDeploymentCommand {
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        SignedFabricContract signed = readSigned(args.requiredPath("contract"));
        BindingPlan plan = signed.contract().bindingPlan();

        out.println("Fabric deployment explanation");
        out.println("canonicalHash=" + signed.canonicalHash());
        if (plan == null || plan.entries().isEmpty()) {
            out.println("bindingPlan=<none>");
            return 0;
        }
        out.println("bindingPlanEntries=" + plan.entries().size());
        for (BindingPlanEntry entry : plan.entries()) {
            out.println("- " + entry.componentId() + " " + entry.capability());
            out.println("  artifactCoordinates=" + entry.artifactCoordinates());
            out.println("  artifactSha256=" + entry.artifactSha256());
            out.println("  deploymentShape=" + entry.deploymentShape());
            out.println("  bindingMode=" + entry.bindingMode());
            out.println("  endpointRef=" + entry.endpointRef().orElse("<none>"));
            out.println("  requiredSubstratePorts=" + entry.requiredSubstratePorts());
            out.println("  host=" + entry.host().map(HostedComponents::describe).orElse("<none>"));
            out.println("  companionArtifacts=" + companions(signed, entry));
        }
        return 0;
    }

    /**
     * The companions pinned for an entry (HLD-in-process-component-hosting §10): read from the child contract that
     * entry's artifact provides its capability through, as {@code coordinates@sha256}; {@code []} when none.
     */
    private static java.util.List<String> companions(SignedFabricContract signed, BindingPlanEntry entry) {
        return signed.contract().childContracts().stream()
                .filter(child -> child.binding().providerCapability().equals(entry.capability())
                        && child.metadata() != null
                        && entry.artifactCoordinates().equals(child.metadata().extensions().get("artifactCoordinates")))
                .flatMap(child -> HostedComponents.companionPins(child).stream())
                .map(pin -> pin.coordinates() + "@" + pin.sha256())
                .distinct()
                .toList();
    }

    private SignedFabricContract readSigned(Path path) {
        try {
            return new SignedFabricContractCodec().parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read signed contract " + path + ": " + ex.getMessage());
        }
    }
}
