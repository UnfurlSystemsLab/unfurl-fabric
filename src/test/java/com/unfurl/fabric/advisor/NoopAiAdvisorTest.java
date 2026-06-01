package com.unfurl.fabric.advisor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoopAiAdvisorTest {
    @Test
    void noopAdvisorNeverInvokesProviderOrReturnsRankings() {
        AdvisorAdvice advice = new NoopAiAdvisor().advise(AdvisorFixtures.ambiguousContext());

        assertThat(advice.providerInvoked()).isFalse();
        assertThat(advice.rankedCandidateIds()).isEmpty();
        assertThat(advice.suggestions()).isEmpty();
        assertThat(advice.explanation()).contains("disabled");
    }
}
