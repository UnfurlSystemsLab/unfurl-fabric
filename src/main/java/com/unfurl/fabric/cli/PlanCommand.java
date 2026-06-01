package com.unfurl.fabric.cli;

import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.MatchResult;

import java.io.PrintStream;

final class PlanCommand {
    int run(String[] argv, PrintStream out) {
        CliSupport.Planned planned = CliSupport.planned(CliArgs.parse(argv));
        MatchResult result = planned.result();
        if (result instanceof MatchResult.ExactMatch exact) {
            out.println("EXACT");
            out.println(CliSupport.candidateLine(exact.candidate()));
        } else if (result instanceof MatchResult.Ambiguous ambiguous) {
            out.println("AMBIGUOUS");
            for (CompositionCandidate candidate : ambiguous.candidates()) {
                out.println(CliSupport.candidateLine(candidate));
            }
        } else if (result instanceof MatchResult.NoMatch noMatch) {
            out.println("NO_MATCH");
            noMatch.missing().forEach(m -> out.println("missing " + m.capability() + " " + m.requestedRange()));
        }
        return 0;
    }
}
