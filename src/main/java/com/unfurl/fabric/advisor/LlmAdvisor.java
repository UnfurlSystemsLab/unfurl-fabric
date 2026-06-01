package com.unfurl.fabric.advisor;

import com.unfurl.fabric.matcher.CompositionCandidate;
import com.unfurl.fabric.matcher.MatchResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class LlmAdvisor implements AiAdvisor {
    private final LlmProvider provider;

    public LlmAdvisor(LlmProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        this.provider = provider;
    }

    @Override
    public AdvisorAdvice advise(AdvisorContext context) {
        if (context.matchResult() instanceof MatchResult.ExactMatch exact) {
            return AdvisorAdvice.none("Exact match " + exact.candidate().candidateId()
                    + "; advisor fallback was not invoked.");
        }
        if (context.matchResult() instanceof MatchResult.Ambiguous ambiguous) {
            LlmResponse response = provider.complete(new LlmRequest(
                    "rank-ambiguous-candidates",
                    ambiguousPrompt(context, ambiguous),
                    ThinkingEffort.LOW,
                    Map.of("candidateCount", String.valueOf(ambiguous.candidates().size()))));
            return new AdvisorAdvice(parseRankedCandidateIds(response.text(), ambiguous.candidates()),
                    List.of(), response.text(), true);
        }
        MatchResult.NoMatch noMatch = (MatchResult.NoMatch) context.matchResult();
        LlmResponse response = provider.complete(new LlmRequest(
                "suggest-substitutes-for-no-match",
                noMatchPrompt(context, noMatch),
                ThinkingEffort.HIGH,
                Map.of("missingCount", String.valueOf(noMatch.missing().size()))));
        return new AdvisorAdvice(List.of(), suggestions(response.text()), response.text(), true);
    }

    public AdvisorAdvice explain(AdvisorContext context, String operatorQuestion) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        String question = operatorQuestion == null || operatorQuestion.isBlank()
                ? "Explain the fabric planning result for an operator."
                : operatorQuestion;
        LlmResponse response = provider.complete(new LlmRequest(
                "explain",
                explainPrompt(context, question),
                ThinkingEffort.MEDIUM,
                Map.of()));
        return new AdvisorAdvice(List.of(), List.of(), response.text(), true);
    }

    private String ambiguousPrompt(AdvisorContext context, MatchResult.Ambiguous ambiguous) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Rank valid fabric candidates without changing validity gates or score math.\n");
        prompt.append("Required capabilities: ");
        context.need().requiredCapabilities().forEach(req ->
                prompt.append(req.capability()).append(" ").append(req.capabilityVersion().range()).append("; "));
        prompt.append("\nCandidates:\n");
        for (CompositionCandidate candidate : ambiguous.candidates()) {
            prompt.append(candidate.candidateId())
                    .append(" score=").append(candidate.score().finalScore())
                    .append(" artifacts=");
            candidate.entries().forEach(entry -> prompt.append(entry.artifact().coordinates()).append(","));
            prompt.append("\n");
        }
        return prompt.toString();
    }

    private String noMatchPrompt(AdvisorContext context, MatchResult.NoMatch noMatch) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Suggest substitutes for unmet fabric needs. Do not override validity gates.\n");
        prompt.append("Catalog entries=").append(context.catalog().entries().size()).append("\n");
        noMatch.missing().forEach(missing -> prompt.append("missing ")
                .append(missing.capability()).append(" ").append(missing.requestedRange()).append("\n"));
        noMatch.potentiallyRelevantRejections().forEach(rejection -> prompt.append("rejected ")
                .append(rejection.entry().artifact().coordinates()).append("\n"));
        return prompt.toString();
    }

    private String explainPrompt(AdvisorContext context, String operatorQuestion) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Explain this fabric planning result for an operator. Do not alter validity gates or score math.\n");
        prompt.append("Question: ").append(operatorQuestion).append("\n");
        prompt.append("Required capabilities: ");
        context.need().requiredCapabilities().forEach(req ->
                prompt.append(req.capability()).append(" ").append(req.capabilityVersion().range()).append("; "));
        prompt.append("\nMatch result: ").append(context.matchResult().getClass().getSimpleName()).append("\n");
        return prompt.toString();
    }

    private List<String> parseRankedCandidateIds(String text, List<CompositionCandidate> candidates) {
        LinkedHashSet<String> known = new LinkedHashSet<>();
        candidates.forEach(candidate -> known.add(candidate.candidateId()));
        LinkedHashSet<String> ranked = new LinkedHashSet<>();
        for (String token : text.split("[\\s,;]+")) {
            if (known.contains(token)) {
                ranked.add(token);
            }
        }
        return List.copyOf(ranked);
    }

    private List<String> suggestions(String text) {
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }
}
