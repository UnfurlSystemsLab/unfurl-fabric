package com.unfurl.fabric.studio;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StudioStateStore {
    private final Path path;
    private final ObjectMapper mapper;

    public StudioStateStore(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        this.path = path;
        this.mapper = StudioJson.mapper();
    }

    public static Path defaultPath() {
        String configured = System.getProperty("unfurl.studio.state.path");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("UNFURL_STUDIO_STATE_PATH");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), ".unfurl", "studio-state.json");
    }

    public synchronized State load() {
        if (!Files.exists(path)) {
            return State.empty();
        }
        try {
            return mapper.readValue(path.toFile(), State.class).normalized();
        } catch (IOException ex) {
            throw new IllegalStateException("unable to read Studio state from " + path, ex);
        }
    }

    public synchronized void save(State state) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), state.normalized());
        } catch (IOException ex) {
            throw new IllegalStateException("unable to write Studio state to " + path, ex);
        }
    }

    public Path path() {
        return path;
    }

    public record State(
            Map<String, List<StudioVisualCatalogEntry>> entriesByTenant,
            Map<String, Map<String, StudioAssemblySummary>> assembliesByTenant,
            Map<String, Map<String, StudioLayoutState>> layoutsByTenant
    ) {
        public static State empty() {
            return new State(Map.of(), Map.of(), Map.of());
        }

        public State normalized() {
            return new State(
                    entriesByTenant == null ? Map.of() : copyEntries(entriesByTenant),
                    assembliesByTenant == null ? Map.of() : copyAssemblies(assembliesByTenant),
                    layoutsByTenant == null ? Map.of() : copyLayouts(layoutsByTenant));
        }

        private static Map<String, List<StudioVisualCatalogEntry>> copyEntries(
                Map<String, List<StudioVisualCatalogEntry>> source
        ) {
            Map<String, List<StudioVisualCatalogEntry>> copy = new HashMap<>();
            source.forEach((tenant, entries) -> copy.put(tenant, entries == null ? List.of() : List.copyOf(entries)));
            return copy;
        }

        private static Map<String, Map<String, StudioAssemblySummary>> copyAssemblies(
                Map<String, Map<String, StudioAssemblySummary>> source
        ) {
            Map<String, Map<String, StudioAssemblySummary>> copy = new HashMap<>();
            source.forEach((tenant, assemblies) -> copy.put(tenant, assemblies == null ? Map.of() : Map.copyOf(assemblies)));
            return copy;
        }

        private static Map<String, Map<String, StudioLayoutState>> copyLayouts(
                Map<String, Map<String, StudioLayoutState>> source
        ) {
            Map<String, Map<String, StudioLayoutState>> copy = new HashMap<>();
            source.forEach((tenant, layouts) -> copy.put(tenant, layouts == null ? Map.of() : Map.copyOf(layouts)));
            return copy;
        }
    }
}
