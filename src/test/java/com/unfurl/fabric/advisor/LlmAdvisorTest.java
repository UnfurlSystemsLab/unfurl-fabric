package com.unfurl.fabric.advisor;

import com.unfurl.fabric.matcher.MatchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LlmAdvisorTest {
    @Test
    void exactMatchDoesNotInvokeProvider() {
        RecordingProvider provider = new RecordingProvider("unused");
        AdvisorAdvice advice = new LlmAdvisor(provider).advise(AdvisorFixtures.exactContext());

        assertThat(provider.calls()).isZero();
        assertThat(advice.providerInvoked()).isFalse();
        assertThat(advice.explanation()).contains("Exact match");
    }

    @Test
    void ambiguousMatchInvokesProviderForRankingOnly() {
        AdvisorContext context = AdvisorFixtures.ambiguousContext();
        MatchResult.Ambiguous ambiguous = (MatchResult.Ambiguous) context.matchResult();
        String secondId = ambiguous.candidates().get(1).candidateId();
        String firstId = ambiguous.candidates().get(0).candidateId();
        RecordingProvider provider = new RecordingProvider(secondId + "\n" + firstId);

        AdvisorAdvice advice = new LlmAdvisor(provider).advise(context);

        assertThat(provider.calls()).isEqualTo(1);
        assertThat(provider.requests().getFirst().purpose()).isEqualTo("rank-ambiguous-candidates");
        assertThat(provider.requests().getFirst().thinkingEffort()).isEqualTo(ThinkingEffort.LOW);
        assertThat(provider.requests().getFirst().prompt()).contains(firstId, secondId, "without changing validity gates");
        assertThat(advice.providerInvoked()).isTrue();
        assertThat(advice.rankedCandidateIds()).containsExactly(secondId, firstId);
    }

    @Test
    void noMatchInvokesProviderForSuggestions() {
        RecordingProvider provider = new RecordingProvider("""
                Try storage.put@^1
                Add a catalog entry for vector.search
                """);

        AdvisorAdvice advice = new LlmAdvisor(provider).advise(AdvisorFixtures.noMatchContext());

        assertThat(provider.calls()).isEqualTo(1);
        assertThat(provider.requests().getFirst().purpose()).isEqualTo("suggest-substitutes-for-no-match");
        assertThat(provider.requests().getFirst().thinkingEffort()).isEqualTo(ThinkingEffort.HIGH);
        assertThat(provider.requests().getFirst().prompt()).contains("missing vector.search ^1");
        assertThat(advice.providerInvoked()).isTrue();
        assertThat(advice.suggestions()).containsExactly(
                "Try storage.put@^1",
                "Add a catalog entry for vector.search");
    }

    @Test
    void explainUsesMediumThinkingEffort() {
        RecordingProvider provider = new RecordingProvider("Operator explanation");

        AdvisorAdvice advice = new LlmAdvisor(provider).explain(AdvisorFixtures.exactContext(), "why this?");

        assertThat(provider.calls()).isEqualTo(1);
        assertThat(provider.requests().getFirst().purpose()).isEqualTo("explain");
        assertThat(provider.requests().getFirst().thinkingEffort()).isEqualTo(ThinkingEffort.MEDIUM);
        assertThat(provider.requests().getFirst().prompt()).contains("why this?", "Do not alter validity gates");
        assertThat(advice.providerInvoked()).isTrue();
        assertThat(advice.explanation()).isEqualTo("Operator explanation");
    }

    private static final class RecordingProvider implements LlmProvider {
        private final String responseText;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<LlmRequest> requests = new ArrayList<>();

        RecordingProvider(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls.incrementAndGet();
            requests.add(request);
            return new LlmResponse(responseText, java.util.Map.of("provider", "test"));
        }

        int calls() {
            return calls.get();
        }

        List<LlmRequest> requests() {
            return requests;
        }
    }
}
