package com.unfurl.fabric.cli;

import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;
import com.unfurl.fabric.verify.ContractDiff;
import com.unfurl.fabric.verify.ContractDiffer;
import com.unfurl.substrate.api.SubstrateProfile;
import com.unfurl.substrate.api.SubstrateProfileCodec;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code fabric diff}: compares two signed compiled contracts and, optionally, their substrate profiles, and prints the
 * selection, deployment-shape and substrate-profile deltas.
 *
 * <p>Pattern: <b>Presenter</b> over {@link ContractDiffer}, the one comparison Studio's {@code contract-diff} route also
 * uses (LLD-fabric-studio-api, <i>Contract diff and verification</i>). This class only reads the inputs and prints the
 * {@link ContractDiff}; it computes nothing itself.
 */
final class DiffCommand {
    /**
     * Runs the command.
     *
     * @param argv {@code --left}, {@code --right}, and optionally {@code --left-profile} with {@code --right-profile}.
     * @param out  where the diff is printed.
     * @return 0; a usage or read problem is thrown as a {@link FabricCliException}.
     */
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        SignedFabricContract left = readSigned(args.requiredPath("left"));
        SignedFabricContract right = readSigned(args.requiredPath("right"));
        Path leftProfilePath = args.optionalPath("left-profile");
        Path rightProfilePath = args.optionalPath("right-profile");
        if ((leftProfilePath == null) != (rightProfilePath == null)) {
            throw FabricCliException.usage("--left-profile and --right-profile must be provided together");
        }
        SubstrateProfile leftProfile = leftProfilePath == null ? null : readProfile(leftProfilePath);
        SubstrateProfile rightProfile = rightProfilePath == null ? null : readProfile(rightProfilePath);

        ContractDiff diff = new ContractDiffer().diff(left.canonicalHash(), left.contract(),
                right.canonicalHash(), right.contract(), leftProfile, rightProfile);
        print(diff, out);
        return 0;
    }

    /** Prints a diff in the command's fixed line format. */
    private static void print(ContractDiff diff, PrintStream out) {
        out.println("Fabric contract diff");
        out.println("leftCanonicalHash=" + diff.leftCanonicalHash());
        out.println("rightCanonicalHash=" + diff.rightCanonicalHash());
        out.println("canonicalHashChanged=" + diff.canonicalHashChanged());
        out.println("contractIdChanged=" + diff.contractIdChanged());
        out.println();

        out.println("Selection delta");
        printKeys(out, "addedSelections", diff.addedSelections());
        printKeys(out, "removedSelections", diff.removedSelections());
        printKeys(out, "changedSelections", diff.changedSelections());
        out.println();

        out.println("Deployment shape delta");
        printKeys(out, "addedDeploymentEntries", diff.addedDeploymentEntries());
        printKeys(out, "removedDeploymentEntries", diff.removedDeploymentEntries());
        out.println("changedDeploymentShapes:");
        if (diff.changedDeploymentShapes().isEmpty()) {
            out.println("- none");
        } else {
            for (ContractDiff.DeploymentShapeChange change : diff.changedDeploymentShapes()) {
                out.println("- " + change.key() + " " + change.fromShape() + " -> " + change.toShape()
                        + " ports " + change.fromPorts() + " -> " + change.toPorts());
            }
        }
        out.println();

        out.println("Substrate profile delta");
        out.println("leftSubstrateProfileHash=" + value(diff.leftSubstrateProfileHash()));
        out.println("rightSubstrateProfileHash=" + value(diff.rightSubstrateProfileHash()));
        out.println("substrateProfileHashChanged=" + diff.substrateProfileHashChanged());
        if (diff.ports() == null) {
            out.println("profileDetail=<not provided>");
            return;
        }
        printKeys(out, "addedPorts", diff.ports().added());
        printKeys(out, "removedPorts", diff.ports().removed());
        printKeys(out, "changedPorts", diff.ports().changed());
    }

    /** Prints a labelled key list, or {@code - none}. */
    private static void printKeys(PrintStream out, String label, List<String> keys) {
        out.println(label + ":");
        if (keys.isEmpty()) {
            out.println("- none");
            return;
        }
        keys.forEach(key -> out.println("- " + key));
    }

    private static SignedFabricContract readSigned(Path path) {
        try {
            return new SignedFabricContractCodec().parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read signed contract " + path + ": " + ex.getMessage());
        }
    }

    private static SubstrateProfile readProfile(Path path) {
        try {
            return new SubstrateProfileCodec().parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read substrate profile " + path + ": " + ex.getMessage());
        }
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
