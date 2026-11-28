package com.unfurl.fabric.studio;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the workbench view state in Studio layouts (LLD-fabric-studio-api, <em>Layout state extension</em>) and the
 * derived permissive trust policy warning (<em>Permissive trust policy warning</em>): valid writes are kept as written;
 * every value outside its set, the retired {@code activeView}, a colour overlay that is not active, 3D outside Flow and
 * Topology, and a layout outside 3D Flow are refused naming the field; an older stored layout keeps only
 * {@code activeView} for Studio to map; and the warning is present exactly when there is no trust policy.
 */
class StudioLayoutRulesTest {

    /** A valid write is stored as written, the retired field absent; a new assembly starts in 3D Flow. */
    @Test
    void validViewStateIsKept() {
        StudioCatalogService service = StudioTestCatalog.service();
        StudioLayoutState initial = service.layout("tenant-a", "assembly-a");
        assertThat(initial.activeViewpoint()).isEqualTo("FLOW");
        assertThat(initial.renderer()).isEqualTo("THREE_D");
        assertThat(initial.layout()).isEqualTo("ASSEMBLY");
        assertThat(initial.activeView()).isNull();

        StudioLayoutState saved = service.saveLayout("tenant-a", "assembly-a", write("TOPOLOGY",
                List.of("SECURITY", "COMPLIANCE"), "COMPLIANCE", "TABLE", null));
        assertThat(saved.activeViewpoint()).isEqualTo("TOPOLOGY");
        assertThat(saved.activeOverlays()).containsExactly("SECURITY", "COMPLIANCE");
        assertThat(saved.colourOverlay()).isEqualTo("COMPLIANCE");
        assertThat(saved.renderer()).isEqualTo("TABLE");
        assertThat(saved.layout()).isNull();
        assertThat(service.layout("tenant-a", "assembly-a")).isEqualTo(saved);
    }

    /** Each invalid write is LAYOUT_STATE_INVALID naming its field. */
    @Test
    void invalidViewStateIsRefusedNamingTheField() {
        StudioCatalogService service = StudioTestCatalog.service();
        assertRefused(service, write("COCKPIT", List.of(), null, "TABLE", null), "activeViewpoint");
        assertRefused(service, write("FLOW", List.of(), null, null, null), "renderer");
        assertRefused(service, write("FLOW", List.of("COST"), null, "TABLE", null), "activeOverlays");
        assertRefused(service, write("FLOW", List.of("SECURITY", "SECURITY"), null, "TABLE", null), "twice");
        assertRefused(service, write("FLOW", List.of("SECURITY"), "COMPLIANCE", "TABLE", null), "colourOverlay");
        assertRefused(service, write("DATA", List.of(), null, "THREE_D", null), "THREE_D exists only for FLOW and TOPOLOGY");
        assertRefused(service, write("TOPOLOGY", List.of(), null, "THREE_D", "EXPLODED"), "layout");
        assertRefused(service, write("FLOW", List.of(), null, "TWO_D", "ASSEMBLY"), "layout");
        assertRefused(service, write("FLOW", List.of(), null, "THREE_D", "SIDEWAYS"), "layout");
        assertRefused(service, new StudioLayoutStateRequest("Exploded", null, null, null, null, "FLOW", null, null,
                "THREE_D", null), "activeView");
        assertRefused(service, null, "body");
    }

    /** An older layout with only activeView reads back as stored; a supplied snapshot layout follows the same rules. */
    @Test
    void anOlderLayoutKeepsItsTabValueForStudioToMap() {
        StudioLayoutState legacy = new StudioLayoutState("t", "a", "Exploded", "CHILD_DCP", "payment", Map.of(), List.of(),
                null, null, null, null, null);
        StudioLayoutRules.requireStored(legacy);
        assertThat(legacy.activeView()).isEqualTo("Exploded");
        assertThat(legacy.activeViewpoint()).isNull();
        assertThat(legacy.scopedTo("t2", "a2").activeView()).isEqualTo("Exploded");

        StudioLayoutState empty = new StudioLayoutState("t", "a", null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> StudioLayoutRules.requireStored(empty)).hasMessageContaining("activeViewpoint");
        StudioLayoutState invalid = new StudioLayoutState("t", "a", null, null, null, null, null, "RUNTIME", null, null,
                "THREE_D", null);
        assertThatThrownBy(() -> StudioLayoutRules.requireStored(invalid)).hasMessageContaining(StudioLayoutRules.INVALID);
    }

    /** The permissive warning is present exactly when there is no trust policy, once, and never carried over stale. */
    @Test
    void thePermissiveWarningFollowsTheTrustPolicy() {
        StudioPlanningWarning other = new StudioPlanningWarning("OTHER", "kept");
        StudioDraftSession open = session("", List.of(other));
        assertThat(open.warnings()).extracting(StudioPlanningWarning::code)
                .containsExactly("OTHER", StudioDraftSession.TRUST_POLICY_PERMISSIVE);

        StudioDraftSession replayed = session("", open.warnings());
        assertThat(replayed.warnings()).extracting(StudioPlanningWarning::code).as("never duplicated")
                .containsExactly("OTHER", StudioDraftSession.TRUST_POLICY_PERMISSIVE);

        StudioDraftSession governed = session("trust-acme", open.warnings());
        assertThat(governed.warnings()).extracting(StudioPlanningWarning::code).as("removed once a policy is set")
                .containsExactly("OTHER");
        assertThat(session(null, null).warnings()).extracting(StudioPlanningWarning::code)
                .containsExactly(StudioDraftSession.TRUST_POLICY_PERMISSIVE);
    }

    private static StudioLayoutStateRequest write(String viewpoint, List<String> overlays, String colour, String renderer,
                                                  String layout) {
        return StudioLayoutStateRequest.of(null, null, null, null, viewpoint, overlays, colour, renderer, layout);
    }

    private static void assertRefused(StudioCatalogService service, StudioLayoutStateRequest request, String field) {
        assertThatThrownBy(() -> service.saveLayout("tenant-a", "assembly-a", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(StudioLayoutRules.INVALID)
                .hasMessageContaining(field);
    }

    private static StudioDraftSession session(String trustPolicyId, List<StudioPlanningWarning> warnings) {
        return new StudioDraftSession("tenant-a", "assembly-a", "session-1", "hash", "DYNAMIC", "needs", trustPolicyId,
                "cand", 1, warnings, List.of(), List.of());
    }
}
