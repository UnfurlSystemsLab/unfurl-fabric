package com.unfurl.fabric.cli;

import com.unfurl.fabric.compiler.DecisionAudit;
import com.unfurl.fabric.compiler.SelectionRecord;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.MatchResult;
import com.unfurl.fabric.trust.RejectedEntry;

import java.io.PrintStream;
import java.util.Map;
import java.util.stream.Collectors;

final class DryRunCommand {
    int run(String[] argv, PrintStream out, PrintStream err) {
        CliArgs args = CliArgs.parse(argv);
        var deploymentPolicy = CliSupport.loadDeploymentPolicy(args);
        CliSupport.Planned planned = CliSupport.planned(args);
        MatchResult result = planned.result();

        if (result instanceof MatchResult.ExactMatch exact) {
            preview(exact.candidate(), planned, "AUTO_SINGLE", deploymentPolicy, out);
            return 0;
        }
        if (result instanceof MatchResult.Ambiguous ambiguous) {
            if (args.has("auto-select-best")) {
                preview(ambiguous.candidates().get(0), planned, "AUTO_BEST_SCORE", deploymentPolicy, out);
                return 0;
            }
            String selected = args.get("select");
            if (selected != null) {
                CompositionCandidate candidate = ambiguous.findById(selected)
                        .orElseThrow(() -> FabricCliException.usage(
                                "unknown candidate id " + selected + "; valid ids: " + validIds(ambiguous)));
                preview(candidate, planned, "MANUAL", deploymentPolicy, out);
                return 0;
            }
            err.println("ambiguous candidates:");
            ambiguous.candidates().forEach(c -> err.println(CliSupport.candidateLine(c)));
            throw FabricCliException.usage("ambiguous dry-run; pass --select <candidate-id> or --auto-select-best");
        }

        MatchResult.NoMatch noMatch = (MatchResult.NoMatch) result;
        err.println("no match");
        noMatch.missing().forEach(m -> err.println("missing " + m.capability() + " " + m.requestedRange()));
        for (RejectedEntry rejection : noMatch.potentiallyRelevantRejections()) {
            err.println("rejected " + rejection.entry().artifact().coordinates() + " "
                    + rejection.reasons().stream().map(r -> r.detail()).collect(Collectors.joining("; ")));
        }
        throw FabricCliException.runtime("dry-run failed: no valid composition");
    }

    private void preview(
            CompositionCandidate candidate,
            CliSupport.Planned planned,
            String selectionMode,
            com.unfurl.deployment.policy.DeploymentPolicy deploymentPolicy,
            PrintStream out) {
        CliSupport.CompiledWithProfile compiled = CliSupport.compileCandidate(
                candidate, planned.need(), selectionMode, deploymentPolicy);
        DecisionAudit audit = compiled.contract().audit();

        out.println("DRY_RUN");
        out.println("writesFiles=false");
        out.println("candidateId=" + candidate.candidateId());
        out.println("selectionMode=" + selectionMode);
        out.println("finalScore=" + candidate.score().finalScore());
        out.println("contractId=" + compiled.contract().contract().contractId());
        out.println("substrateProfileHash=" + compiled.contract().substrateProfileHash());
        out.println("deploymentShapes=" + compiled.deploymentReport().selectedShapePerComponent());
        out.println();

        out.println("Selections");
        for (SelectionRecord selection : compiled.contract().selections()) {
            out.println("- " + selection.artifact().coordinates());
            out.println("  artifactSha256=" + selection.artifact().sha256());
            out.println("  claimHash=" + selection.claimHash());
            out.println("  bindingMode=" + selection.bindingMode());
            out.println("  deploymentShape=" + selection.deploymentShape());
            out.println("  interfaceKind=" + selection.chosenInterfaceKind());
        }
        out.println();

        out.println("Decision audit");
        printList(out, "alternativesConsidered", audit.alternativesConsidered());
        printList(out, "selectionReasons", audit.selectionReasons());
        printScores(out, audit.scoreBreakdown());
        printList(out, "planningWarnings", audit.planningWarnings());
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

    private String validIds(MatchResult.Ambiguous ambiguous) {
        return ambiguous.candidates().stream()
                .map(CompositionCandidate::candidateId)
                .collect(Collectors.joining(", "));
    }
}
