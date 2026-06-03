package com.unfurl.fabric.cli;

import com.unfurl.deployment.plan.BindingPlanEntry;
import com.unfurl.deployment.plan.DeploymentResolutionReport;
import com.unfurl.deployment.plan.RejectedShape;
import com.unfurl.deployment.policy.DeploymentPolicy;
import com.unfurl.deployment.resolver.ResolverOutcome;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.MatchResult;

import java.io.PrintStream;
import java.util.stream.Collectors;

final class ResolveDeploymentCommand {
    int run(String[] argv, PrintStream out, PrintStream err) {
        CliArgs args = CliArgs.parse(argv);
        DeploymentPolicy policy = CliSupport.loadDeploymentPolicy(args);
        CliSupport.Planned planned = CliSupport.planned(args);
        CompositionCandidate candidate = selectCandidate(planned.result(), args, err);
        ResolverOutcome outcome = CliSupport.resolveDeployment(candidate, policy);

        out.println("Deployment resolution");
        out.println("candidateId=" + candidate.candidateId());
        printReport(out, outcome.report());
        out.println("bindingPlan:");
        if (outcome.plan().entries().isEmpty()) {
            out.println("- none");
            return 0;
        }
        for (BindingPlanEntry entry : outcome.plan().entries()) {
            out.println("- " + entry.componentId() + " " + entry.capability());
            out.println("  deploymentShape=" + entry.deploymentShape());
            out.println("  bindingMode=" + entry.bindingMode());
            out.println("  requiredSubstratePorts=" + entry.requiredSubstratePorts());
        }
        return 0;
    }

    private CompositionCandidate selectCandidate(MatchResult result, CliArgs args, PrintStream err) {
        if (result instanceof MatchResult.ExactMatch exact) {
            return exact.candidate();
        }
        if (result instanceof MatchResult.Ambiguous ambiguous) {
            if (args.has("auto-select-best")) {
                return ambiguous.candidates().get(0);
            }
            String selected = args.get("select");
            if (selected != null) {
                return ambiguous.findById(selected)
                        .orElseThrow(() -> FabricCliException.usage(
                                "unknown candidate id " + selected + "; valid ids: " + validIds(ambiguous)));
            }
            err.println("ambiguous candidates:");
            ambiguous.candidates().forEach(c -> err.println(CliSupport.candidateLine(c)));
            throw FabricCliException.usage(
                    "ambiguous deployment resolution; pass --select <candidate-id> or --auto-select-best");
        }
        MatchResult.NoMatch noMatch = (MatchResult.NoMatch) result;
        noMatch.missing().forEach(m -> err.println("missing " + m.capability() + " " + m.requestedRange()));
        throw FabricCliException.runtime("deployment resolution failed: no valid composition");
    }

    private void printReport(PrintStream out, DeploymentResolutionReport report) {
        out.println("selectedShapePerComponent=" + report.selectedShapePerComponent());
        out.println("serviceCount=" + report.serviceCount());
        out.println("maxServices=" + (report.maxServices().isPresent() ? report.maxServices().getAsInt() : "<none>"));
        out.println("rejectedShapes:");
        if (report.rejectedShapesWithReasons().isEmpty()) {
            out.println("- none");
            return;
        }
        report.rejectedShapesWithReasons().forEach((component, rejected) -> {
            out.println("- " + component);
            for (RejectedShape shape : rejected) {
                out.println("  " + shape.shape() + ": " + shape.detail());
            }
        });
    }

    private String validIds(MatchResult.Ambiguous ambiguous) {
        return ambiguous.candidates().stream()
                .map(CompositionCandidate::candidateId)
                .collect(Collectors.joining(", "));
    }
}
