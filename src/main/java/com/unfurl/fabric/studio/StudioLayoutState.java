package com.unfurl.fabric.studio;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object: a tenant- and assembly-scoped Studio layout, UI state only and never a contract validity source
 * (LLD-fabric-studio-api, <em>Layout</em> and <em>Layout state extension</em>).
 *
 * <p>It holds the workbench view state (Studio LLD §6): the active view, overlays, colour overlay, renderer and 3D Flow
 * layout. An older stored layout has only {@code activeView}, the retired five-tab value; it is returned as stored, with
 * the view-state fields absent, and Studio maps it (Studio LLD §5.4). Absent values are not serialized. The rules for the
 * values are {@link StudioLayoutRules}'.
 *
 * @param activeView      the retired tab value, present only on an older layout
 * @param activeViewpoint {@code FLOW}, {@code DATA}, {@code TOPOLOGY} or {@code RUNTIME}
 * @param activeOverlays  the distinct active overlays, possibly none
 * @param colourOverlay   the overlay that owns the colour channel, one of {@code activeOverlays}, or none
 * @param renderer        {@code THREE_D}, {@code TWO_D} or {@code TABLE}
 * @param layout          {@code ASSEMBLY} or {@code EXPLODED}, only for 3D Flow
 */
public record StudioLayoutState(
        String tenantId,
        String assemblyId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String activeView,
        String semanticZoomLevel,
        String selectedSurface,
        Map<String, Object> camera,
        List<String> annotations,
        @JsonInclude(JsonInclude.Include.NON_NULL) String activeViewpoint,
        List<String> activeOverlays,
        @JsonInclude(JsonInclude.Include.NON_NULL) String colourOverlay,
        @JsonInclude(JsonInclude.Include.NON_NULL) String renderer,
        @JsonInclude(JsonInclude.Include.NON_NULL) String layout
) {
    /** The layout of an assembly nobody has arranged yet: Flow & Composition in 3D, assembly layout, no overlay. */
    public static StudioLayoutState initial(String tenantId, String assemblyId) {
        return new StudioLayoutState(tenantId, assemblyId, null, "ASSEMBLY_DCP", "validation", Map.of(), List.of(),
                "FLOW", List.of(), null, "THREE_D", "ASSEMBLY");
    }

    /** Normalizing constructor: absent collections are empty, absent scalars stay absent (never defaulted). */
    public StudioLayoutState {
        tenantId = tenantId == null ? "" : tenantId;
        assemblyId = assemblyId == null ? "" : assemblyId;
        activeView = activeView == null || activeView.isBlank() ? null : activeView;
        semanticZoomLevel = semanticZoomLevel == null || semanticZoomLevel.isBlank()
                ? "ASSEMBLY_DCP"
                : semanticZoomLevel;
        selectedSurface = selectedSurface == null || selectedSurface.isBlank() ? "validation" : selectedSurface;
        camera = camera == null ? Map.of() : Map.copyOf(camera);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        activeOverlays = activeOverlays == null ? List.of() : List.copyOf(activeOverlays);
    }

    /** This layout under another tenant and assembly, every other value kept. */
    public StudioLayoutState scopedTo(String tenant, String assembly) {
        return new StudioLayoutState(tenant, assembly, activeView, semanticZoomLevel, selectedSurface, camera, annotations,
                activeViewpoint, activeOverlays, colourOverlay, renderer, layout);
    }
}
