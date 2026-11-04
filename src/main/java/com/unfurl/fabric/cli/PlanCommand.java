package com.unfurl.fabric.cli;

import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.MatchResult;
import com.unfurl.fabric.matcher.NoMatchExplanation;
import com.unfurl.fabric.matcher.OptionalDependencies;

import java.io.PrintStream;

/**
 * CLI command: {@code fabric plan} previews matching without compiling. Prints the result kind, then each valid
 * candidate followed by the optional claim dependencies that candidate leaves unprovided (informational, see
 * {@link OptionalDependencies}), or, after {@code NO_MATCH}, the reasons from {@link NoMatchExplanation}.
 */
final class PlanCommand {
    /**
     * Runs the command. Always exits 0, because planning reports rather than fails.
     *
     * @param argv the command arguments.
     * @param out  where the plan is printed.
     * @return the exit code.
     */
    int run(String[] argv, PrintStream out) {
        CliSupport.Planned planned = CliSupport.planned(CliArgs.parse(argv));
        MatchResult result = planned.result();
        if (result instanceof MatchResult.ExactMatch exact) {
            out.println("EXACT");
            printCandidate(exact.candidate(), out);
        } else if (result instanceof MatchResult.Ambiguous ambiguous) {
            out.println("AMBIGUOUS");
            for (CompositionCandidate candidate : ambiguous.candidates()) {
                printCandidate(candidate, out);
            }
        } else if (result instanceof MatchResult.NoMatch noMatch) {
            // plan reports rather than fails, so the reasons go to stdout under the NO_MATCH header.
            out.println("NO_MATCH");
            NoMatchExplanation.reasons(noMatch).forEach(out::println);
        }
        return 0;
    }

    /** Prints one candidate line, then one line per optional dependency the candidate leaves unprovided. */
    private static void printCandidate(CompositionCandidate candidate, PrintStream out) {
        out.println(CliSupport.candidateLine(candidate));
        OptionalDependencies.unprovided(candidate.entries()).forEach(unprovided -> out.println(unprovided.describe()));
    }
}
