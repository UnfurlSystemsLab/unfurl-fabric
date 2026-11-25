package com.unfurl.fabric.studio;

import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object: a Studio layout write (LLD-fabric-studio-api, <em>Layout state extension</em>). It carries the
 * workbench view state; {@code activeView} is kept only so a client still sending the retired tab value is refused by
 * {@link StudioLayoutRules#requireWritable} with a message rather than silently ignored. No view-state value is defaulted.
 */
public record StudioLayoutStateRequest(
        String activeView,
        String semanticZoomLevel,
        String selectedSurface,
        Map<String, Object> camera,
        List<String> annotations,
        String activeViewpoint,
        List<String> activeOverlays,
        String colourOverlay,
        String renderer,
        String layout
) {
    /** Normalizing constructor: absent collections are empty; zoom and surface keep their established defaults. */
    public StudioLayoutStateRequest {
        semanticZoomLevel = semanticZoomLevel == null || semanticZoomLevel.isBlank()
                ? "ASSEMBLY_DCP"
                : semanticZoomLevel;
        selectedSurface = selectedSurface == null || selectedSurface.isBlank() ? "validation" : selectedSurface;
        camera = camera == null ? Map.of() : Map.copyOf(camera);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        activeOverlays = activeOverlays == null ? List.of() : List.copyOf(activeOverlays);
    }

    /** Factory: a write of the given view state, as Studio sends it. */
    public static StudioLayoutStateRequest of(String semanticZoomLevel, String selectedSurface, Map<String, Object> camera,
                                              List<String> annotations, String activeViewpoint, List<String> activeOverlays,
                                              String colourOverlay, String renderer, String layout) {
        return new StudioLayoutStateRequest(null, semanticZoomLevel, selectedSurface, camera, annotations, activeViewpoint,
                activeOverlays, colourOverlay, renderer, layout);
    }
}
