package com.unfurl.fabric.matcher;

import com.unfurl.fabric.catalog.CatalogEntry;
import com.unfurl.fabric.needs.CapabilityRequirement;
import com.unfurl.fabric.needs.Need;
import com.unfurl.fabric.testing.FabricTestFixtures;
import com.unfurl.fabric.trust.RejectedEntry;
import com.unfurl.fabric.trust.RejectionReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NoMatchExplanation} builds its text lines and its typed causes from one list of causes, so the CLI, Studio and
 * the advisor say the same thing (LLD-fabric-studio-api, <i>Structured match failures and trust rejections</i>).
 */
class NoMatchExplanationTest {
    private final StructuralMatcher matcher = new StructuralMatcher();

    /** An unmet need: one typed cause whose message is the line without its prefix. */
    @Test
    void anUnmetNeedIsTheSameCauseAsItsLine() {
        CatalogEntry storage = FabricTestFixtures.entry("storage-s3", "storage.put");
        MatchResult.NoMatch noMatch = (MatchResult.NoMatch) matcher.match(List.of(storage),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^7")), List.of());

        assertThat(NoMatchExplanation.causes(noMatch)).singleElement().satisfies(cause -> {
            assertThat(cause.kind()).isEqualTo(MatchFailureReason.Kind.UNMET_NEED);
            assertThat(cause.reasonCode()).isEqualTo("VERSION_NOT_OFFERED");
            assertThat(cause.capability()).isEqualTo("storage.put");
            assertThat(cause.componentCoordinates()).isNull();
            assertThat(NoMatchExplanation.reasons(noMatch))
                    .containsExactly(NoMatchExplanation.UNMET_PREFIX + cause.message());
        });
        assertThat(NoMatchExplanation.trustRejections(noMatch)).isEmpty();
    }

    /** A trust rejection: the unmet need says so, and each rejection reason is one row naming its variant. */
    @Test
    void aTrustRejectionIsOneRowPerReason() {
        CatalogEntry rejectedProvider = FabricTestFixtures.entry("storage-s3", "storage.put");
        RejectedEntry rejection = new RejectedEntry(rejectedProvider, List.of(
                new RejectionReason.VendorNotTrusted("BlockedCo"),
                new RejectionReason.CapabilityDenied("storage.put")));
        MatchResult.NoMatch noMatch = (MatchResult.NoMatch) matcher.match(List.of(),
                Need.ofRequiredCapabilities(CapabilityRequirement.requiredOf("storage.put", "^1")), List.of(rejection));

        assertThat(NoMatchExplanation.causes(noMatch)).singleElement()
                .satisfies(cause -> assertThat(cause.reasonCode()).isEqualTo("REJECTED_BY_TRUST_POLICY"));
        assertThat(NoMatchExplanation.trustRejections(noMatch))
                .extracting(TrustRejectionReason::coordinates, TrustRejectionReason::reasonCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(rejectedProvider.artifact().coordinates(), "VendorNotTrusted"),
                        org.assertj.core.groups.Tuple.tuple(rejectedProvider.artifact().coordinates(), "CapabilityDenied"));
        assertThat(NoMatchExplanation.trustRejections(noMatch)).allSatisfy(row -> assertThat(row.message()).isNotBlank());
    }

    /** Conflicts: distinct by text in first-seen order, each typed by its variant with what it names. */
    @Test
    void conflictsAreDistinctAndTypedByVariant() {
        Conflict dependency = new Conflict.DependencyConflict("com.unfurl:flow:1.0.0", "agent.run@^0.1", "agent.run");
        Conflict refusal = new Conflict.RefusalConflict("billing");
        MatchResult.NoMatch noMatch = new MatchResult.NoMatch(List.of(), List.of(dependency, refusal, dependency),
                List.of());

        assertThat(NoMatchExplanation.causes(noMatch))
                .extracting(MatchFailureReason::reasonCode, MatchFailureReason::capability,
                        MatchFailureReason::componentCoordinates)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("DEPENDENCY", "agent.run", "com.unfurl:flow:1.0.0"),
                        org.assertj.core.groups.Tuple.tuple("REFUSAL", null, null));
        assertThat(NoMatchExplanation.reasons(noMatch)).containsExactly(
                NoMatchExplanation.CONFLICT_PREFIX + dependency.detail(),
                NoMatchExplanation.CONFLICT_PREFIX + refusal.detail());
    }

    /** A NoMatch without causes still prints one line, and has no typed cause. */
    @Test
    void aNoMatchWithoutCausesSaysSo() {
        MatchResult.NoMatch noMatch = new MatchResult.NoMatch(List.of(), List.of(), List.of());

        assertThat(NoMatchExplanation.reasons(noMatch)).containsExactly(NoMatchExplanation.NO_CANDIDATE);
        assertThat(NoMatchExplanation.causes(noMatch)).isEmpty();
    }

    /** A draft's failed validity: its conflicts sorted by text, joined for the details and typed in the same order. */
    @Test
    void draftDetailsAndCausesShareTheirOrder() {
        Conflict version = new Conflict.VersionConflict("storage.put", "^2", "1.0.0");
        Conflict missing = new Conflict.MissingCapabilityConflict("agent.run", "^0.1");
        List<Conflict> conflicts = List.of(version, missing);

        List<MatchFailureReason> causes = NoMatchExplanation.draftCauses(conflicts);

        assertThat(causes).extracting(MatchFailureReason::message)
                .containsExactly(missing.detail(), version.detail());
        assertThat(causes).extracting(MatchFailureReason::reasonCode).containsExactly("MISSING_CAPABILITY", "VERSION");
        assertThat(NoMatchExplanation.draftDetails(conflicts)).isEqualTo(missing.detail() + "; " + version.detail());
        assertThat(NoMatchExplanation.draftDetails(List.of())).isEqualTo(NoMatchExplanation.DRAFT_UNSATISFIED);
    }
}
