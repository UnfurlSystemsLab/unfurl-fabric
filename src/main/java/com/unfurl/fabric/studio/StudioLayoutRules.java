package com.unfurl.fabric.studio;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Policy: the rules for the Studio workbench view state stored in a layout (LLD-fabric-studio-api, <em>Layout state
 * extension</em>; unfurl-ui LLD-studio-viewpoints-and-perspectives §5.2, §6).
 *
 * <p>Layout is UI state and never a validity source, but it is still typed: a value outside its set is refused with
 * {@value #INVALID} naming the field, and never coerced to a default. The service applies these rules to every write
 * ({@link #requireWritable}) and to every layout carried by a loaded assembly snapshot ({@link #requireStored}); the
 * records themselves stay plain so persisted state always reads back.
 */
public final class StudioLayoutRules {
    /** A layout value outside its set, or a combination the workbench does not have. */
    public static final String INVALID = "LAYOUT_STATE_INVALID";
    /** The four views. */
    public static final Set<String> VIEWPOINTS = Set.of("FLOW", "DATA", "TOPOLOGY", "RUNTIME");
    /** The four perspective overlays. */
    public static final Set<String> OVERLAYS = Set.of("SECURITY", "PERFORMANCE", "RESILIENCE", "COMPLIANCE");
    /** The three renderers. */
    public static final Set<String> RENDERERS = Set.of("THREE_D", "TWO_D", "TABLE");
    /** The 3D Flow layouts. */
    public static final Set<String> LAYOUTS = Set.of("ASSEMBLY", "EXPLODED");
    /** The views that have a 3D renderer (Studio LLD §5.2). */
    private static final Set<String> THREE_D_VIEWS = Set.of("FLOW", "TOPOLOGY");

    private StudioLayoutRules() {
    }

    /**
     * Guard for a layout write: the request carries the workbench view state, and not the retired {@code activeView}.
     *
     * @throws IllegalArgumentException {@value #INVALID} naming the field
     */
    public static void requireWritable(StudioLayoutStateRequest request) {
        if (request == null) {
            throw invalid("body", "a layout write carries activeViewpoint and renderer");
        }
        if (request.activeView() != null) {
            throw invalid("activeView", "is no longer written; send activeViewpoint, activeOverlays, colourOverlay, "
                    + "renderer and layout");
        }
        requireViewState(request.activeViewpoint(), request.activeOverlays(), request.colourOverlay(), request.renderer(),
                request.layout());
    }

    /**
     * Guard for a layout read from a supplied assembly snapshot: either an older layout with only {@code activeView},
     * which Studio maps, or a valid workbench view state.
     *
     * @throws IllegalArgumentException {@value #INVALID} naming the field
     */
    public static void requireStored(StudioLayoutState layout) {
        boolean legacy = layout.activeViewpoint() == null && layout.renderer() == null && layout.activeOverlays().isEmpty()
                && layout.colourOverlay() == null && layout.layout() == null;
        if (legacy) {
            if (layout.activeView() == null || layout.activeView().isBlank()) {
                throw invalid("activeViewpoint", "is required");
            }
            return;
        }
        requireViewState(layout.activeViewpoint(), layout.activeOverlays(), layout.colourOverlay(), layout.renderer(),
                layout.layout());
    }

    /** The view-state rules of LLD-fabric-studio-api, <em>Layout state extension</em>. */
    private static void requireViewState(String viewpoint, List<String> overlays, String colourOverlay, String renderer,
                                         String layout) {
        requireOneOf("activeViewpoint", viewpoint, VIEWPOINTS);
        requireOneOf("renderer", renderer, RENDERERS);
        Set<String> seen = new HashSet<>();
        for (String overlay : overlays == null ? List.<String>of() : overlays) {
            requireOneOf("activeOverlays", overlay, OVERLAYS);
            if (!seen.add(overlay)) {
                throw invalid("activeOverlays", "names " + overlay + " twice");
            }
        }
        if (colourOverlay != null && !seen.contains(colourOverlay)) {
            throw invalid("colourOverlay", "must be one of the active overlays " + seen + ", not " + colourOverlay);
        }
        if ("THREE_D".equals(renderer) && !THREE_D_VIEWS.contains(viewpoint)) {
            throw invalid("renderer", "THREE_D exists only for FLOW and TOPOLOGY, not " + viewpoint);
        }
        if (layout != null) {
            requireOneOf("layout", layout, LAYOUTS);
            if (!"FLOW".equals(viewpoint) || !"THREE_D".equals(renderer)) {
                throw invalid("layout", "applies only to FLOW with THREE_D");
            }
        }
    }

    private static void requireOneOf(String field, String value, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw invalid(field, "must be one of " + allowed.stream().sorted().toList() + ", not " + value);
        }
    }

    private static IllegalArgumentException invalid(String field, String detail) {
        return new IllegalArgumentException(INVALID + ": " + field + " " + detail);
    }
}
