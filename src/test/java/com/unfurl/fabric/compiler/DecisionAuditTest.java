package com.unfurl.fabric.compiler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionAuditTest {
    @Test
    void capturesDeterministicDecisionPath() {
        DecisionAudit audit = CompilerFixtures.compiled().audit();

        assertThat(audit.compiledAt()).isEqualTo(CompilerFixtures.FIXED_CLOCK.instant());
        assertThat(audit.alternativesConsidered()).containsExactly("com.unfurl:storage-s3:1.0.0");
        assertThat(audit.selectionReasons())
                .anyMatch(reason -> reason.contains("selected com.unfurl:storage-s3:1.0.0"))
                .anyMatch(reason -> reason.contains("satisfied required capabilities"));
        assertThat(audit.scoreBreakdown())
                .containsEntry("finalScore", 77)
                .containsEntry("versionPreferenceScore", 5)
                .containsEntry("footprintPenalty", -3);
        assertThat(audit.planningWarnings()).containsExactly("no provider satisfied optional capability audit.write");
    }
}
