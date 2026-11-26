package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StudioCompileFreshness}: the one rule for whether a compile record still describes the draft, and the policy
 * hash Fabric alone computes (LLD-fabric-studio-api, <i>Compile records and freshness</i>).
 */
class StudioCompileFreshnessTest {
    private static final String SHA = "a".repeat(64);
    private final StudioCompileFreshness freshness = new StudioCompileFreshness(new StudioDeploymentService());

    /** No record: COMPILE_REQUIRED, naming the session. */
    @Test
    void requiresACompileWhenNothingIsRecorded() {
        StudioCompileFreshness.Verdict verdict = freshness.of(session(3), null, Optional.empty());

        assertThat(verdict.state()).isEqualTo(StudioCompileFreshness.State.COMPILE_REQUIRED);
        assertThat(verdict.message()).contains("session-1");
    }

    /** Same revision, and the same policy when the request names one: current. */
    @Test
    void isCurrentAtTheSameRevisionAndPolicy() {
        StudioCompileRecord record = record(3, freshness.policyHash(policy(List.of("CONTAINERIZED_SERVICE"))));

        assertThat(freshness.of(session(3), record, Optional.empty()).current()).isTrue();
        assertThat(freshness.of(session(3), record, Optional.of(policy(List.of("CONTAINERIZED_SERVICE")))).current())
                .isTrue();
    }

    /** A later revision is stale, naming both revisions. */
    @Test
    void isStaleAfterAnEdit() {
        StudioCompileFreshness.Verdict verdict = freshness.of(session(9), record(7, freshness.policyHash(null)),
                Optional.empty());

        assertThat(verdict.state()).isEqualTo(StudioCompileFreshness.State.COMPILE_STALE);
        assertThat(verdict.message()).isEqualTo("compiled at revision 7; the draft is at revision 9");
    }

    /** Another policy named by the request is stale at the same revision. */
    @Test
    void isStaleForAnotherPolicy() {
        StudioCompileRecord record = record(3, freshness.policyHash(policy(List.of("CONTAINERIZED_SERVICE"))));

        StudioCompileFreshness.Verdict verdict = freshness.of(session(3), record,
                Optional.of(policy(List.of("IN_PROCESS_LIBRARY"))));

        assertThat(verdict.state()).isEqualTo(StudioCompileFreshness.State.COMPILE_STALE);
        assertThat(verdict.message()).contains("different deployment policy");
    }

    /**
     * The hash is of the effective policy: absent and empty lists, and absent and false runtime flags, are one policy;
     * no draft at all means Fabric's default policy, which differs; disallowed shapes are a set.
     */
    @Test
    void hashesTheEffectivePolicy() {
        StudioDeploymentPolicyDraft absentLists = new StudioDeploymentPolicyDraft(null, null, null,
                new StudioDeploymentRuntimeDraft(null, null, null, null, null));
        StudioDeploymentPolicyDraft emptyLists = new StudioDeploymentPolicyDraft(List.of(), List.of(), List.of(),
                new StudioDeploymentRuntimeDraft("", false, false, false, null));

        assertThat(freshness.policyHash(absentLists)).isEqualTo(freshness.policyHash(emptyLists)).matches("[0-9a-f]{64}");
        assertThat(freshness.policyHash(null)).isNotEqualTo(freshness.policyHash(emptyLists));
        assertThat(freshness.policyHash(disallowing("CONTAINERIZED_SERVICE", "REMOTE_MICROSERVICE")))
                .isEqualTo(freshness.policyHash(disallowing("REMOTE_MICROSERVICE", "CONTAINERIZED_SERVICE")));
        assertThat(freshness.policyHash(policy(List.of("CONTAINERIZED_SERVICE", "IN_PROCESS_LIBRARY"))))
                .as("preference order is meaningful")
                .isNotEqualTo(freshness.policyHash(policy(List.of("IN_PROCESS_LIBRARY", "CONTAINERIZED_SERVICE"))));
    }

    /** The current record is the latest. */
    @Test
    void theCurrentRecordIsTheLatest() {
        StudioCompileRecord first = record(1, SHA);
        StudioCompileRecord second = record(2, SHA);
        StudioDraftSession session = session(2).withCompileRecord(first, "COMPILED", Instant.EPOCH)
                .withCompileRecord(second, "COMPILED", Instant.EPOCH);

        assertThat(freshness.currentRecord(session)).hasValue(second);
        assertThat(freshness.currentRecord(session(2))).isEmpty();
    }

    private static StudioDraftSession session(long revision) {
        return new StudioDraftSession("tenant-a", "assembly-a", "session-1", "hash", "DYNAMIC", "needs", "",
                "cand", revision, List.of(), List.of(), List.of());
    }

    private static StudioCompileRecord record(long revision, String policyHash) {
        return new StudioCompileRecord(revision, policyHash, SHA, SHA, SHA, null, Instant.EPOCH);
    }

    private static StudioDeploymentPolicyDraft policy(List<String> preferred) {
        return new StudioDeploymentPolicyDraft(preferred, List.of(), List.of(),
                new StudioDeploymentRuntimeDraft("21", true, true, true, null));
    }

    private static StudioDeploymentPolicyDraft disallowing(String... shapes) {
        return new StudioDeploymentPolicyDraft(List.of(), List.of(shapes), List.of(),
                new StudioDeploymentRuntimeDraft("21", true, true, true, null));
    }
}
