package com.unfurl.fabric.advisor;

public final class NoopAiAdvisor implements AiAdvisor {
    @Override
    public AdvisorAdvice advise(AdvisorContext context) {
        return AdvisorAdvice.none("AI advisor disabled; deterministic fabric result is unchanged.");
    }
}
