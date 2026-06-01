package com.unfurl.fabric.advisor;

import java.util.List;

public record AdvisorAdvice(
        List<String> rankedCandidateIds,
        List<String> suggestions,
        String explanation,
        boolean providerInvoked
) {
    public AdvisorAdvice {
        rankedCandidateIds = rankedCandidateIds == null ? List.of() : List.copyOf(rankedCandidateIds);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        explanation = explanation == null ? "" : explanation;
    }

    public static AdvisorAdvice none(String explanation) {
        return new AdvisorAdvice(List.of(), List.of(), explanation, false);
    }
}
