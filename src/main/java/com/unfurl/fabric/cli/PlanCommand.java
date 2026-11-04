package com.unfurl.fabric.cli;

import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.MatchResult;
import com.unfurl.fabric.matcher.NoMatchExplanation;

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
            // plan reports rather than fails, so the reasons go to stdout under the NO_MATCH header.
            out.println("NO_MATCH");
            NoMatchExplanation.reasons(noMatch).forEach(out::println);
        }
        return 0;
    }
}
