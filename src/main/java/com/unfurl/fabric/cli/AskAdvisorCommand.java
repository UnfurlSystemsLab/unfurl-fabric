package com.unfurl.fabric.cli;

import com.unfurl.fabric.advisor.AdvisorAdvice;
import com.unfurl.fabric.advisor.AdvisorBootstrap;
import com.unfurl.fabric.advisor.AdvisorContext;
import com.unfurl.fabric.advisor.AiAdvisor;
import com.unfurl.fabric.advisor.LlmAdvisor;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs the deterministic plan pipeline (scan + needs + trust + match), then delegates to
 * an {@link AiAdvisor} resolved via {@link AdvisorBootstrap}.
 *
 * <p>This is the natural CLI surface for AI-assisted advisor calls: the operator supplies
 * the same catalog + need + trust-policy inputs that {@code fabric plan} uses, plus the
 * advisor configuration. The deterministic matcher runs first; the advisor sees the full
 * {@link AdvisorContext} (catalog, need, MatchResult) — preserving the
 * deterministic-before-AI wedge.
 *
 * <p>If no advisor is configured, the bootstrap returns {@code NoopAiAdvisor} which produces
 * a deterministic explanation. Fabric stays usable offline; AI is strictly opt-in.
 */
final class AskAdvisorCommand {

    int run(String[] argv, PrintStream out) {
        CliArgs args = CliArgs.parse(argv);

        CliSupport.Planned planned = CliSupport.planned(args);
        AdvisorContext context = new AdvisorContext(planned.catalog(), planned.need(), planned.result());

        AdvisorBootstrap.BootstrapInputs inputs = new AdvisorBootstrap.BootstrapInputs(
                args.optionalPath(AdvisorBootstrap.CliFlag.CONFIG_FILE),
                cliAdvisorFlags(args),
                envAdvisorVars(),
                null);
        AiAdvisor advisor = AdvisorBootstrap.resolve(inputs);

        String question = args.get("question");
        AdvisorAdvice advice = invokeAdvisor(advisor, context, question);

        printAdvice(out, advisor, question, advice);
        return 0;
    }

    private AdvisorAdvice invokeAdvisor(AiAdvisor advisor, AdvisorContext context, String question) {
        if (question != null && !question.isBlank() && advisor instanceof LlmAdvisor llm) {
            return llm.explain(context, question);
        }
        return advisor.advise(context);
    }

    private Map<String, String> cliAdvisorFlags(CliArgs args) {
        Map<String, String> flags = new LinkedHashMap<>();
        copyIfPresent(args, AdvisorBootstrap.CliFlag.PROVIDER, flags);
        copyIfPresent(args, AdvisorBootstrap.CliFlag.MODEL, flags);
        copyIfPresent(args, AdvisorBootstrap.CliFlag.ENDPOINT, flags);
        copyIfPresent(args, AdvisorBootstrap.CliFlag.API_KEY, flags);
        return flags;
    }

    private static void copyIfPresent(CliArgs args, String key, Map<String, String> target) {
        String value = args.get(key);
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private Map<String, String> envAdvisorVars() {
        Map<String, String> env = new LinkedHashMap<>();
        copyEnvIfPresent(AdvisorBootstrap.EnvVar.PROVIDER, env);
        copyEnvIfPresent(AdvisorBootstrap.EnvVar.MODEL, env);
        copyEnvIfPresent(AdvisorBootstrap.EnvVar.ENDPOINT, env);
        copyEnvIfPresent(AdvisorBootstrap.EnvVar.API_KEY, env);
        copyEnvIfPresent(AdvisorBootstrap.EnvVar.CONFIG_FILE, env);
        return env;
    }

    private static void copyEnvIfPresent(String key, Map<String, String> target) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private void printAdvice(PrintStream out, AiAdvisor advisor, String question, AdvisorAdvice advice) {
        out.println("advisor=" + advisor.getClass().getSimpleName());
        out.println("providerInvoked=" + advice.providerInvoked());
        if (question != null && !question.isBlank()) {
            out.println("question=" + question);
        }
        if (!advice.rankedCandidateIds().isEmpty()) {
            out.println("rankedCandidateIds:");
            advice.rankedCandidateIds().forEach(id -> out.println("- " + id));
        }
        if (!advice.suggestions().isEmpty()) {
            out.println("suggestions:");
            advice.suggestions().forEach(s -> out.println("- " + s));
        }
        out.println("explanation:");
        out.println(advice.explanation());
    }
}
