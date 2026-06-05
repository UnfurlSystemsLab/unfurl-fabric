package com.unfurl.fabric.studio;

import java.util.List;
import java.util.Map;

public record StudioLayoutStateRequest(
        String activeView,
        String semanticZoomLevel,
        String selectedSurface,
        Map<String, Object> camera,
        List<String> annotations
) {
    public StudioLayoutStateRequest {
        activeView = activeView == null || activeView.isBlank() ? "Assembly" : activeView;
        semanticZoomLevel = semanticZoomLevel == null || semanticZoomLevel.isBlank()
                ? "ASSEMBLY_DCP"
                : semanticZoomLevel;
        selectedSurface = selectedSurface == null || selectedSurface.isBlank() ? "validation" : selectedSurface;
        camera = camera == null ? Map.of() : Map.copyOf(camera);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }
}
