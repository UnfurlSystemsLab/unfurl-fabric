package com.unfurl.fabric.cli;

import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.compiler.DecisionAudit;
import com.unfurl.fabric.compiler.SelectionRecord;
import com.unfurl.fabric.signing.SignedFabricContract;
import com.unfurl.fabric.signing.SignedFabricContractCodec;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class ExplainCommand {
    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);
        SignedFabricContract signed = readSigned(args.requiredPath("contract"));
        CompiledContract compiled = signed.contract();
        DecisionAudit audit = compiled.audit();

        out.println("Fabric contract explanation");
        out.println("contractId=" + compiled.contract().contractId());
        out.println("contractVersion=" + compiled.contract().contractVersion());
        out.println("canonicalHash=" + signed.canonicalHash());
        out.println("signerKeyFingerprint=" + signed.signerKeyFingerprint());
        out.println("signatureAlgorithm=" + signed.algorithm());
        out.println("substrateProfileHash=" + value(compiled.substrateProfileHash()));
        out.println("selectionMode=" + value(audit.selectionMode()));
        out.println();

        out.println("Selections");
        if (compiled.selections().isEmpty()) {
            out.println("- none");
        }
        for (SelectionRecord selection : compiled.selections()) {
            out.println("- " + selection.artifact().coordinates());
            out.println("  artifactSha256=" + selection.artifact().sha256());
            out.println("  claimHash=" + selection.claimHash());
            out.println("  bindingMode=" + selection.bindingMode());
            out.println("  interfaceKind=" + selection.chosenInterfaceKind());
        }
        out.println();

        out.println("Decision path");
        printList(out, "alternativesConsidered", audit.alternativesConsidered());
        printList(out, "selectionReasons", audit.selectionReasons());
        printScores(out, audit.scoreBreakdown());
        printList(out, "planningWarnings", audit.planningWarnings());
        return 0;
    }

    private SignedFabricContract readSigned(Path path) {
        try {
            return new SignedFabricContractCodec().parse(Files.readAllBytes(path));
        } catch (IOException ex) {
            throw FabricCliException.runtime("unable to read signed contract " + path + ": " + ex.getMessage());
        }
    }

    private void printList(PrintStream out, String label, java.util.List<String> values) {
        out.println(label + ":");
        if (values.isEmpty()) {
            out.println("- none");
            return;
        }
        values.forEach(value -> out.println("- " + value));
    }

    private void printScores(PrintStream out, Map<String, Integer> scores) {
        out.println("scoreBreakdown:");
        if (scores.isEmpty()) {
            out.println("- none");
            return;
        }
        scores.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> out.println("- " + entry.getKey() + "=" + entry.getValue()));
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
