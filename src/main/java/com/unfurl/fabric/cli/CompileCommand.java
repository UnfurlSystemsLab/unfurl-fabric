package com.unfurl.fabric.cli;

import com.unfurl.fabric.compiler.CompiledContract;
import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.MatchResult;
import com.unfurl.fabric.trust.RejectedEntry;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.stream.Collectors;

final class CompileCommand {
    int run(String[] argv, PrintStream out, PrintStream err) {
        CliArgs args = CliArgs.parse(argv);
        Path target = args.requiredPath("out");
        Path profileTarget = args.optionalPath("substrate-profile-out");
        if (profileTarget == null) {
            profileTarget = CliSupport.defaultProfilePath(target);
        }
        CliSupport.Planned planned = CliSupport.planned(args);
        MatchResult result = planned.result();

        if (result instanceof MatchResult.ExactMatch exact) {
            write(exact.candidate(), planned, target, profileTarget, "AUTO_SINGLE", out);
            return 0;
        }
        if (result instanceof MatchResult.Ambiguous ambiguous) {
            if (args.has("auto-select-best")) {
                write(ambiguous.candidates().get(0), planned, target, profileTarget, "AUTO_BEST_SCORE", out);
                return 0;
            }
            String selected = args.get("select");
            if (selected != null) {
                CompositionCandidate candidate = ambiguous.findById(selected)
                        .orElseThrow(() -> FabricCliException.usage(
                                "unknown candidate id " + selected + "; valid ids: " + validIds(ambiguous)));
                write(candidate, planned, target, profileTarget, "MANUAL", out);
                return 0;
            }
            err.println("ambiguous candidates:");
            ambiguous.candidates().forEach(c -> err.println(CliSupport.candidateLine(c)));
            throw FabricCliException.usage("ambiguous match; pass --select <candidate-id> or --auto-select-best");
        }
        MatchResult.NoMatch noMatch = (MatchResult.NoMatch) result;
        err.println("no match");
        noMatch.missing().forEach(m -> err.println("missing " + m.capability() + " " + m.requestedRange()));
        for (RejectedEntry rejection : noMatch.potentiallyRelevantRejections()) {
            err.println("rejected " + rejection.entry().artifact().coordinates() + " "
                    + rejection.reasons().stream().map(r -> r.detail()).collect(Collectors.joining("; ")));
        }
        throw FabricCliException.runtime("compile failed: no valid composition");
    }

    private static void write(
            CompositionCandidate candidate,
            CliSupport.Planned planned,
            Path target,
            Path profileTarget,
            String selectionMode,
            PrintStream out) {
        CliSupport.CompiledWithProfile compiled = CliSupport.compileCandidate(candidate, planned.need(), selectionMode);
        CliSupport.writeCompiled(compiled.contract(), target);
        CliSupport.writeProfile(compiled.profile(), profileTarget);
        out.println("compiled=" + target);
        out.println("substrateProfile=" + profileTarget);
        out.println("candidateId=" + candidate.candidateId());
        out.println("selectionMode=" + selectionMode);
    }

    private static String validIds(MatchResult.Ambiguous ambiguous) {
        return ambiguous.candidates().stream()
                .map(CompositionCandidate::candidateId)
                .collect(Collectors.joining(", "));
    }
}
